// 文件承载内容缝全集：路由函数 + 内容体 + 交互 + 图片加载（内聚取舍，函数数超限一并豁免）
@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.mistbell.tavern.android.ui.chat.render

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.mistbell.tavern.android.data.repository.ResolvedLayoutPack
import com.mistbell.tavern.android.data.repository.THEME_SUPPORTED_TEMPLATE_VERSION
import com.mistbell.tavern.android.render.engine.PanelRenderer
import com.mistbell.tavern.android.render.engine.RenderRequest
import com.mistbell.tavern.android.render.engine.RenderResult
import com.mistbell.tavern.android.render.engine.RenderSession
import com.mistbell.tavern.android.render.engine.WordSelection
import com.mistbell.tavern.android.render.markdown.MarkdownToNodes
import com.mistbell.tavern.android.render.model.BindingContext
import com.mistbell.tavern.android.render.model.ContentSegment
import com.mistbell.tavern.android.render.model.ImageLoader
import com.mistbell.tavern.android.render.model.RenderDecision
import com.mistbell.tavern.android.render.model.ThemeVars
import com.mistbell.tavern.android.render.parse.CssParser
import com.mistbell.tavern.android.render.parse.TemplateParser
import com.mistbell.tavern.android.render.parse.TemplateValidator
import com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector
import com.mistbell.tavern.android.ui.theme.AccentGreen
import com.mistbell.tavern.android.ui.theme.AccentGreenDark
import com.mistbell.tavern.android.ui.theme.AccentOrange
import com.mistbell.tavern.android.ui.theme.AccentOrangeDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ---- 降级路由（M4，THEME_FRAMEWORK_DESIGN.md §7）：纯函数 + 全路径单测 ----

/** 消息内容渲染路径：Engine = 引擎渲染；Legacy = MessageBubble 既有分支（Markdown/HtmlBubble） */
sealed interface RenderPath {
    /** 引擎渲染，[pack] 为已通过全部前置检查的布局主题包 */
    data class Engine(val pack: ResolvedLayoutPack) : RenderPath

    /** 整条消息回落 legacy（MessageMarkdown / HtmlCardDetector+HtmlBubble 现有分支） */
    data object Legacy : RenderPath {
        override fun toString() = "Legacy"
    }
}

/**
 * 降级路由决策（纯函数，设计文档 §7 层级 2/3）：
 * 1. 开关关闭 → legacy（引擎代码路径完全不参与）；
 * 2. 无 layout 包 / 模板版本不受支持 → legacy；
 * 3. 引擎未引导（EngineBootstrap 未 init，防御位）→ legacy；
 * 4. 任一 HTML 卡段含白名单外危险构造（table/svg/form 等）→ legacy。legacy 里 style/table
 *    交给 litehtml 画成位图；script、iframe 和事件处理仍走 HtmlBubble。引擎管线对危险元素
 *    是"静默丢弃"，路由必须提前把整条消息送出引擎，避免内容凭空消失；
 * 5. 其余 → engine。渲染期故障（RenderResult.fallback）在组合后暴露，由 [EngineMessageContent]
 *    原地切 legacy 兜底（引擎异常绝不上抛，绝不崩聊天页）。
 */
@Suppress("ReturnCount") // 守卫子句风格：每处早退即一条降级路径的判定点（与 MvuEngine 同型）
fun resolveRenderPath(
    engineEnabled: Boolean,
    pack: ResolvedLayoutPack?,
    segments: List<HtmlCardDetector.Part>,
    engineReady: Boolean,
): RenderPath {
    if (!engineEnabled) return RenderPath.Legacy
    if (pack == null) return RenderPath.Legacy
    if (pack.templateVersion != THEME_SUPPORTED_TEMPLATE_VERSION) return RenderPath.Legacy
    if (!engineReady) return RenderPath.Legacy
    val dangerous =
        segments.any { part ->
            part is HtmlCardDetector.Part.HtmlDoc &&
                TemplateValidator.decideHtml(part.html, pack.templateVersion) != RenderDecision.Renderable
        }
    if (dangerous) return RenderPath.Legacy
    return RenderPath.Engine(pack)
}

