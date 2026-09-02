package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.theme.resolved
import com.mistbell.tavern.android.ui.common.rememberBitmap
import com.mistbell.tavern.android.ui.common.rememberFileBitmap
import com.mistbell.tavern.android.ui.theme.*

// —— 常量：集中定义，避免散落的魔法数字 ——

// 判定“贴底”的余量：最后可见项 index >= totalItemsCount - 2 即视为贴底
private const val BOTTOM_ANCHOR_SLACK = 2

// 判定“接近顶部需加载更旧消息”的阈值：第一个可见项 index <= 1
private const val LOAD_OLDER_THRESHOLD_INDEX = 1

// 流式占位 item 的固定 key：保证 streamingText 增量只重组该 item
private const val STREAMING_ITEM_KEY = "streaming"

// 列表顶/底渐隐遮罩高度（覆盖层绘制用）
private val MessageListTopFadeHeight = 72.dp
private val MessageListBottomFadeHeight = 88.dp

// 角色背景大图解码上限（px）：整屏显示 1280 已足够清晰，无需原始分辨率
private const val BACKGROUND_BITMAP_MAX_DIM_PX = 1280

/**
 * 修复2：上滚 prepend 旧消息时的阅读位置锚点。
 * LazyColumn 默认按索引保留位置，头部插入 N 条后正在读的消息会被整体下推；
 * 触发加载前记录 (firstVisibleItemIndex, firstVisibleItemScrollOffset, 旧 messages.size)，
 * 列表增长后按 delta = 新 size - 旧 size 恢复到同一内容位置。
 */
private data class PrependAnchor(
    val firstVisibleIndex: Int,
    val firstVisibleOffset: Int,
    val oldSize: Int,
)

