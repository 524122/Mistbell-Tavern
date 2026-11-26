package com.mistbell.tavern.android.render.engine

import android.graphics.Bitmap
import com.facebook.yoga.YogaConstants
import com.mistbell.tavern.android.render.builtin.DefaultTemplate
import com.mistbell.tavern.android.render.draw.SkiaDrawVisitor
import com.mistbell.tavern.android.render.instantiate.SlotFiller
import com.mistbell.tavern.android.render.layout.LayoutSpec
import com.mistbell.tavern.android.render.layout.LayoutSpecBuilder
import com.mistbell.tavern.android.render.layout.YogaTreeBuilder
import com.mistbell.tavern.android.render.markdown.MarkdownToNodes
import com.mistbell.tavern.android.render.model.BindingContext
import com.mistbell.tavern.android.render.model.ContentSegment
import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.ImageLoader
import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.model.TextRect
import com.mistbell.tavern.android.render.model.ThemeVars
import com.mistbell.tavern.android.render.parse.ParsedStylesheet
import org.jetbrains.skia.Surface

/** 渲染请求：分段集（或现成模板）、宽度/密度、主题 token、绑定上下文、图片加载器 */
data class RenderRequest(
    /** 模板；null → 内置默认模板 v1（DefaultTemplate.document()） */
    val template: TemplateDocument? = null,
    /** 叙述段（有序）：Markdown / HTML / 现成 TextBlock */
    val narrative: List<ContentSegment> = emptyList(),
    /** 面板段（有序 HTML 串） */
    val panels: List<String> = emptyList(),
    /** 渲染宽度（dp） */
    val widthDp: Float,
    /** 屏幕密度（bitmap 尺寸 = dp × density；画布先 scale(density)） */
    val density: Float,
    /** 主题包 style.css 解析产物（与默认样式表级联） */
    val themeStylesheet: ParsedStylesheet? = null,
    /** --mt-* token（宿主按当前深浅模式注入） */
    val themeVars: ThemeVars = ThemeVars.EMPTY,
    /** 绑定数据源（MVU KV 扁平化 + 宏） */
    val bindingContext: BindingContext = BindingContext(),
    /** 变量版本（缓存失效四元组之一；宿主经 mvuVariablesVersion 推送） */
    val varsVersion: Long = 0L,
    /** 画布底色（默认透明，气泡背景由 Compose 承担） */
    val canvasBackgroundArgb: Int = 0x00000000,
    /** 图片加载器（img[src]；null → 图片叶子跳过；不参与缓存内容 hash） */
    val imageLoader: ImageLoader? = null,
    /** Markdown 行内高亮调色板（深浅色由宿主选择） */
    val markdownPalette: MarkdownToNodes.Palette = MarkdownToNodes.Palette(),
)

/** 渲染结果：位图 + M5 命中矩形 + 诊断 + 耗时；[fallback] = 引擎失败（宿主落 WebView） */
@Suppress("LongParameterList") // 结果信封字段一一对应管线产物，逐字段命名传参（与 M3 既有调用一致）
class RenderResult(
    val bitmap: Bitmap?,
    val textRects: List<TextRect>,
    val diagnostics: List<Diagnostic>,
    val renderMs: Long,
    /** true = 引擎内部失败，内容未（完整）渲染，宿主应回落 WebView/legacy */
    val fallback: Boolean,
    /** true = 命中位图缓存（未重新光栅） */
    val fromCache: Boolean,
    /**
     * M5 交互会话（链接命中 / 选词查询；仅在主线程使用，见 [RenderSession]）。null =
     * 缓存命中 / 失败 / 空结果等未发生本次光栅的路径——宿主按"无交互"处理。
     */
    val session: RenderSession? = null,
)

/**
 * 面板渲染入口（M3）。内部流程：
 * SlotFiller → InlineCollector → LayoutSpecBuilder → YogaTreeBuilder → calculateLayout(widthDp)
 * → SkiaDrawVisitor（Surface 光栅，画布 scale(density)）→ BitmapBridge → [BitmapCache]。
 *
 * 失败语义：任何内部异常 catch 为 [RenderResult.fallback]（诊断 RENDER_FAILED），**绝不上抛**——
 * 主题包数据问题不是宿主故障，M4 据此落 WebView。display:none / 不可见树在规格构建期剪枝。
 */