/** contentDescription 兜底文本上限（无障碍子树一条语义描述；§8 已知限制） */
private const val ACCESSIBILITY_TEXT_MAX_CHARS = 2000

/** 图片源解析诊断日志 tag */
private const val TAG = "EngineContent"

/** 内容 hash 乘数（与 PanelRenderer.contentHashOf 同风格） */
private const val CONTENT_HASH_MULTIPLIER = 31

/**
 * 引擎消息内容体（M4 内容缝）：正则后显示内容 → HtmlCardDetector 分段 → 模板 slot 填充 →
 * PanelRenderer 在 Dispatchers.Default 渲染 → Bitmap 展示（气泡壳/手势仍归 Compose）。
 *
 * 失败语义：渲染结果 [RenderResult.fallback]（引擎内部异常/未引导）→ 原地切 [legacy] 内容
 * （MessageBubble 既有分支），绝不崩聊天页、绝不静默丢内容。
 * 产图键 = (内容 hash, 变量版本, 宽度 px, 深浅色, 包 id)：任一变化才重新光栅（§2.2 四元组
 * + 包维度），PanelRenderer 内部另有 Bitmap LruCache 二级缓存。
 */
@Composable
@Suppress("LongMethod", "LongParameterList") // 内容缝入参与 MessageBubble 既有参数一一对应
fun EngineMessageContent(
    content: String,
    pack: ResolvedLayoutPack,
    dark: Boolean,
    characterName: String,
    variables: VariablesSnapshot,
    modifier: Modifier = Modifier,
    /** 渲染失败（fallback）时原地回落的内容体（MessageBubble legacy 分支闭包） */
    legacy: @Composable () -> Unit,
) {
    // 分段（与 legacy 同一检测器，纯函数）：Markdown 段 → narrative slot，HTML 段 → panels slot
    val parts = remember(content) { HtmlCardDetector.split(content) }
    val narrative =
        remember(parts) {
            parts.filterIsInstance<HtmlCardDetector.Part.Markdown>().map { ContentSegment.Markdown(it.text) }
        }
    val panels =
        remember(parts) {
            parts.filterIsInstance<HtmlCardDetector.Part.HtmlDoc>().map { it.html }
        }
    val plainText =
        remember(parts) {
            parts
                .joinToString(separator = "\n") { part ->
                    when (part) {
                        is HtmlCardDetector.Part.Markdown -> part.text
                        is HtmlCardDetector.Part.HtmlDoc -> part.html
                    }
                }
                .take(ACCESSIBILITY_TEXT_MAX_CHARS)
        }

    // --mt-* token 与主题样式表按当前深浅色取一套（§5.4：样式表无需感知模式）
    val vars = if (dark) pack.themeVarsDark else pack.themeVarsLight
    val themeVars = remember(vars) { ThemeVars(vars) }
    val themeStylesheet =
        remember(pack.styleCss, vars) { pack.styleCss?.let { css -> CssParser.parse(css, themeVars) } }
    val template =
        remember(pack.packId, pack.templateHtml, pack.templateVersion) {
            TemplateParser.parse(pack.templateHtml, pack.templateVersion)
        }
    val markdownPalette =
        remember(dark) {
            MarkdownToNodes.Palette(
                // 引号绿 / 动作括号橙：与 MarkdownRenderer 深浅色变体同源（叙述映射行内高亮）
                quoteColorArgb = (if (dark) AccentGreenDark else AccentGreen).toArgb().toLong(),
                actionColorArgb = (if (dark) AccentOrangeDark else AccentOrange).toArgb().toLong(),
            )
        }
    val bindingContext =
        remember(variables, characterName) {
            BindingContext(
                variables = variables.variables,
                // 宏上下文最小集：{{char}} = 说话方名（{{user}} 无 persona 数据源，不注册、原样保留）
                macros = if (characterName.isBlank()) emptyMap() else mapOf("char" to characterName),
            )
        }
    val filesRoot = LocalContext.current.filesDir
    val imageLoader =
        remember(pack.packId, filesRoot) {
            ImageLoader { resolvePackImageBytes(filesRoot, pack.packId, it) }
        }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val widthDp = if (density.density > 0f) widthPx / density.density else 0f
        val contentHash = remember(content, narrative, panels) { contentHashOf(content, narrative, panels) }

        // 引擎产图（后台线程）；fallback → 原地切 legacy。键含包 id：换包即重光栅
        val result =
            produceState<RenderResult?>(
                initialValue = null,
                contentHash,
                variables.version,
                widthPx,
                dark,
                pack.packId,
            ) {
                value =
                    if (widthPx <= 0 || widthDp <= 0f) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            PanelRenderer().render(
                                RenderRequest(
                                    template = template,
                                    narrative = narrative,
                                    panels = panels,
                                    widthDp = widthDp,
                                    density = density.density,
                                    themeStylesheet = themeStylesheet,
                                    themeVars = themeVars,
                                    bindingContext = bindingContext,
                                    varsVersion = variables.version,
                                    imageLoader = imageLoader,
                                    markdownPalette = markdownPalette,
                                ),
                            )
                        }
                    }
            }

        val rendered = result.value
        if (rendered != null && rendered.fallback) {
            // 引擎内部失败（诊断含 RENDER_FAILED）：整条回落 legacy（设计文档 §7 层级 2）
            legacy()
        } else {
            val bitmap = rendered?.bitmap
            if (bitmap != null) {
                EngineRenderedContent(
                    bitmap = bitmap,
                    session = rendered.session,
                    plainText = plainText,
                )
            }
            // bitmap == null 且非 fallback（整树不可见的合法空内容）→ 空内容体
        }
    }
}

