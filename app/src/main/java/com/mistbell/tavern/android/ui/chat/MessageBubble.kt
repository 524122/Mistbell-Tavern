package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.ui.theme.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// 时间格式化器提为常量：原先每条消息格式化时间都 ofPattern 重新编译一次，
// 消息多时开销可观；DateTimeFormatter 线程安全，可全局复用
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// —— 长按动作 pill 布局常量 ——

// pill 与气泡边缘的间距
private val PillGap = 8.dp

// pill 距屏幕（窗口）左右边的最小留白：水平 clamp 用
private val PillScreenMargin = 8.dp

// 单行 pill 的估算高度（图标 20 + 标签一行 + 上下内边距），仅用于「上/下翻转」判定——
// 判定发生在 pill 内容测量之前，拿不到真实高度，按单行估算兜底
private val PillRowEstimatedHeight = 40.dp

// 顶部安全距离：状态栏 + 顶栏 + 余量。气泡顶部（窗口坐标）距窗口顶不足
// 「pill 高 + 间距 + 该距离」时，pill 翻到气泡下方展开
private val PillTopSafeMargin = 120.dp
private val PillAboveMinTopSpace = PillRowEstimatedHeight + PillGap + PillTopSafeMargin

// pill 打开期间压制文本选择浮动工具条的轮询间隔：短于一次长按的剩余时长即可
// 覆盖抬指时刻的重新弹出，同时不至于频繁空转
private const val PILL_TOOLBAR_SUPPRESS_INTERVAL_MS = 200L

// 横滑触发阈值：累计横向位移超过该值即切换一次 swipe（单次手势只触发一次）
private val SwipeFireThreshold = 80.dp

