package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.ui.theme.*
import java.time.format.DateTimeFormatter

// 时间格式化器提为常量：原先每条消息格式化时间都 ofPattern 重新编译一次，
// 消息多时开销可观；DateTimeFormatter 线程安全，可全局复用
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalFoundationApi::class)
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
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    var thinkingExpanded by remember { mutableStateOf(false) }

    val bubbleShape = RoundedCornerShape(16.dp)
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

        // Message row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            // No avatar - it's now in the background

            // Bubble with long-press menu
            Box {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = if (isUser) 280.dp else 520.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showMenu = true },
                            )
                            .then(
                                Modifier
                                    .shadow(1.dp, bubbleShape, ambientColor = cardShadowColor)
                                    .background(cardBackgroundColor, bubbleShape)
                                    .border(1.dp, cardBorderColor, bubbleShape),
                            )
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    if (isUser) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = cardTextColor,
                            lineHeight = 22.sp,
                        )
                    } else {
                        MarkdownRenderer(
                            content = message.content,
                            // 透传应用内三态深浅色，而非组件内部读系统设置
                            dark = dark,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Long-press context menu
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            showMenu = false
                            onCopy()
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                    )
                    if (!isUser && !isLastMessage) {
                        DropdownMenuItem(
                            text = { Text("回退到这里") },
                            onClick = {
                                showMenu = false
                                onBacktrack()
                            },
                            leadingIcon = { Icon(Icons.Default.Replay, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (!isUser) {
                        DropdownMenuItem(
                            text = { Text("重新生成") },
                            onClick = {
                                showMenu = false
                                onRegenerate()
                            },
                            leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (isLastMessage && !isUser) {
                        DropdownMenuItem(
                            text = { Text("继续") },
                            onClick = {
                                showMenu = false
                                onContinue()
                            },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (isLastMessage && isUser) {
                        DropdownMenuItem(
                            text = { Text("撤销") },
                            onClick = {
                                showMenu = false
                                onUndo()
                            },
                            leadingIcon = { Icon(Icons.Default.Undo, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    // Swipe controls for AI messages with multiple swipes
                    if (!isUser && message.swipes != null && message.swipes.size > 1) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("← 上一个 (${message.swipeIndex}/${message.swipes.size - 1})") },
                            onClick = {
                                showMenu = false
                                onSwipeLeft()
                            },
                            leadingIcon = { Icon(Icons.Default.ChevronLeft, null, modifier = Modifier.size(18.dp)) },
                        )
                        DropdownMenuItem(
                            text = { Text("下一个 →") },
                            onClick = {
                                showMenu = false
                                onSwipeRight()
                            },
                            leadingIcon = { Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
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

        // Thinking section (collapsible)
        if (!message.thinking.isNullOrBlank()) {
            Column(
                modifier =
                    Modifier
                        .padding(
                            top = 4.dp,
                            start = if (isUser) 0.dp else 16.dp,
                            end = if (isUser) 16.dp else 0.dp,
                        )
                        .widthIn(max = 520.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { thinkingExpanded = !thinkingExpanded },
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
                        if (thinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = thinkingExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Text(
                        text = message.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
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

private fun formatTime(isoString: String): String {
    return try {
        val instant = java.time.Instant.parse(isoString)
        val zonedDateTime = instant.atZone(java.time.ZoneId.systemDefault())
        zonedDateTime.format(TIME_FORMATTER)
    } catch (e: Exception) {
        ""
    }
}