/** 选区高亮的透明度（secondaryContainer 基色上的叠加值，设计文档 §8 长按选词） */
private const val SELECTION_HIGHLIGHT_ALPHA = 0.4f

/**
 * 引擎位图内容体（M5 交互缝）：Image 之上叠选区高亮层与点按/长按手势层——
 * - 点按链接：session.linkHitAt 命中 href → ACTION_VIEW 打开（仅 http/https；无命中不消费，
 *   事件穿透给气泡）；
 * - 长按选词：session.wordRangeAt → 半透明高亮 + 底部「复制」小弹出（照长按 pill 风格）；
 * - 点击任意处清除既有选区；会话替换/离开组合时 release（幂等）。
 *
 * 手势语义详见 [observeTapAndLongPress]：只拦截"点按/长按"，绝不消费拖动——列表滚动、
 * 气泡横滑不受影响；长按 pill 在文本区让位给选词、在空白区照常触发。
 */
@Composable
private fun EngineRenderedContent(
    bitmap: android.graphics.Bitmap,
    session: RenderSession?,
    plainText: String,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 选区状态按会话键控：新内容（新 session）自动清除旧选区（旧矩形坐标已失效）
    var selection by remember(session) { mutableStateOf<WordSelection?>(null) }

    // 会话生命周期：新结果产生（session 替换）或本内容体离开组合时释放（release 幂等）
    DisposableEffect(session) {
        onDispose { session?.release() }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = plainText.ifBlank { null },
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
        val highlight = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = SELECTION_HIGHLIGHT_ALPHA)
        selection?.let { selected ->
            // 选区高亮：selectionRectsDp 是画布 dp == 本 Box 本地 dp（位图按 FillWidth 原宽展示）
            Canvas(modifier = Modifier.matchParentSize()) {
                selected.selectionRectsDp.forEach { rect ->
                    drawRect(
                        color = highlight,
                        topLeft = Offset(rect.left.dp.toPx(), rect.top.dp.toPx()),
                        size = Size(rect.width().dp.toPx(), rect.height().dp.toPx()),
                    )
                }
            }
            WordCopyPopup(
                onCopy = {
                    copyToClipboard(context, selected.selectedText)
                    selection = null
                },
            )
        }
        if (session != null) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .pointerInput(session, density.density) {
                            observeTapAndLongPress(
                                session = session,
                                density = density.density,
                                onTap = { xDp, yDp ->
                                    if (selection != null) selection = null
                                    // 无命中不处理：事件未消费，穿透给气泡（气泡 onClick 为空，无副作用）
                                    val link = session.linkHitAt(xDp, yDp) ?: return@observeTapAndLongPress
                                    openLink(context, link.href)
                                },
                                onLongPress = { xDp, yDp ->
                                    session.wordRangeAt(xDp, yDp)?.let { selection = it }
                                },
                            )
                        },
            )
        }
    }
}