@OptIn(ExperimentalFoundationApi::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun MessageBubble(
    message: Message,
    characterName: String,
    characterColor: Color = AccentBlue,
    // 深浅色由调用方（应用内三态 darkModeSetting 算出的 isDark）透传给 MarkdownRenderer，
    // 避免 Markdown 组件读系统深色导致与应用内主题设置脱节
    dark: Boolean,
    isUser: Boolean,
    isLastInGroup: Boolean,
    isLastMessage: Boolean = false,
    onCopy: () -> Unit = {},
    onUndo: () -> Unit = {},
    onBacktrack: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onContinue: () -> Unit = {},
    // 编辑保存回调（参数为对话框中的新正文）；编辑只针对 content 主文本，
    // thinking 是独立字段，不进编辑框
    onEdit: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    // —— 长按动作 pill（微信式横排图标浮层，Popup 独立窗口绘制）——
    // 开关状态提升到 ChatScreen（actionsBubbleId）：列表滚动即关、任意时刻至多一个气泡
    // 的 pill 打开，需要屏幕级状态协调；「点 pill 外即关」由 Popup 自身的
    // onDismissRequest（focusable=false + FLAG_WATCH_OUTSIDE_TOUCH）处理，无需屏幕点击层
    actionsVisible: Boolean = false,
    onActionsOpen: () -> Unit = {},
    onActionsDismiss: () -> Unit = {},
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    // HTML 界面：桥宿主 + 卡片脚本 + 全局开关（三者齐备且消息含 HTML 才渲染 WebView）
    htmlHost: com.mistbell.tavern.android.ui.chat.htmlui.HtmlBridgeHost? = null,
    htmlScripts: List<com.mistbell.tavern.android.ui.chat.htmlui.HtmlScript> = emptyList(),
    htmlUiEnabled: Boolean = false,
    // 生效正则管线（全局+卡级）按角色与楼层深度处理展示内容，不改存储
    regexScripts: List<com.mistbell.tavern.android.data.regex.CardRegexEngine.CardRegexScript> = emptyList(),
    regexDepth: Int? = null,
    /** MVU 变量版本（>0 且变化时 HtmlBubble 向页面推送变量更新） */
    variablesVersion: Long = 0,
    /** 当前会话聊天事件的有界队列（HtmlBubble 按 version 游标补推酒馆助手事件） */
    chatEvents: List<com.mistbell.tavern.android.ui.chat.htmlui.HtmlFacadeContract.HtmlChatEvent> = emptyList(),
    /** HTML 界面展开状态（宿主按会话持久化；默认折叠） */
    htmlExpanded: Boolean = false,
    onHtmlExpandedChange: (Boolean) -> Unit = {},
) {
    // 编辑对话框开关；文本状态以 (对话框打开, 消息内容) 为 key 初始化——每次打开都预填
    // 当前存储正文（非正则处理后的展示文本），关闭再开不会残留上次未保存的输入
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember(showEditDialog, message.content) { mutableStateOf(message.content) }
    // 有思维链的消息进入聊天页时直接显示正文；仍可点击标题收起，避免用户误以为思维链没有生成。
    var thinkingExpanded by remember { mutableStateOf(true) }
    // 长按 pill 与系统浮动文本工具条（FloatingActionMode，独立窗口）互斥：
    // 打开 pill 前先收起可能残留的工具条，防止其窗口叠在浮层之上吞掉动作项点击
    val textToolbar = LocalTextToolbar.current
    // 仅在长按触发时刻 hide 不够：手指未抬起时选择手势仍在进行，抬指瞬间
    // （SelectionManager onStop → showToolbar）会把工具条重新弹出。pill 存续期间
    // 周期性压制（hide 对未激活的 ActionMode 是无成本空操作），保证任何时点不出现叠窗
    LaunchedEffect(actionsVisible) {
        if (actionsVisible) {
            while (true) {
                textToolbar.hide()
                kotlinx.coroutines.delay(PILL_TOOLBAR_SUPPRESS_INTERVAL_MS)
            }
        }
    }

    // 兼容早期版本：部分消息把 <think>...</think> 保存在 content，而没有单独写入 thinking 列。
    // 先从正文拆出再渲染，避免 Markdown 处理 HTML 标签后把思维链完全吞掉。
    val (inlineContent, inlineThinking) =
        remember(message.content, message.thinking) {
            splitThinkingForDisplay(message.content)
        }
    // 显示时正则：按消息角色与楼层深度替换展示内容，存储原文不动
    val rawContent = if (inlineThinking != null) inlineContent else message.content
    val displayedContent =
        remember(rawContent, regexScripts, message.role, regexDepth) {
            com.mistbell.tavern.android.data.regex.CardRegexEngine.applyToDisplay(
                rawContent,
                message.role,
                regexScripts,
                regexDepth,
            )
        }
    val displayedThinking = message.thinking?.takeIf { it.isNotBlank() } ?: inlineThinking

    // HTML 界面：消息内 ```html 块 / 裸 HTML 经检测拆分；纯 HTML 消息（如苍玄界开场界面）
    // 去气泡外壳全宽渲染，与聊天背景融合；混排（正文+HTML）保留气泡壳
    val htmlParts =
        remember(displayedContent) {
            com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector.split(displayedContent)
        }
    val renderHtml =
        htmlUiEnabled && htmlHost != null &&
            htmlParts.any {
                it is com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector.Part.HtmlDoc
            }
    val hasNarrativeHtml =
        htmlParts.any { it is com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector.Part.HtmlDoc && it.narrative }
    val htmlFullBleed =
        renderHtml && !hasNarrativeHtml &&
            htmlParts.all { part ->
                part is com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector.Part.HtmlDoc ||
                    (
                        (part as? com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector.Part.Markdown)
                            ?.text
                            ?.isBlank() == true
                    )
            }

    val bubbleShape = MaterialTheme.shapes.medium
    val cardBackgroundColor =
        if (isUser) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        }
    val cardBorderColor =
        if (isUser) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    val cardTextColor =
        if (isUser) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val cardShadowColor =
        if (isUser) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Black.copy(alpha = 0.04f)
        }

    // —— 多 swipe 门控：溢出层与横滑手势共用 ——
    // 多 swipe 的 AI 消息：溢出层保留上一个/下一个入口（HTML 气泡等手势不可用时的
    // 兜底入口）；横滑手势只作用于 Markdown 正文气泡——HTML 全出血气泡整屏交给
    // WebView 自身手势，不参与横滑切换
    val hasMultiSwipes = !isUser && message.swipes != null && message.swipes.size > 1
    val canHorizontalSwipe = hasMultiSwipes && !htmlFullBleed

    // 气泡顶部在窗口坐标系中的 y（供 pill 上/下翻转判定；NaN=尚未完成首次布局）。
    // positionInRoot 的点变换不做视口裁剪，与 Popup 锚点坐标同源；pill 的具体摆放由
    // BubblePillPositionProvider 用锚点（气泡 Box）的窗口坐标计算——HTML 气泡异步
    // reportHeight 拉高气泡后，锚点布局更新，Popup 每次布局重算位置自动跟随。
    var bubbleTopInRootPx by remember { mutableFloatStateOf(Float.NaN) }
    // 翻转判定：气泡顶部距窗口顶不足「pill 高 + 间距 + 顶栏安全距离」时翻到气泡下方
    // 展开。判定与 pill 内容的展开方向共用同一布尔，保证定位与内容一致。未完成首次
    // 布局时 NaN 与任何值比较均为 false，按「下方」兜底。
    val density = LocalDensity.current
    val placeAbove = bubbleTopInRootPx >= with(density) { PillAboveMinTopSpace.toPx() }

    // —— pill 动作集：语义与条件与旧 DropdownMenu 完全一致 ——
    // 显示顺序：复制 · 编辑 · [重新生成] · [继续|撤销] · 删除 · [···]
    fun pillAction(
        icon: ImageVector,
        label: String,
        action: () -> Unit,
    ) = BubbleAction(icon, label) {
        // 任一动作执行后关闭 pill
        onActionsDismiss()
        action()
    }

    val pillActions =
        buildList {
            add(pillAction(Icons.Default.ContentCopy, "复制", onCopy))
            add(pillAction(Icons.Default.Edit, "编辑") { showEditDialog = true })
            if (!isUser) {
                add(pillAction(Icons.Default.Refresh, "重新生成", onRegenerate))
            }
            if (isLastMessage) {
                if (!isUser) {
                    add(pillAction(Icons.Default.ArrowForward, "继续", onContinue))
                } else {
                    add(pillAction(Icons.Default.Undo, "撤回", onUndo))
                }
            }
            add(pillAction(Icons.Default.Delete, "删除", onDelete))
        }
    // 低频项收进「···」二级浮层；用户消息没有低频项，pill 不显示「···」入口
    val pillOverflowActions =
        buildList {
            if (!isUser && !isLastMessage) {
                add(pillAction(Icons.Default.Replay, "回溯到这里", onBacktrack))
            }
            if (hasMultiSwipes) {
                add(pillAction(Icons.Default.ChevronLeft, "上一个", onSwipeLeft))
                add(pillAction(Icons.Default.ChevronRight, "下一个", onSwipeRight))
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLastInGroup) 20.dp else 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Sender label
        if (isLastInGroup) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.padding(
                        bottom = 4.dp,
                        start = if (isUser) 0.dp else 16.dp,
                        end = if (isUser) 16.dp else 0.dp,
                    ),
            ) {
                if (!isUser) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AccentGreen),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (isUser) "你" else characterName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 思考过程显示在对应回复上方，与消息保持同一侧对齐，但仍作为独立区域。
        if (!displayedThinking.isNullOrBlank()) {
            thinkingSection(
                thinking = displayedThinking.orEmpty(),
                expanded = thinkingExpanded,
                onExpandedChange = { thinkingExpanded = it },
                modifier =
                    Modifier.padding(
                        bottom = 4.dp,
                        start = if (isUser) 0.dp else 16.dp,
                        end = if (isUser) 16.dp else 0.dp,
                    ),
            )
        }

        // Message row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (htmlFullBleed) 6.dp else 16.dp),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            // No avatar - it's now in the background

            // Bubble with long-press actions pill + horizontal swipe gesture
            Box {
                Box(
                    modifier =
                        Modifier
                            .then(
                                if (htmlFullBleed) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier.widthIn(max = if (isUser) 320.dp else 620.dp)
                                },
                            )
                            .onGloballyPositioned { coordinates ->
                                // 气泡顶部（窗口坐标，点变换不做视口裁剪）供上/下翻转判定；
                                // pill 的精确摆放由 Popup 锚点坐标在布局期计算（见上方注释）
                                bubbleTopInRootPx = coordinates.positionInRoot().y
                            }
                            .pointerInput(canHorizontalSwipe) {
                                // 气泡横滑切换 swipe：横向拖过阈值即触发一次。横竖主轴
                                // 竞争时先过 touch slop 者赢，纵向滚动不受影响；本手势
                                // 消耗位移后，同一次按下的长按/点击自动取消
                                if (!canHorizontalSwipe) return@pointerInput
                                var totalDragPx = 0f
                                var fired = false
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        totalDragPx = 0f
                                        fired = false
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        if (fired) return@detectHorizontalDragGestures
                                        totalDragPx += dragAmount
                                        if (abs(totalDragPx) >= SwipeFireThreshold.toPx()) {
                                            fired = true
                                            // 方向语义与 repo.swipeMessage("left"/"right")
                                            // 一致：左滑=left=上一个，右滑=right=下一个
                                            if (totalDragPx < 0) onSwipeLeft() else onSwipeRight()
                                            // 横滑执行动作即收起已打开的 pill
                                            onActionsDismiss()
                                        }
                                    },
                                )
                            }
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    textToolbar.hide()
                                    onActionsOpen()
                                },
                            )
                            .then(
                                if (htmlFullBleed) {
                                    Modifier
                                } else {
                                    Modifier
                                        .shadow(0.dp, bubbleShape, ambientColor = cardShadowColor)
                                        .background(cardBackgroundColor, bubbleShape)
                                        .border(1.dp, cardBorderColor, bubbleShape)
                                },
                            )
                            .padding(
                                horizontal = if (htmlFullBleed) 0.dp else 18.dp,
                                vertical = if (htmlFullBleed) 0.dp else 13.dp,
                            ),
                ) {
                    if (renderHtml) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            htmlParts.forEachIndexed { partIndex, part ->
                                when (part) {
                                    is com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector.Part.Markdown ->
                                        MessageMarkdown(
                                            displayedContent = part.text,
                                            dark = dark,
                                            isUser = isUser,
                                            cardTextColor = cardTextColor,
                                            // 与非 HTML 分支同语义：pill 打开期间卸载选择容器，
                                            // 长按选择的手柄/工具条不得与浮层并存
                                            selectable = !actionsVisible,
                                        )
                                    is com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector.Part.HtmlDoc ->
                                        key(message.id, partIndex, part.html) {
                                            com.mistbell.tavern.android.ui.chat.htmlui.HtmlBubble(
                                                messageId = message.id,
                                                html = part.html,
                                                scripts = htmlScripts,
                                                host = htmlHost!!,
                                                expanded = htmlExpanded,
                                                onExpandedChange = onHtmlExpandedChange,
                                                variablesVersion = variablesVersion,
                                                chatEvents = chatEvents,
                                                narrative = part.narrative,
                                            )
                                        }
                                }
                            }
                        }
                    } else {
                        MessageMarkdown(
                            displayedContent = displayedContent,
                            dark = dark,
                            isUser = isUser,
                            cardTextColor = cardTextColor,
                            // pill 打开期间禁用气泡内文本选择：长按开 pill 的同一手势也会在
                            // SelectionContainer 里启动"长按选择"，抬指后系统浮动文本工具条
                            // （独立弹窗）会盖住浮层动作项，吞掉其点击。pill 打开即整体卸载
                            // 选择容器，句柄/工具条不再出现。
                            selectable = !actionsVisible,
                        )
                    }
                }

                // 长按动作 pill：Popup 独立窗口绘制（非组合内绘制）。组合内绘制的 pill 是
                // 列表 item 的后代节点，pill 溢出气泡的部分会被 LazyColumn 视口裁剪——
                // 绘制被裁、命中测试同样被裁，点 pill 项既不触发动作也不关闭。独立窗口
                // 从根上规避裁剪与遮挡；「点 pill 外即关」由 Popup 的 onDismissRequest
                // 处理，滚动即关仍由 ChatScreen 统一协调
                if (actionsVisible) {
                    BubbleActionsPopup(
                        isUser = isUser,
                        placeAbove = placeAbove,
                        actions = pillActions,
                        overflowActions = pillOverflowActions,
                        onDismiss = onActionsDismiss,
                    )
                }

                // 编辑消息对话框：多行输入框预填存储正文；保存经 onEdit(newContent) 走
                // ChatViewModel.editMessage → ChatRepository.editMessage 落库（仅改 content）
                if (showEditDialog) {
                    AlertDialog(
                        onDismissRequest = { showEditDialog = false },
                        title = { Text("编辑消息") },
                        text = {
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { editText = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 10,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showEditDialog = false
                                    onEdit(editText)
                                },
                                enabled = editText.isNotBlank(),
                            ) {
                                Text("保存")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEditDialog = false }) {
                                Text("取消")
                            }
                        },
                    )
                }
            }

            // Swipe indicator and controls for AI messages with multiple swipes
            if (!isUser && message.swipes != null && message.swipes.size > 1) {
                Row(
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .widthIn(max = 520.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onSwipeLeft,
                        modifier = Modifier.size(24.dp),
                        enabled = message.swipeIndex > 0,
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = "上一个回复",
                            modifier = Modifier.size(18.dp),
                            tint =
                                if (message.swipeIndex > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                        )
                    }

                    Text(
                        text = "${message.swipeIndex + 1}/${message.swipes.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    IconButton(
                        onClick = onSwipeRight,
                        modifier = Modifier.size(24.dp),
                        enabled = message.swipeIndex < message.swipes.size - 1,
                    ) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "下一个回复",
                            modifier = Modifier.size(18.dp),
                            tint =
                                if (message.swipeIndex < message.swipes.size - 1) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                        )
                    }
                }
            }

            // 头像已完全移除
        }

        // Timestamp
        if (isLastInGroup && message.createdAt.isNotBlank()) {
            // 记忆化：同一消息的时间戳只格式化一次，重组时直接复用结果
            val timestamp = remember(message.createdAt) { formatTime(message.createdAt) }
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier =
                    Modifier.padding(
                        top = 4.dp,
                        start = if (isUser) 0.dp else 16.dp,
                        end = if (isUser) 16.dp else 0.dp,
                    ),
            )
        }
    }
}