@Composable
private fun buildTextAvatar(character: com.mistbell.tavern.android.data.api.model.Character) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(top = 120.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .size(280.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    character.color?.let {
                                        try {
                                            Color(android.graphics.Color.parseColor(it))
                                                .copy(alpha = 0.08f)
                                        } catch (_: Exception) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                        }
                                    } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.0f),
                                ),
                            radius = 400f,
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = character.name.take(1),
                fontSize = 180.sp,
                fontWeight = FontWeight.Bold,
                color =
                    character.color?.let {
                        try {
                            Color(android.graphics.Color.parseColor(it))
                                .copy(alpha = 0.05f)
                        } catch (_: Exception) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                        }
                    } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    // 注意：streamingText 不在顶层收集——订阅已下放到 StreamingSlot，
    // 流式增量只重组对应的列表 item，不再触发整屏重组
    val hasMoreOlder by viewModel.hasMoreOlder.collectAsState()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsState()
    val sessionId by viewModel.activeSessionId.collectAsState()
    val currentCharacter by viewModel.currentCharacter.collectAsState()
    val participantCharacters by viewModel.participantCharacters.collectAsState()
    val error by viewModel.error.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val activeWorldBookId by viewModel.activeWorldBookId.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val displayCharacters =
        participantCharacters.ifEmpty {
            currentCharacter?.let { listOf(it) } ?: emptyList()
        }
    val primaryDisplayCharacter = displayCharacters.firstOrNull() ?: currentCharacter
    val chatTitle =
        displayCharacters.joinToString("、") { it.name }.ifBlank {
            currentCharacter?.name ?: "AI"
        }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var inputContentHeight by remember { mutableStateOf(64.dp) }
    val messageListTopPadding = if (!isOnline) 56.dp else 24.dp
    val inputBottomPadding = 12.dp
    val messageToInputGap = if (imeBottom > 0) 6.dp else 14.dp
    val messageListBottomInset = inputContentHeight + inputBottomPadding + messageToInputGap
    val bottomAnchorIndex = messages.size + 1 + if (isTyping) 1 else 0
    // 最后一条消息 id 只需算一次：原先在每个 item 内读 messages.lastOrNull()，
    // 相当于每个 item 都订阅整个列表状态
    val lastMessageId = messages.lastOrNull()?.id

    // “贴底”判定：derivedStateOf 只在最后可见项 index 跨过阈值时才触发重组
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            // 无可见项（尚未完成布局）视为贴底，避免首帧被误判为“正在阅读历史”
            visibleItems.isEmpty() ||
                visibleItems.last().index >= layoutInfo.totalItemsCount - BOTTOM_ANCHOR_SLACK
        }
    }

    // 区分首次加载与用户阅读历史：会话切换（sessionId 变化）时重置
    var jumpedToBottom by remember(sessionId) { mutableStateOf(false) }

    // 修复2：上滚 prepend 旧消息的阅读位置锚点（解释见 PrependAnchor 注释）。
    // 只在"即将触发加载"的瞬间被赋值，列表增长后消费并清空
    var pendingAnchor by remember { mutableStateOf<PrependAnchor?>(null) }

    // 修复2：列表增长后按锚点恢复阅读位置。
    // key 必须是 messages.size：prepend 后 size 变化触发本 effect，用 scrollToItem
    // （瞬时、无动画）恢复到 原firstVisibleIndex + delta，像素偏移也一并还原，
    // 视觉上正在读的消息纹丝不动
    LaunchedEffect(messages.size) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        val delta = messages.size - anchor.oldSize
        if (delta > 0) {
            listState.scrollToItem(anchor.firstVisibleIndex + delta, anchor.firstVisibleOffset)
        }
        // delta<=0（加载失败/空结果）同样清空，避免过期锚点被后续加载误用
        pendingAnchor = null
    }

    // 修复1：key 必须含 sessionId —— loadSession 不清空 _messages，两个长会话都是
    // 200 条时 messages.size 不变，仅靠 (size, isTyping) 做 key 时 effect 不重启；
    // jumpedToBottom 虽被 remember(sessionId) 复位，却没有任何 effect 再执行，
    // 导致切会话后列表停留在旧滚动位置。加入 sessionId 后每次切会话必然重新执行，
    // 完成一次"跳到底部"的开场定位
    LaunchedEffect(sessionId, messages.size, isTyping) {
        if (!jumpedToBottom) {
            if (messages.isNotEmpty() || isTyping) {
                // 首次加载（消息从空到非空）：瞬时跳到底部，不做动画，
                // 避免开场动画扫过整段历史消息
                listState.scrollToItem(bottomAnchorIndex)
                jumpedToBottom = true
            }
        } else if (atBottom) {
            // 只有用户仍贴底时才跟随新消息/typing 变化；
            // 用户上翻阅读历史时绝不拉回底部
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(imeBottom, inputContentHeight) {
        if (imeBottom > 0 && (messages.isNotEmpty() || isTyping)) {
            if (!jumpedToBottom || atBottom) {
                // IME 弹出同样非动画跟随，且阅读历史时不拉回
                listState.scrollToItem(bottomAnchorIndex)
            }
        }
    }

    // 顶部加载旧消息：第一个可见项接近列表头时触发；
    // 防并发/防重入/到头返回由 ViewModel 内部实现
    val shouldLoadOlder by remember {
        derivedStateOf {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            firstVisible != null && firstVisible.index <= LOAD_OLDER_THRESHOLD_INDEX
        }
    }
    // 修复3：key 必须含 isLoadingOlder —— 加载结束后 isLoadingOlder true→false 变化
    // 会重启本 effect；否则若 shouldLoadOlder 全程保持 true（修复2索引保留后首项仍在顶部
    // 阈值内），effect 不重启，第 2 页之后分页永远停摆。锚定恢复后 firstVisibleItemIndex
    // 变大 → shouldLoadOlder 变 false，用户再滚回顶部才继续加载，链条自然收敛
    LaunchedEffect(shouldLoadOlder, hasMoreOlder, isLoadingOlder) {
        if (shouldLoadOlder && hasMoreOlder && !isLoadingOlder) {
            // 修复2：触发加载前记录阅读位置锚点（当前首可见项 index/像素偏移 + 旧 size）
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { first ->
                pendingAnchor =
                    PrependAnchor(
                        firstVisibleIndex = first.index,
                        firstVisibleOffset = first.offset,
                        oldSize = messages.size,
                    )
            }
            viewModel.loadOlderMessages()
            // 加载返回后列表未增长（失败/到头/空结果）：锚点不会被 size effect 消费，
            // 在此直接丢弃，避免过期锚点残留
            val anchor = pendingAnchor
            if (anchor != null && messages.size == anchor.oldSize) {
                pendingAnchor = null
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val toast by viewModel.toast.collectAsState()
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    // 已读标记不再由 UI 触发：改由 ChatViewModel 在消息流首次发射后自动执行
    // （仅未读数 > 0 时写库），避免进页面必然多一次写库

    // 主题包状态：tokens / 背景图 / 深色模式（三态判定与 Theme.kt 保持一致，避免 dark 覆盖错配）
    val characterTokens by viewModel.characterTokens.collectAsState()
    val characterBackgroundFile by viewModel.characterBackgroundFile.collectAsState()
    val darkModeSetting by viewModel.darkModeSetting.collectAsState()
    val baseScheme = MaterialTheme.colorScheme
    val isDark =
        when (darkModeSetting) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    // 记忆化：tokens/深色态不变时 resolved() 只计算一次
    val eff = remember(characterTokens, isDark) { characterTokens?.resolved(isDark) }

    // 主角色气泡颜色：解析提入 remember（角色不变则只解析一次）；
    // 主题回退色在组合期读取，避免 remember 块内读 snapshot state 被固化
    val primaryCharacterColor =
        remember(primaryDisplayCharacter?.color) {
            primaryDisplayCharacter?.color?.let { colorString ->
                try {
                    Color(android.graphics.Color.parseColor(colorString))
                } catch (_: Exception) {
                    null
                }
            }
        } ?: MaterialTheme.colorScheme.primary

    // 背景图异步采样解码：复用公共工具 + LRU 缓存，不再在组合线程同步解码大图
    val bgBitmap = rememberFileBitmap(characterBackgroundFile?.absolutePath, BACKGROUND_BITMAP_MAX_DIM_PX)

    // 原有聊天内容整体作为 lambda，按需包裹主题覆盖与背景图
    val chatContent: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background with character avatar image
            // 有主题包背景图时此层必须透明，否则会把背景图盖住
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(if (bgBitmap != null) Color.Transparent else MaterialTheme.colorScheme.background),
            ) {
                // Character avatar as faded background
                primaryDisplayCharacter?.let { character ->
                    if (character.avatarData.isNotBlank()) {
                        // 背景大图异步采样解码（复用公共缓存工具），失败回落文字头像
                        val bitmap = rememberBitmap(character.avatarData, BACKGROUND_BITMAP_MAX_DIM_PX)

                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .alpha(0.5f),
                                // 50%透明度
                                // 裁剪填充整个屏幕
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            // Fallback to text avatar if bitmap parsing fails
                            buildTextAvatar(character)
                        }
                    } else {
                        // Fallback to text avatar if no image
                        buildTextAvatar(character)
                    }
                }
            }

            // Main content
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    Surface(
                        color = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                        Column(modifier = Modifier.statusBarsPadding()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                // Left: Back button
                                IconButton(
                                    onClick = onMenuClick,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        contentDescription = "返回",
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }

                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CompositeCharacterAvatar(
                                        characters = displayCharacters,
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = chatTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }

                                // Right: Settings button
                                IconButton(
                                    onClick = onSettingsClick,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "设置",
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = Color.Transparent,
            ) { paddingValues ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) {
                    // 修复5：离线横幅已移到渐隐遮罩覆盖层之后组合（见下方），
                    // 避免横幅落在遮罩渐变高 alpha 区被"遮花"

                    // Message list area
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (event.changes.any { it.pressed }) {
                                                focusManager.clearFocus()
                                            }
                                        }
                                    }
                                }
                                .imePadding()
                                .navigationBarsPadding()
                                .padding(bottom = messageListBottomInset),
                    ) {
                        // Message list - centered max-width 780dp
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    top = messageListTopPadding,
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            item {
                                Spacer(modifier = Modifier.width(780.dp))
                            }
                            items(messages, key = { it.id }) { message ->
                                val isUser = message.role == "user"
                                val isLastMsg = message.id == lastMessageId
                                // 回调 remember 化：闭包引用稳定，配合稳定参数让 MessageBubble 可跳过重组。
                                // onCopy 额外以 content 为 key：swipe/重新生成后内容变化需重建闭包，避免复制到旧文本
                                val onCopy =
                                    remember(message.id, message.content) {
                                        { viewModel.copyMessage(message.content) }
                                    }
                                val onUndo =
                                    remember(message.id) { { viewModel.undoLastMessage() } }
                                val onBacktrack =
                                    remember(message.id) { { viewModel.backtrackToMessage(message.id) } }
                                val onRegenerate =
                                    remember(message.id) { { viewModel.regenerateMessage(message.id) } }
                                val onContinue =
                                    remember(message.id) { { viewModel.continueMessage() } }
                                val onSwipeLeft =
                                    remember(message.id) { { viewModel.swipeMessage(message.id, "left") } }
                                val onSwipeRight =
                                    remember(message.id) { { viewModel.swipeMessage(message.id, "right") } }
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .widthIn(max = 780.dp)
                                            .padding(horizontal = 24.dp),
                                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
                                ) {
                                    MessageBubble(
                                        message = message,
                                        characterName = primaryDisplayCharacter?.name ?: "AI",
                                        characterColor = primaryCharacterColor,
                                        // 修复6：传应用内三态深浅色，Markdown 颜色随之同步
                                        dark = isDark,
                                        isUser = isUser,
                                        isLastInGroup = true,
                                        isLastMessage = isLastMsg,
                                        onCopy = onCopy,
                                        onUndo = onUndo,
                                        onBacktrack = onBacktrack,
                                        onRegenerate = onRegenerate,
                                        onContinue = onContinue,
                                        onSwipeLeft = onSwipeLeft,
                                        onSwipeRight = onSwipeRight,
                                    )
                                }
                            }

                            if (isTyping) {
                                item(key = STREAMING_ITEM_KEY) {
                                    // 流式文本订阅下放到 StreamingSlot：
                                    // 每个流式增量只重组这一个 item，不整屏重组
                                    StreamingSlot(
                                        viewModel = viewModel,
                                        characterName = primaryDisplayCharacter?.name ?: "AI",
                                        // 修复4：跟随滚动所需的列表状态直接传参
                                        listState = listState,
                                        atBottom = atBottom,
                                        bottomAnchorIndex = bottomAnchorIndex,
                                        // 修复6：流式气泡的 Markdown 同样用应用内三态深浅色
                                        dark = isDark,
                                    )
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(messageToInputGap))
                            }
                        }

                        // 顶/底渐隐遮罩：纯覆盖层绘制，不拦截触摸（Box 无任何指针处理修饰符，
                        // 触摸事件直接穿透到下方列表）。取代原先 LazyColumn 上的
                        // Offscreen 合成 + BlendMode.DstIn 遮罩——离屏合成会让整个列表
                        // 多一次全屏 GPU 合成，滚动帧开销大
                        // 修复5：渐变色用的是不透明 colorScheme.background，有主题背景图
                        // （bgBitmap != null）时会在顶/底画出实色带、把背景图盖死；
                        // 此时有背景图可透出，跳过遮罩绘制，无背景图时保留渐隐效果
                        if (bgBitmap == null) {
                            val fadeColor = MaterialTheme.colorScheme.background
                            Box(
                                modifier =
                                    Modifier
                                        .matchParentSize()
                                        .drawBehind {
                                            // 顶部：页面背景色渐变到透明
                                            drawRect(
                                                brush =
                                                    Brush.verticalGradient(
                                                        colors = listOf(fadeColor, Color.Transparent),
                                                        startY = 0f,
                                                        endY = MessageListTopFadeHeight.toPx(),
                                                    ),
                                            )
                                            // 底部：透明渐变到页面背景色
                                            drawRect(
                                                brush =
                                                    Brush.verticalGradient(
                                                        colors = listOf(Color.Transparent, fadeColor),
                                                        startY = size.height - MessageListBottomFadeHeight.toPx(),
                                                        endY = size.height,
                                                    ),
                                            )
                                        },
                            )
                        }
                    }

                    // 修复5：离线横幅移到渐隐遮罩覆盖层【之后】组合——Box 子级按声明顺序
                    // 绘制，后声明的横幅盖在遮罩之上，不再落在渐变高 alpha 区被遮花
                    if (!isOnline) {
                        Surface(
                            color = AccentRedLight,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "离线模式 — 消息将在联网后同步",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Input area at bottom
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .imePadding()
                                .navigationBarsPadding()
                                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                                Modifier.onGloballyPositioned { coordinates ->
                                    val measuredHeight = with(density) { coordinates.size.height.toDp() }
                                    if (measuredHeight > 0.dp) {
                                        inputContentHeight = measuredHeight
                                    }
                                },
                        ) {
                            MessageInput(
                                onSend = { viewModel.sendMessage(it) },
                                enabled = !isTyping,
                                // 生成中：发送按钮变为"停止生成"按钮
                                isGenerating = isTyping,
                                onStop = { viewModel.stopGeneration() },
                            )
                        }
                    }
                }
            }
        }
    }

    // 应用主题包：tokens 覆盖 scheme + 背景图铺底
    Box(modifier = Modifier.fillMaxSize()) {
        if (bgBitmap != null) {
            Image(
                bitmap = bgBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        val effScheme =
            eff?.let { t ->
                baseScheme.copy(
                    primary = t.primary ?: baseScheme.primary,
                    onPrimary = t.onPrimary ?: baseScheme.onPrimary,
                    background = t.background ?: baseScheme.background,
                    onBackground = t.onBackground ?: baseScheme.onBackground,
                    surface = t.surface ?: baseScheme.surface,
                    onSurface = t.onSurface ?: baseScheme.onSurface,
                    surfaceVariant = t.surfaceVariant ?: baseScheme.surfaceVariant,
                )
            }
        // 恒定包裹（无 tokens 时传 baseScheme）：避免 tokens null↔非null 切换时
        // 组合树结构变化导致内部 remember 状态（如输入框文本）被丢弃
        MaterialTheme(colorScheme = effScheme ?: baseScheme) {
            chatContent()
        }
    }
}

/**
 * 流式占位子组合：内部自行订阅 streamingText，text 非空渲染流式气泡，
 * 否则显示打字指示器。独立子组合的目的：streamingText（已由 ViewModel
 * 做 ~80ms 时间窗节流）的每个增量只重组这一个 item，避免提升到顶层
 * 导致整个 ChatScreen 重组。isTyping 是低频状态，保留在顶层判断。
 */
@Composable
private fun StreamingSlot(
    viewModel: ChatViewModel,
    characterName: String,
    // 修复4：跟随滚动所需状态由调用方传入——本函数仍在列表 item 内组合，
    // 直接持有同一 listState 即可执行滚动，无需新的订阅
    listState: LazyListState,
    atBottom: Boolean,
    bottomAnchorIndex: Int,
    // 修复6：透传应用内三态深浅色给 Markdown 渲染
    dark: Boolean,
) {
    val text by viewModel.streamingText.collectAsState()
    // 修复4：流式增量使气泡持续增高，若用户仍贴底则跟随滚动到底部锚点，
    // 避免长回复的底部滚出屏幕；用户上翻阅读时不打扰。
    // key 为 text：每个流式增量重启本 effect，正好对应一次"增高后跟随"
    LaunchedEffect(text) {
        if (atBottom && !text.isNullOrEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }
    // 拷贝到普通局部变量：委托属性（by collectAsState）无法 smart cast
    when (val currentText = text) {
        null -> {
            // 首个 token 未到达：保持原打字指示器
            TypingIndicator(characterName)
        }
        else -> {
            // 流式输出中：显示累计文本的流式气泡
            StreamingBubble(text = currentText, characterName = characterName, dark = dark)
        }
    }
}

/**
 * 流式回复气泡：渲染累计文本 + 底部"生成中…"小字。
 * 每个增量都会更新 streamingText，remember(text) 让 Markdown 按 Latest 文本重新解析一次。
 */
@Composable
private fun StreamingBubble(
    text: String,
    characterName: String,
    // 修复6：应用内三态深浅色透传给 MarkdownRenderer
    dark: Boolean,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 680.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // 修复6：Markdown 颜色跟随应用内三态深浅色设置
                MarkdownRenderer(content = text, dark = dark)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "生成中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