/**
 * 观察式点按/长按检测（M5 交互核心）：只拦截"点按/长按"，绝不吃拖动。
 *
 * 为什么不用 `detectTapGestures`：foundation 1.7.6 的该检测器在 `awaitFirstDown()` 后
 * **无条件 consume down**，而外层气泡长按 pill（combinedClickable）以"未消费的 down"为
 * 起点——内层无条件消费后气泡在整个手势里拿不到 down，引擎内容上的长按 pill 会永久失效；
 * 且其长按超时路径不复查事件消费，事后无法补救。本检测器因此"先观察、后接管"：
 * - down 仅在落点命中文本块（[RenderSession.hitsTextAt]）时消费 = 接管该手势（文本上的
 *   长按应选词而非开 pill）；空白区完全不消费 → 点按/长按整体穿透（气泡 onClick 为空、
 *   长按开 pill 照常）；
 * - move 从不主动消费：位移超 touch slop 或已被他人消费（外层横滑/列表滚动接管）即放手；
 *   气泡横滑与 LazyColumn 滚动均用 requireUnconsumed=false 的拖动检测，消费 down 不影响
 *   其启动，故接管与滚动/横滑天然共存；
 * - 接管后的长按执行回调并消费后续 move/up（与外层 pill 长按后的行为一致：同一次按下
 *   的滑动/点击自动取消，选词弹出与 pill 不会并存）。
 *
 * [onTap]/[onLongPress] 入参为画布 dp 坐标（位图按 FillWidth 原宽展示时 = 本内容体本地 dp，
 * 与 [RenderSession] 查询坐标系一致）。
 */
private suspend fun PointerInputScope.observeTapAndLongPress(
    session: RenderSession,
    density: Float,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val xDp = down.position.x / density
        val yDp = down.position.y / density
        val claimed = session.hitsTextAt(xDp, yDp)
        if (claimed) down.consume()
        var dragged = false
        var drift = 0f
        try {
            withTimeout(viewConfiguration.longPressTimeoutMillis.toLong()) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) return@withTimeout // 抬起 → 点按
                    if (change.isConsumed) {
                        // 他人接管（外层横滑 / 列表滚动已消费位移）：让渡，本手势不再处理
                        dragged = true
                        return@withTimeout
                    }
                    // 累计位移超 touch slop 才算拖动（slop 内的手指微颤不打断长按，与
                    // View/foundation 长按的容差语义一致）
                    drift += change.positionChange().getDistance()
                    if (drift > viewConfiguration.touchSlop) {
                        dragged = true
                        return@withTimeout
                    }
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            // 长按超时：仅接管的手势回调并吃掉后续事件（阻断 pill 与横滑在同一次按下的二次触发）。
            // 注意须捕获 Compose AwaitPointerEventScope.withTimeout 抛出的
            // PointerEventTimeoutCancellationException（CancellationException 子类），而非
            // kotlinx 的 TimeoutCancellationException——捕获错类会让异常逸出、检测协程静默
            // 失效（手势全部穿透给外层气泡，长按选词永久失效）。
            if (claimed) {
                onLongPress(xDp, yDp)
                consumeUntilUp()
            }
            return@awaitEachGesture
        }
        if (!dragged) onTap(xDp, yDp)
    }
}