/** pill 单个动作：图标 + 短标签（null 表示仅图标，如「···」）+ 点击行为 */
internal class BubbleAction(
    val icon: ImageVector,
    val label: String?,
    val onClick: () -> Unit,
)

/**
 * 长按动作 pill 的窗口外壳：Popup 独立窗口（focusable=false），内容为 [BubbleActionsContent]。
 *
 * - 锚点即气泡：Popup 组合在气泡 Box 内，PopupPositionProvider 拿到的 anchorBounds 就是
 *   气泡的窗口坐标——气泡高度异步变化（WebView reportHeight）时锚点布局随之更新，位置
 *   每次布局重算，pill 自动跟随。
 * - focusable=false：不抢输入焦点，外部点击走 ACTION_OUTSIDE 触发 onDismissRequest 且
 *   事件不消费、照常落到下层（点外即关且不影响下层交互）；副作用是返回键不关闭 pill
 *   （dismissOnBackPress 需要 focusable），关闭路径为点外/滚动/动作执行/横滑。
 * - 无进出场动画：Popup 窗口随开关瞬时显隐，组合内 fade 动画在窗口销毁时来不及播放。
 * 开关状态在 ChatScreen：actionsVisible=false 时 Popup 离开组合、窗口销毁，「···」展开
 * 等内部状态一并重置，下次打开从收起态开始。
 */
