package com.mistbell.tavern.android.render.engine

import android.graphics.RectF
import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextRect
import com.mistbell.tavern.android.render.model.TextSpan
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.RectHeightMode
import org.jetbrains.skia.paragraph.RectWidthMode
import org.jetbrains.skia.paragraph.TextBox

/**
 * 链接命中结果（M5）：[href] 为跳转目标；[pressRect] 为链接文本的按压区域（画布 dp，
 * 供按压视觉反馈，v0 UI 未绘制）。命中测试输入坐标系：渲染位图的画布 dp（= Compose
 * 叠加层本地 dp，见 [RenderSession] KDoc 的坐标换算说明）。
 */
class LinkHit(
    val href: String,
    val pressRect: RectF,
)

/**
 * 选词结果（M5）：[selectedText] 为选中字符串；矩形两套坐标并存——
 * - [selectionRects]：位图**物理像素**（bitmap px），与 RenderRequest.density 对齐；
 * - [selectionRectsDp]：同一矩形换算为**画布 dp**（px / density），即位图按 FillWidth
 *   展示时 Compose 叠加层的本地 dp 坐标，可直接绘制高亮。
 */
class WordSelection(
    val selectedText: String,
    val selectionRects: List<RectF>,
    val selectionRectsDp: List<RectF>,
)

/** 会话内一个可命中文本块：[rect] 为画布 dp 几何 + span 序列；[paragraph] 为绘制所用 skia 实例（纯 JVM 测试可为 null） */
class RenderHitBlock(
    val rect: TextRect,
    val paragraph: Paragraph? = null,
) {
    /** 块整段文本（span 顺序拼接；字符区间换算基准） */
    val text: String by lazy { rect.spans.joinToString(separator = "") { it.text } }

    /** 字符总数 */
    val textLength: Int get() = text.length
}

/**
 * 渲染会话（M5）：[PanelRenderer] 渲染成功后随 [RenderResult] 保留的交互查询对象——
 * 持有每个 TextBlock 的画布位置、span 序列与绘制时用的 skia [Paragraph] 实例，支撑
 * 链接命中（[linkHitAt]）与长按选词（[wordRangeAt]）。
 *
 * **坐标系**：渲染位图尺寸 = widthDp × density（画布绘制前 scale(density)，画布坐标为
 * dp），而 skia Paragraph 按"内容框宽度（dp 值）"layout——Paragraph 内部坐标同样是画布
 * dp。因此：
 * - 查询入参 xDp/yDp：位图按 FillWidth 原宽展示时 Compose 叠加层的本地 dp 坐标
 *   （像素坐标请先 /density）；
 * - [LinkHit.pressRect] / [WordSelection.selectionRectsDp]：画布 dp，可直接在叠加层绘制；
 * - [WordSelection.selectionRects]：物理像素（dp × density），即位图像素坐标。
 *
 * **线程约定**：实例在渲染线程（Dispatchers.Default）创建，经 RenderResult 交接后**只在
 * 主线程使用**——skia 对象非线程安全；交接本身经 produceState 的状态发布形成
 * happens-before，无并发访问。
 *
 * **生命周期**：[release] 由宿主（EngineMessageContent）在产生新结果 / 离开组合时调用，
 * 幂等；release 后一切查询返回 null/false。Paragraph 的原生生命周期归 TextMeasurer 的
 * LRU 测量缓存所有（M3 设计：同 key 复用同一实例），本类 release **只丢引用、不 close**——
 * close 会毒化缓存中仍会被后续渲染复用的实例（use-after-close），缓存淘汰后由 skiko 的
 * GC 清理回收原生内存。
 */
