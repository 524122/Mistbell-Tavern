package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.ui.components.ConfirmDeleteDialog
import com.mistbell.tavern.android.ui.theme.*

@Composable
private fun buildTextAvatar(character: com.mistbell.tavern.android.data.api.model.Character) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 120.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            character.color?.let {
                                try {
                                    Color(android.graphics.Color.parseColor(it))
                                        .copy(alpha = 0.08f)
                                } catch (_: Exception) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                }
                            } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.0f)
                        ),
                        radius = 400f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = character.name.take(1),
                fontSize = 180.sp,
                fontWeight = FontWeight.Bold,
                color = character.color?.let {
                    try {
                        Color(android.graphics.Color.parseColor(it))
                            .copy(alpha = 0.05f)
                    } catch (_: Exception) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    }
                } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
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
    var showClearChatDialog by remember { mutableStateOf(false) }
    val displayCharacters = participantCharacters.ifEmpty {
        currentCharacter?.let { listOf(it) } ?: emptyList()
    }
    val primaryDisplayCharacter = displayCharacters.firstOrNull() ?: currentCharacter
    val chatTitle = displayCharacters.joinToString("、") { it.name }.ifBlank {
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

    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty() || isTyping) {
            listState.animateScrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(imeBottom, inputContentHeight) {
        if (imeBottom > 0 && (messages.isNotEmpty() || isTyping)) {
            listState.animateScrollToItem(bottomAnchorIndex)
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

    // Mark messages as read when entering chat
    LaunchedEffect(Unit) {
        viewModel.markMessagesAsRead()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background with character avatar image
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Character avatar as faded background
            primaryDisplayCharacter?.let { character ->
                if (character.avatarData.isNotBlank()) {
                    // Use actual image as background
                    val bitmap = remember(character.avatarData) {
                        com.mistbell.tavern.android.util.ImageUtils.dataUriToBitmap(character.avatarData)
                    }

                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.5f),  // 50%透明度
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop  // 裁剪填充整个屏幕
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
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Back button
                            IconButton(
                                onClick = onMenuClick,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.ArrowBack,
                                    contentDescription = "返回",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CompositeCharacterAvatar(
                                    characters = displayCharacters,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = chatTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }

                            // Right: Settings button
                            IconButton(
                                onClick = onSettingsClick,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "设置",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent
        ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Offline banner
            if (!isOnline) {
                Surface(
                    color = AccentRedLight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("离线模式 — 消息将在联网后同步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Message list area
            Box(
                modifier = Modifier
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
                    .padding(bottom = messageListBottomInset)
            ) {
                // Message list - centered max-width 780dp
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            val topMaskHeight = 72.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.White.copy(alpha = 0.00f),
                                        0.35f to Color.White.copy(alpha = 0.55f),
                                        1.00f to Color.White
                                    ),
                                    startY = 0f,
                                    endY = topMaskHeight
                                ),
                                blendMode = BlendMode.DstIn
                            )

                            val bottomMaskHeight = 88.dp.toPx()
                            val bottomMaskStart = (size.height - bottomMaskHeight).coerceAtLeast(0f)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.White,
                                        0.65f to Color.White.copy(alpha = 0.55f),
                                        1.00f to Color.White.copy(alpha = 0.00f)
                                    ),
                                    startY = bottomMaskStart,
                                    endY = size.height
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    contentPadding = PaddingValues(
                        top = messageListTopPadding
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Spacer(modifier = Modifier.width(780.dp))
                    }
                    items(messages) { message ->
                            val isUser = message.role == "user"
                            val isLastMsg = message.id == messages.lastOrNull()?.id
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 780.dp)
                                    .padding(horizontal = 24.dp),
                                contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                MessageBubble(
                                    message = message,
                                    characterName = primaryDisplayCharacter?.name ?: "AI",
                                    characterColor = primaryDisplayCharacter?.color?.let {
                                        try { Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { MaterialTheme.colorScheme.primary }
                                    } ?: MaterialTheme.colorScheme.primary,
                                    isUser = isUser,
                                    isLastInGroup = true,
                                    isLastMessage = isLastMsg,
                                    onCopy = { viewModel.copyMessage(message.content) },
                                    onUndo = { viewModel.undoLastMessage() },
                                    onBacktrack = { viewModel.backtrackToMessage(message.id) },
                                    onRegenerate = { viewModel.regenerateMessage(message.id) },
                                    onContinue = { viewModel.continueMessage() },
                                    onSwipeLeft = { viewModel.swipeMessage(message.id, "left") },
                                    onSwipeRight = { viewModel.swipeMessage(message.id, "right") }
                                )
                            }
                        }

                    if (isTyping) {
                        item {
                            TypingIndicator(primaryDisplayCharacter?.name ?: "AI")
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(messageToInputGap))
                    }
                }
            }

            // Input area at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val measuredHeight = with(density) { coordinates.size.height.toDp() }
                        if (measuredHeight > 0.dp) {
                            inputContentHeight = measuredHeight
                        }
                    }
                ) {
                    MessageInput(
                        onSend = { viewModel.sendMessage(it) },
                        enabled = !isTyping
                    )
                }
            }
        }
    }
    }

    // Clear chat confirmation
    if (showClearChatDialog) {
        ConfirmDeleteDialog(
            title = "清除对话",
            message = "确定要清除当前对话的所有消息吗？此操作不可撤销。",
            confirmText = "清除",
            onConfirm = { viewModel.clearChat(); showClearChatDialog = false },
            onDismiss = { showClearChatDialog = false }
        )
    }
}

@Composable
private fun WelcomeScreen(characterName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon - 135deg gradient
        Box(
            modifier = Modifier
                .size(72.dp)
                .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "💬", fontSize = 32.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "欢迎使用暮铃",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.02).sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "一个智能的AI聊天助手，支持多种角色和模型",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 3-step guide
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                WelcomeStep(
                    number = "1",
                    title = "选择角色",
                    description = "从侧边栏选择一个角色开始对话",
                    modifier = Modifier.weight(1f)
                )
                WelcomeStep(
                    number = "2",
                    title = "配置模型",
                    description = "在设置中配置你的AI模型",
                    modifier = Modifier.weight(1f)
                )
                WelcomeStep(
                    number = "3",
                    title = "开始聊天",
                    description = "输入消息，开始与角色互动",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(
    number: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