class PanelRenderer(
    /** 位图缓存（外部持有以跨渲染复用；默认 24MB 预算） */
    private val cache: BitmapCache<BitmapCacheKey, Bitmap> =
        BitmapCache(BitmapCache.DEFAULT_MAX_BYTES) { bitmap -> bitmap.byteCount },
    /** 测量桥；null → 首次渲染时经 EngineBootstrap 构建（未引导按 fallback 处理） */
    private val measurer: TextMeasurer? = null,
) {
    private val measurerLazy: TextMeasurer? by lazy { measurer ?: EngineBootstrap.fontCollection?.let(::TextMeasurer) }

    fun render(request: RenderRequest): RenderResult {
        val startedAt = System.nanoTime()
        val key = cacheKeyOf(request)
        cache.get(key)?.let { cached ->
            return RenderResult(
                bitmap = cached,
                textRects = emptyList(),
                diagnostics = emptyList(),
                renderMs = elapsedMs(startedAt),
                fallback = false,
                fromCache = true,
            )
        }
        return try {
            renderInternal(request, key, startedAt)
        } catch (ignored: Throwable) {
            RenderResult(
                bitmap = null,
                textRects = emptyList(),
                diagnostics =
                    listOf(
                        Diagnostic(
                            DiagnosticSeverity.ERROR,
                            DiagnosticCodes.RENDER_FAILED,
                            "渲染失败，按 fallback 处理：${ignored.javaClass.simpleName}: ${ignored.message.orEmpty()}",
                        ),
                    ),
                renderMs = elapsedMs(startedAt),
                fallback = true,
                fromCache = false,
            )
        }
    }

    private fun renderInternal(
        request: RenderRequest,
        key: BitmapCacheKey,
        startedAt: Long,
    ): RenderResult {
        val measurerInstance = measurerLazy ?: error("EngineBootstrap 未初始化，无法渲染")
        val widthDp = request.widthDp.coerceAtLeast(MIN_WIDTH_DP)
        val density = request.density.coerceAtLeast(MIN_DENSITY)

        // 1. 实例化（slot 填充 + 绑定）→ 2. 行内合并 → 3. 布局规格（剪枝）
        val filled =
            SlotFiller.fill(
                SlotFiller.FillRequest(
                    template = request.template ?: DefaultTemplate.document(),
                    narrative = request.narrative,
                    panels = request.panels,
                    themeStylesheet = request.themeStylesheet,
                    themeVars = request.themeVars,
                    context = request.bindingContext,
                    markdownPalette = request.markdownPalette,
                ),
            )
        val collected = InlineCollector.collect(filled.root)
        val spec: LayoutSpec =
            LayoutSpecBuilder.build(collected)
                ?: return emptyResult(filled.diagnostics, elapsedMs(startedAt))

        // 4. Yoga 布局（宽约束 = 内容宽，高自适应）
        val builder = YogaTreeBuilder(measurerInstance)
        val built = builder.build(spec)
        built.yoga.calculateLayout(widthDp, YogaConstants.UNDEFINED)

        // 5. 光栅：bitmap 尺寸 = dp × density，画布先 scale(density)
        val widthPx = (widthDp * density).toInt().coerceAtLeast(1)
        val heightPx = (built.yoga.layoutHeight * density).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
        val diagnostics = filled.diagnostics.toMutableList()
        try {
            val canvas = surface.canvas
            canvas.clear(request.canvasBackgroundArgb)
            canvas.scale(density, density)
            val visitor = SkiaDrawVisitor(measurerInstance, request.imageLoader)
            visitor.draw(canvas, built)
            diagnostics += visitor.diagnostics
            // 6. 回传 android.graphics.Bitmap 并入缓存
            val bitmap =
                BitmapBridge.capture(surface, widthPx, heightPx)
                    ?: error("像素回传失败（surface.readPixels）")
            cache.put(key, bitmap)
            return RenderResult(
                bitmap = bitmap,
                textRects = visitor.textRects,
                diagnostics = diagnostics.toList(),
                renderMs = elapsedMs(startedAt),
                fallback = false,
                fromCache = false,
                session =
                    RenderSession.from(
                        blocks = visitor.hitBlocks,
                        bitmapWidthPx = widthPx,
                        bitmapHeightPx = heightPx,
                        density = density,
                    ),
            )
        } finally {
            surface.close()
        }
    }

    /** 整树不可见 → 空结果（成功但无位图，宿主按空内容处理） */
    private fun emptyResult(
        diagnostics: List<Diagnostic>,
        renderMs: Long,
    ): RenderResult =
        RenderResult(
            bitmap = null,
            textRects = emptyList(),
            diagnostics = diagnostics,
            renderMs = renderMs,
            fallback = false,
            fromCache = false,
        )

    /** 缓存 key：内容 hash + 变量版本 + 宽度 + 密度（imageLoader 不参与内容 hash） */
    private fun cacheKeyOf(request: RenderRequest): BitmapCacheKey =
        BitmapCacheKey(
            contentHash = contentHashOf(request),
            varsVersion = request.varsVersion,
            widthDp = request.widthDp.toInt(),
            density = (request.density * DENSITY_SCALE).toInt(),
        )

    private fun contentHashOf(request: RenderRequest): Int {
        var hash = request.template?.hashCode() ?: DEFAULT_TEMPLATE_HASH
        hash = HASH_MULTIPLIER * hash + request.narrative.hashCode()
        hash = HASH_MULTIPLIER * hash + request.panels.hashCode()
        hash = HASH_MULTIPLIER * hash + request.themeStylesheet.hashCode()
        hash = HASH_MULTIPLIER * hash + request.themeVars.hashCode()
        hash = HASH_MULTIPLIER * hash + request.bindingContext.hashCode()
        hash = HASH_MULTIPLIER * hash + request.markdownPalette.hashCode()
        hash = HASH_MULTIPLIER * hash + request.canvasBackgroundArgb
        return hash
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / NANOS_PER_MS

    private companion object {
        const val MIN_WIDTH_DP = 1f
        const val MIN_DENSITY = 1f
        const val DENSITY_SCALE = 100
        const val HASH_MULTIPLIER = 31
        const val DEFAULT_TEMPLATE_HASH = 0
        const val NANOS_PER_MS = 1_000_000L
    }
}