class RenderSession private constructor(
    private val blocks: List<RenderHitBlock>,
    /** 渲染位图物理像素宽（= widthDp × density；诊断/换算用） */
    val bitmapWidthPx: Int,
    /** 渲染位图物理像素高（= 画布 dp 高 × density） */
    val bitmapHeightPx: Int,
    /** 渲染密度（物理像素 = 画布 dp × density） */
    val density: Float,
) {
    private val rects: List<TextRect> = blocks.map { it.rect }

    @Volatile
    private var released = false

    /** 落点是否在任一文本块的可命中区域内（画布 dp 坐标）。交互层据此决定是否接管手势 */
    fun hitsTextAt(
        xDp: Float,
        yDp: Float,
    ): Boolean = !released && blockIndexAt(rects, xDp, yDp, SNAP_DP) >= 0

    /**
     * 链接命中：落点（画布 dp）所在 span 含 href 时返回 [LinkHit]（含按压矩形）；
     * 无链接 / 空白区 / 已 release 返回 null——调用方应放行事件（穿透给气泡）。
     */
    @Suppress("ReturnCount") // 守卫子句风格：每处早退即一条"不命中"的判定点（与降级路由同型）
    fun linkHitAt(
        xDp: Float,
        yDp: Float,
    ): LinkHit? {
        if (released) return null
        val block = blockAt(xDp, yDp) ?: return null
        val paragraph = block.paragraph ?: return null
        if (block.textLength <= 0) return null
        val charIndex = glyphPositionAt(block, paragraph, xDp, yDp).coerceIn(0, block.textLength - 1)
        val link = linkRangeAt(block.rect.spans, charIndex) ?: return null
        val boxes =
            paragraph.getRectsForRange(
                link.start,
                link.endExclusive,
                RectHeightMode.TIGHT,
                RectWidthMode.TIGHT,
            )
        return LinkHit(link.href, pressRectOf(boxes, block.rect))
    }

    /**
     * 长按选词：落点（画布 dp）所在文本块 → skia 字形坐标命中 → 词边界 → 选区矩形
     * （物理像素 + 画布 dp 两套，见 [WordSelection]）。命中文块但词为空白（落点在
     * 空格/空白带）时退化为落点单字符；仍空白返回 null。
     */
    @Suppress("ReturnCount") // 守卫子句风格：每处早退即一条"不命中"的判定点（与降级路由同型）
    fun wordRangeAt(
        xDp: Float,
        yDp: Float,
    ): WordSelection? {
        if (released) return null
        val block = blockAt(xDp, yDp) ?: return null
        val paragraph = block.paragraph ?: return null
        if (block.textLength <= 0) return null
        val pos = glyphPositionAt(block, paragraph, xDp, yDp)
        val boundary = paragraph.getWordBoundary(pos.coerceIn(0, block.textLength))
        val range =
            resolveWordRange(block.textLength, pos, boundary.start, boundary.end)
                ?: (pos.coerceIn(0, block.textLength - 1)..pos.coerceIn(0, block.textLength - 1))
        val selected = block.text.substring(range.first, range.last + 1)
        if (selected.isBlank()) return null
        val boxes =
            paragraph.getRectsForRange(
                range.first,
                range.last + 1,
                RectHeightMode.TIGHT,
                RectWidthMode.TIGHT,
            )
        val dpRects = boxes.map { box -> blockRectOf(box, block.rect) }
        val pxRects = dpRects.map { rect -> pxRectOf(rect, density) }
        return WordSelection(selectedText = selected, selectionRects = pxRects, selectionRectsDp = dpRects)
    }

    /**
     * 释放会话（幂等）：使一切查询失效并丢掉 skia 引用（实例随之不可达）。**不 close**
     * skia Paragraph——所有权在 TextMeasurer 测量缓存（类 KDoc 详述）；释放后 linkHitAt /
     * wordRangeAt 恒为 null、hitsTextAt 恒为 false。
     */
    fun release() {
        released = true
    }

    /** 落点命中的块；未命中返回 null */
    private fun blockAt(
        xDp: Float,
        yDp: Float,
    ): RenderHitBlock? = blockIndexAt(rects, xDp, yDp, SNAP_DP).takeIf { it >= 0 }?.let(blocks::get)

    /** 字形坐标命中：落点换算到 Paragraph 本地坐标（内容框原点）后的字符位置 */
    private fun glyphPositionAt(
        block: RenderHitBlock,
        paragraph: Paragraph,
        xDp: Float,
        yDp: Float,
    ): Int =
        paragraph
            .getGlyphPositionAtCoordinate(xDp - block.rect.left, yDp - block.rect.top)
            .position

    /** 选区/链接矩形：Paragraph 本地矩形平移到画布 dp */
    private fun blockRectOf(
        box: TextBox,
        rect: TextRect,
    ): RectF =
        RectF(
            box.rect.left + rect.left,
            box.rect.top + rect.top,
            box.rect.left + box.rect.width + rect.left,
            box.rect.top + box.rect.height + rect.top,
        )

    /** 链接按压矩形：全部行矩形取并集（画布 dp）；无框时退化为块原点零矩形 */
    private fun pressRectOf(
        boxes: Array<TextBox>,
        rect: TextRect,
    ): RectF {
        if (boxes.isEmpty()) return RectF(rect.left, rect.top, rect.left, rect.top)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (box in boxes) {
            val r = blockRectOf(box, rect)
            left = minOf(left, r.left)
            top = minOf(top, r.top)
            right = maxOf(right, r.right)
            bottom = maxOf(bottom, r.bottom)
        }
        return RectF(left, top, right, bottom)
    }

    companion object {
        /** 生产入口：渲染遍历收集的块序列 + 位图尺寸/密度 */
        fun from(
            blocks: List<RenderHitBlock>,
            bitmapWidthPx: Int,
            bitmapHeightPx: Int,
            density: Float,
        ): RenderSession = RenderSession(blocks, bitmapWidthPx, bitmapHeightPx, density)

        /** 测试入口：仅矩形（无 skia Paragraph）的块序列，纯 JVM 单测用 */
        internal fun fromRects(
            rects: List<TextRect>,
            bitmapWidthPx: Int,
            bitmapHeightPx: Int,
            density: Float,
        ): RenderSession = RenderSession(rects.map(::RenderHitBlock), bitmapWidthPx, bitmapHeightPx, density)

        /**
         * 画布 dp 下行的水平起点：TextRect.left 是内容框左缘，行内文本按对齐方式落在
         * 内容框内（width = longestLine，contentWidth = 内容框宽）。
         */
        internal fun lineStartX(rect: TextRect): Float =
            when (rect.align) {
                TextAlign.LEFT -> rect.left
                TextAlign.CENTER -> rect.left + (rect.contentWidth - rect.width) / CENTER_HALF
                TextAlign.RIGHT -> rect.left + rect.contentWidth - rect.width
            }

        /** 点是否在块的可命中区域内（含边界；水平范围 = 行区域而非整内容框） */
        internal fun containsPoint(
            rect: TextRect,
            x: Float,
            y: Float,
        ): Boolean {
            val startX = lineStartX(rect)
            return x >= startX && x <= startX + rect.width && y >= rect.top && y <= rect.top + rect.height
        }

        /**
         * 落点所在块下标：先精确包含；未命中时在 [snapDp] 容差内取**垂直距离最近**的块
         * （最近行选择：行间空隙/行框边缘的落点归到最近文本行）；越界返回 -1（空白区，
         * 调用方穿透事件）。
         */
        internal fun blockIndexAt(
            rects: List<TextRect>,
            x: Float,
            y: Float,
            snapDp: Float,
        ): Int {
            for ((index, rect) in rects.withIndex()) {
                if (containsPoint(rect, x, y)) return index
            }
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for ((index, rect) in rects.withIndex()) {
                val startX = lineStartX(rect)
                val horizontalOk = x >= startX - snapDp && x <= startX + rect.width + snapDp
                val distance = verticalDistanceTo(rect, y)
                if (horizontalOk && distance <= snapDp && distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return best
        }

        /**
         * 链接区间判定：字符下标所在 span 含 href 时返回 [LinkRange]（href + 块内字符
         * 区间 [start, endExclusive)）；区间向两侧吸收同 href 相邻 span（嵌套行内元素把
         * 同一链接拆成多 span 的场景）；非链接 span / 越界字符返回 null。
         */
        @Suppress("ReturnCount") // 守卫子句风格：扫描循环的三处早退各有明确语义
        internal fun linkRangeAt(
            spans: List<TextSpan>,
            charIndex: Int,
        ): LinkRange? {
            if (spans.isEmpty() || charIndex < 0) return null
            var offset = 0
            var hitIndex = -1
            for ((index, span) in spans.withIndex()) {
                val spanEnd = offset + span.text.length
                if (charIndex < spanEnd) {
                    hitIndex = index
                    break
                }
                offset = spanEnd
            }
            if (hitIndex < 0) return null
            val href = spans[hitIndex].href ?: return null
            var start = offset
            var end = offset + spans[hitIndex].text.length
            var previous = hitIndex - 1
            while (previous >= 0 && spans[previous].href == href) {
                start -= spans[previous].text.length
                previous -= 1
            }
            var next = hitIndex + 1
            while (next < spans.size && spans[next].href == href) {
                end += spans[next].text.length
                next += 1
            }
            return LinkRange(href, start, end)
        }

        /**
         * 词边界收敛（纯逻辑，不触 skiko）：skia getWordBoundary 的区间端点钳制到文本范围，
         * 区间非法（空/倒序/越界裁空）时退化为落点单字符区间；返回 [IntRange]（闭区间）。
         */
        @Suppress("ReturnCount") // 守卫子句风格：三处早退各有明确语义
        internal fun resolveWordRange(
            textLength: Int,
            pos: Int,
            boundaryStart: Int,
            boundaryEnd: Int,
        ): IntRange? {
            if (textLength <= 0) return null
            var start = boundaryStart.coerceIn(0, textLength)
            var end = boundaryEnd.coerceIn(0, textLength)
            if (end <= start) {
                val anchor = pos.coerceIn(0, textLength - 1)
                start = anchor
                end = anchor + 1
            }
            if (end <= start) return null
            return start until end
        }

        /** 物理像素 → 画布 dp（density <= 0 时原样返回，防御除零） */
        internal fun pxToDp(
            px: Float,
            density: Float,
        ): Float = if (density > 0f) px / density else px

        private fun verticalDistanceTo(
            rect: TextRect,
            y: Float,
        ): Float =
            when {
                y < rect.top -> rect.top - y
                y > rect.top + rect.height -> y - (rect.top + rect.height)
                else -> 0f
            }

        /** 命中判定使用的行间/行缘吸附容差（dp） */
        internal const val SNAP_DP = 8f

        /** 对齐折算的分母（contentWidth - longestLine 的一半） */
        private const val CENTER_HALF = 2f
    }

    /** 块内字符区间（start 闭、endExclusive 开）+ 链接目标 */
    internal data class LinkRange(
        val href: String,
        val start: Int,
        val endExclusive: Int,
    )
}

/** 物理像素矩形换算（画布 dp → bitmap px；density 为渲染请求值） */
private fun pxRectOf(
    dp: RectF,
    density: Float,
): RectF = RectF(dp.left * density, dp.top * density, dp.right * density, dp.bottom * density)