@Composable
@Suppress("LongParameterList")
private fun BubbleActionsPopup(
    isUser: Boolean,
    placeAbove: Boolean,
    actions: List<BubbleAction>,
    overflowActions: List<BubbleAction>,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider =
        remember(isUser, placeAbove) {
            BubblePillPositionProvider(isUser = isUser, placeAbove = placeAbove, density = density)
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        BubbleActionsContent(
            isUser = isUser,
            placeAbove = placeAbove,
            actions = actions,
            overflowActions = overflowActions,
        )
    }
}

/**
 * pill 的 Popup 定位器：垂直方向按 placeAbove 把 pill 放到气泡上方/下方（间距 PillGap），
 * 真实内容高度（含「···」二级浮层展开）由 popupContentSize 传入，内容尺寸变化时 Compose
 * 会以新尺寸重算本方法，展开浮层不把主 pill 推离气泡；水平方向按 isUser 对齐气泡对应侧
 * 并 clamp 在窗口内。
 */
private class BubblePillPositionProvider(
    private val isUser: Boolean,
    private val placeAbove: Boolean,
    private val density: Density,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gapPx = with(density) { PillGap.roundToPx() }
        val y =
            if (placeAbove) {
                // pill 底边钉在气泡顶上方间距处：上方展开浮层增高时整体上移，主 pill 不动
                anchorBounds.top - popupContentSize.height - gapPx
            } else {
                anchorBounds.bottom + gapPx
            }
        val x = if (isUser) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val marginPx = with(density) { PillScreenMargin.roundToPx() }
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        return IntOffset(x.coerceIn(marginPx, maxX), y)
    }
}

