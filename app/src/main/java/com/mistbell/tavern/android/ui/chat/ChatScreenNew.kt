package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenNew(
    viewModel: ChatViewModel,
    onBack: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
) {
    val messages by viewModel.messages.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val currentCharacter by viewModel.currentCharacter.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val listState = rememberLazyListState()
    var messageInput by remember { mutableStateOf("") }

    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.background

    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .clearFocusOnTap(),
        containerColor = backgroundColor,
        topBar = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(surfaceColor),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Back button
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Character avatar with online indicator
                    Box {
                        Box(
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(parseCharColor(currentCharacter?.color)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = getCharacterEmoji(currentCharacter?.name ?: ""),
                                fontSize = 24.sp,
                            )
                        }

                        if (isOnline) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(10.dp)
                                        .align(Alignment.BottomEnd)
                                        .clip(CircleShape)
                                        .background(Color(0xFF34C759)),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Character name and status
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = currentCharacter?.name ?: "暮铃",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isOnline) "在线" else "离线",
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E93),
                            )
                            if (currentCharacter?.role?.isNotBlank() == true) {
                                Text(
                                    text = " · ${currentCharacter?.role}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF8E8E93),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Settings button
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = Color(0xFF8E8E93),
                        )
                    }
                }
            }
        },
        bottomBar = {
            ChatInputBar(
                message = messageInput,
                onMessageChange = { messageInput = it },
                onSend = {
                    if (messageInput.isNotBlank()) {
                        viewModel.sendMessage(messageInput)
                        messageInput = ""
                    }
                },
                enabled = !isTyping,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // Group messages by date and add date headers
            val groupedMessages = messages.groupBy { getDateGroup(it.createdAt) }

            groupedMessages.forEach { (date, messagesInGroup) ->
                item {
                    // Date header
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = date,
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93),
                            modifier =
                                Modifier
                                    .background(
                                        color = Color(0xFFE5E5EA),
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }

                items(messagesInGroup) { message ->
                    MessageBubbleNew(
                        message = message,
                        characterName = currentCharacter?.name ?: "",
                        characterColor = currentCharacter?.color ?: "",
                        onRegenerate = { viewModel.regenerateMessage(message.id) },
                        onDelete = { /* Delete message */ },
                    )
                }
            }

            if (isTyping) {
                item {
                    TypingIndicatorNew(
                        characterColor = currentCharacter?.color ?: "",
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubbleNew(
    message: Message,
    characterName: String,
    characterColor: String,
    onRegenerate: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            // AI avatar
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(parseCharColor(characterColor)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = getCharacterEmoji(characterName),
                    fontSize = 20.sp,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            // Message bubble
            Box(
                modifier =
                    Modifier
                        .background(
                            color = if (isUser) Color(0xFF007AFF) else MaterialTheme.colorScheme.surface,
                            shape =
                                RoundedCornerShape(
                                    topStart = if (isUser) 18.dp else 4.dp,
                                    topEnd = if (isUser) 4.dp else 18.dp,
                                    bottomStart = 18.dp,
                                    bottomEnd = 18.dp,
                                ),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.content,
                    fontSize = 15.sp,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Time and status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Text(
                    text = formatMessageTime(message.createdAt),
                    fontSize = 11.sp,
                    color = Color(0xFF8E8E93),
                )
                if (isUser) {
                    Text(
                        text = " · 已发送",
                        fontSize = 11.sp,
                        color = Color(0xFF8E8E93),
                    )
                }
            }

            // Action buttons for AI messages
            if (!isUser) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MessageActionButton(
                        icon = Icons.Default.Refresh,
                        onClick = onRegenerate,
                    )
                    MessageActionButton(
                        icon = Icons.Default.Edit,
                        onClick = { /* Edit */ },
                    )
                    MessageActionButton(
                        icon = Icons.Default.ContentCopy,
                        onClick = { /* Copy */ },
                    )
                    MessageActionButton(
                        icon = Icons.Default.Delete,
                        onClick = onDelete,
                    )
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))

            // User indicator
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF007AFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "我",
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun MessageActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFC7C7CC),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
fun ChatInputBar(
    message: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
    ) {
        // Suggestion chips
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SuggestionChip(text = "换个更暖味一点的语气")
            SuggestionChip(text = "继续推进剧情")
            SuggestionChip(text = "查看角色设定")
        }

        // Input row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Add button
            IconButton(
                onClick = { /* Add attachment */ },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加",
                    tint = Color(0xFF8E8E93),
                )
            }

            // Input field
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp, max = 120.dp),
                placeholder = {
                    Text(
                        text = "输入消息...",
                        fontSize = 15.sp,
                        color = Color(0xFFC7C7CC),
                    )
                },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                shape = RoundedCornerShape(20.dp),
                enabled = enabled,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button
            FloatingActionButton(
                onClick = onSend,
                modifier = Modifier.size(36.dp),
                containerColor = Color(0xFF007AFF),
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun RowScope.SuggestionChip(text: String) {
    Button(
        onClick = { /* Handle suggestion */ },
        modifier =
            Modifier
                .weight(1f)
                .height(32.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TypingIndicatorNew(characterColor: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(parseCharColor(characterColor)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "...", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = "正在输入...",
                fontSize = 15.sp,
                color = Color(0xFF8E8E93),
            )
        }
    }
}

private fun parseCharColor(colorString: String?): Color {
    return try {
        Color(android.graphics.Color.parseColor(colorString ?: "#FF6B9D"))
    } catch (e: Exception) {
        Color(0xFFFF6B9D)
    }
}

private fun getCharacterEmoji(name: String): String {
    val emojiMap =
        mapOf(
            "米拉" to "❄️",
            "个男人的女性修仙界" to "🌍",
            "Seraphina" to "👩",
            "酒馆旧梦" to "🍷",
        )
    return emojiMap[name] ?: "✨"
}

private fun getDateGroup(timestamp: String): String {
    return try {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = dateFormat.parse(timestamp) ?: return "今天"

        val now = Calendar.getInstance()
        val messageTime = Calendar.getInstance().apply { time = date }

        when {
            now.get(Calendar.DAY_OF_YEAR) == messageTime.get(Calendar.DAY_OF_YEAR) &&
                now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) ->
                "今天 · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)}"
            else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(date)
        }
    } catch (e: Exception) {
        "今天"
    }
}

private fun formatMessageTime(timestamp: String): String {
    return try {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = dateFormat.parse(timestamp) ?: return ""
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    } catch (e: Exception) {
        ""
    }
}