/** 长按后的兜底消费：吃掉全部后续事件直到抬指（foundation detectTapGestures 同款语义） */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) break
    }
}

/**
 * 选词复制小弹出（照长按 pill 风格：全圆 Surface + 图标标签动作项）。Popup 独立窗口绘制，
 * 溢出气泡的部分不被列表视口裁剪（同 MessageBubble.BubbleActionsPopup 的窗口化理由）；
 * focusable=false 不抢输入焦点，点外部事件照常落到下层（点空白 → 选区清除 → 本弹出消失）。
 */
@Composable
private fun WordCopyPopup(onCopy: () -> Unit) {
    Popup(alignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier =
                    Modifier
                        .sizeIn(minWidth = 56.dp, minHeight = 40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCopy),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 选中文本落系统剪贴板（ClipboardManager；label 供剪贴板 UI 展示来源） */
private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard != null) {
        clipboard.setPrimaryClip(ClipData.newPlainText("engine-selection", text))
    }
}

/**
 * 链接跳转白名单（纯字符串逻辑，JVM 可单测）：仅 http/https 放行——引擎内容来自主题包
 * （第三方数据），intent://、file://、自定义 scheme 一律拒绝；其余大小写不敏感识别。
 */
internal fun isWebLink(href: String?): Boolean {
    val schemeEnd = href?.indexOf("://") ?: -1
    if (schemeEnd <= 0) return false
    val scheme = href!!.substring(0, schemeEnd).lowercase(Locale.ROOT)
    return scheme == "http" || scheme == "https"
}

/** 打开 http/https 链接（ACTION_VIEW）；非白名单/无接收方记诊断日志后忽略 */
private fun openLink(
    context: Context,
    href: String,
) {
    if (!isWebLink(href)) {
        android.util.Log.d(TAG, "非 http(s) 链接已忽略：$href")
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
    } catch (ignored: ActivityNotFoundException) {
        android.util.Log.d(TAG, "无应用可打开链接：$href")
    }
}

/** 产图键的内容 hash（narrative/panels 结构化段一并入 hash，与 PanelRenderer 内容 hash 互补） */
private fun contentHashOf(
    content: String,
    narrative: List<ContentSegment>,
    panels: List<String>,
): Int {
    var hash = content.hashCode()
    hash = CONTENT_HASH_MULTIPLIER * hash + narrative.hashCode()
    hash = CONTENT_HASH_MULTIPLIER * hash + panels.hashCode()
    return hash
}

/**
 * 图片源解析（受控磁盘读，JVM 可单测）：仅接受主题包 assets 相对路径与 filesDir 内文件；
 * 其余（http(s)/content:// 等任何带 scheme 的 src、含 ".." 的路径）返回 null（引擎按
 * IMAGE_FAILED 占位跳过）并记 debug 日志——引擎图片面被沙箱在包目录内。
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount") // 单文件读的防御性兜底：任何 IO 异常都按"无图"降级
fun resolvePackImageBytes(
    filesRoot: File,
    packId: String,
    src: String,
): ByteArray? {
    val path = src.trim().replace('\\', '/')
    if (path.isEmpty() || path.contains("://") || path.split('/').any { it == ".." }) {
        android.util.Log.d(TAG, "图片 src 已拒绝（scheme/路径穿越）：$src")
        return null
    }
    // 候选 1：主题包 assets 相对路径；候选 2：filesDir 内相对路径。均不含 ".."，必落在 filesRoot 内
    val candidates = listOf(File(filesRoot, "themes/$packId/assets/$path"), File(filesRoot, path))
    for (file in candidates) {
        if (file.isFile) {
            return try {
                file.readBytes()
            } catch (e: Exception) {
                android.util.Log.d(TAG, "图片读取失败：$src（${e.message}）")
                null
            }
        }
    }
    android.util.Log.d(TAG, "图片 src 未命中包内文件：$src")
    return null
}