/**
 * 长按动作 pill 的内容：横排动作 pill + 「···」二级浮层。开关等状态放在本层：pill 收起后
 * Popup 内容随之销毁，下次长按打开时全部重置。
 */
@Composable
@Suppress("LongParameterList")
private fun BubbleActionsContent(
    isUser: Boolean,
    placeAbove: Boolean,
    actions: List<BubbleAction>,
    overflowActions: List<BubbleAction>,
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        // 「···」仅当有低频项时显示（用户消息没有，不显示空入口）
        val moreAction =
            if (overflowActions.isEmpty()) {
                null
            } else {
                BubbleAction(Icons.Default.MoreHoriz, "更多") { overflowExpanded = !overflowExpanded }
            }
        if (placeAbove) {
            BubbleOverflowPanel(
                expanded = overflowExpanded,
                expandUpwards = true,
                actions = overflowActions,
            )
            BubblePillRow(actions = if (moreAction == null) actions else actions + moreAction)
        } else {
            BubblePillRow(actions = if (moreAction == null) actions else actions + moreAction)
            BubbleOverflowPanel(
                expanded = overflowExpanded,
                expandUpwards = false,
                actions = overflowActions,
            )
        }
    }
}

/** 横排动作 pill 主体：全圆 Surface + 横排「图标在上、短标签在下」的动作项，细线分隔 */
@Composable
private fun BubblePillRow(actions: List<BubbleAction>) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    // 动作间细线分隔：与气泡描边同色，低存在感
                    Box(
                        modifier =
                            Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                BubblePillActionButton(action)
            }
        }
    }
}

/** pill 上的单个动作：图标在上、短标签在下，最小 40dp 触达 */
@Composable
private fun BubblePillActionButton(action: BubbleAction) {
    Box(
        modifier =
            Modifier
                .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                .clip(CircleShape)
                .clickable(onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            if (action.label != null) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 「···」二级浮层：低频动作竖排（回溯到这里 / 上一个 / 下一个）。展开方向跟随
 * pill 位置——始终向远离气泡一侧展开，不遮挡正文。
 */
@Composable
private fun BubbleOverflowPanel(
    expanded: Boolean,
    expandUpwards: Boolean,
    actions: List<BubbleAction>,
) {
    AnimatedVisibility(
        visible = expanded,
        enter =
            fadeIn(animationSpec = tween(durationMillis = 100)) +
                expandVertically(expandFrom = if (expandUpwards) Alignment.Bottom else Alignment.Top),
        exit =
            fadeOut(animationSpec = tween(durationMillis = 100)) +
                shrinkVertically(shrinkTowards = if (expandUpwards) Alignment.Bottom else Alignment.Top),
    ) {
        Column {
            // 与主 pill 之间留一道小间距
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 3.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    actions.forEach { action ->
                        Row(
                            modifier =
                                Modifier
                                    .widthIn(min = 136.dp)
                                    .clickable(onClick = action.onClick)
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = action.label.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 可复用的思维链折叠区，正式消息与流式占位消息保持同一交互。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun thinkingSection(
    thinking: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { onExpandedChange(!expanded) },
                    onLongClick = {},
                )
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "💭 思考过程",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            SelectionContainer {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 允许流式中的未闭合 <think> 标签实时显示，而不是等回复结束才出现。 */
internal fun splitThinkingForDisplay(reply: String): Pair<String, String?> {
    val open = reply.indexOf("<think>", ignoreCase = true)
    if (open < 0) return reply to null
    val contentStart = open + "<think>".length
    val close = reply.indexOf("</think>", contentStart, ignoreCase = true)
    return if (close >= 0) {
        val thinking = reply.substring(contentStart, close).trim()
        val content = (reply.removeRange(open, close + "</think>".length)).trim()
        content to thinking.ifBlank { null }
    } else {
        "" to reply.substring(contentStart).trim().ifBlank { null }
    }
}

private fun formatTime(isoString: String): String {
    return try {
        val instant = java.time.Instant.parse(isoString)
        val zonedDateTime = instant.atZone(java.time.ZoneId.systemDefault())
        zonedDateTime.format(TIME_FORMATTER)
    } catch (e: Exception) {
        ""
    }
}

/** 消息正文的 Markdown 渲染（用户/AI 两个分支的既有差异收敛一处） */
@Suppress("FunctionNaming")
@Composable
private fun MessageMarkdown(
    displayedContent: String,
    dark: Boolean,
    isUser: Boolean,
    cardTextColor: Color,
    // 菜单打开期间传 false：卸载 SelectionContainer，避免长按选择与菜单争抢同一手势
    selectable: Boolean = true,
) {
    if (isUser) {
        // 用户消息也使用 MarkdownRenderer 以支持语义高亮（引号、括号变色）
        MarkdownRenderer(
            content = displayedContent,
            dark = dark,
            highlightColor = MaterialTheme.colorScheme.onPrimary,
            textColor = cardTextColor,
            modifier = Modifier.fillMaxWidth(),
            selectable = selectable,
        )
    } else {
        MarkdownRenderer(
            content = displayedContent,
            // 透传应用内三态深浅色，而非组件内部读系统设置
            dark = dark,
            highlightColor = MaterialTheme.colorScheme.tertiary,
            textColor = cardTextColor,
            modifier = Modifier.fillMaxWidth(),
            selectable = selectable,
        )
    }
}
