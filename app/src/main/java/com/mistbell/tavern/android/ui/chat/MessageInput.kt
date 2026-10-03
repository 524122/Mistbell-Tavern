package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.ui.theme.*

@Composable
fun MessageInput(
    onSend: (String) -> Unit,
    enabled: Boolean = true,
    isGenerating: Boolean = false,
    onStop: (() -> Unit)? = null,
    participants: List<Character> = emptyList(),
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val isEnabled = textFieldValue.text.isNotBlank() && enabled
    val showStop = isGenerating && onStop != null

    // @ 提及检测
    val mentionQuery =
        remember(textFieldValue.text, textFieldValue.selection.start) {
            detectMentionInput(textFieldValue.text, textFieldValue.selection.start)
        }
    val suggestions =
        remember(mentionQuery) {
            if (mentionQuery != null && participants.isNotEmpty()) {
                filterCharacters(participants, mentionQuery)
            } else {
                emptyList()
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // @ 提及自动补全弹窗
        AnimatedVisibility(
            visible = suggestions.isNotEmpty() && isFocused,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            MentionSuggestionPopup(
                suggestions = suggestions,
                onSuggestionClick = { character ->
                    val (newText, newCursor) =
                        replaceMention(
                            textFieldValue.text,
                            textFieldValue.selection.start,
                            character.name,
                        )
                    textFieldValue =
                        TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor),
                        )
                },
                onDismiss = { },
            )
        }

        // 快捷字符工具栏独立放在输入框上沿，避免占用输入文字的垂直空间。
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InputFormatChip(
                label = "对话「」",
                onClick = {
                    textFieldValue = insertPair(textFieldValue, "「", "」")
                    isFocused = true
                },
            )
            InputFormatChip(
                label = "动作（）",
                onClick = {
                    textFieldValue = insertPair(textFieldValue, "（", "）")
                    isFocused = true
                },
            )
        }

        // 输入框主体
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 0.dp,
            tonalElevation = if (isFocused) 1.dp else 0.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            border =
                BorderStroke(
                    width = 1.dp,
                    color =
                        if (isFocused) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
                        },
                ),
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border =
                    BorderStroke(
                        width = 0.75.dp,
                        color =
                            if (isFocused) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                            },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 文本输入区域
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { textFieldValue = it },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        minLines = 1,
                        maxLines = 4,
                        enabled = enabled,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (textFieldValue.text.isBlank()) {
                                    Text(
                                        text = if (participants.isNotEmpty()) "输入消息… (@提及角色)" else "输入消息…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp, max = 88.dp)
                                .onFocusChanged { isFocused = it.isFocused },
                    )

                    // 发送/停止按钮
                    AnimatedVisibility(
                        visible = isEnabled || showStop,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                    ) {
                        FloatingActionButton(
                            onClick = {
                                if (showStop) {
                                    onStop?.invoke()
                                } else if (isEnabled) {
                                    onSend(textFieldValue.text.trim())
                                    textFieldValue = TextFieldValue("")
                                    focusManager.clearFocus()
                                }
                            },
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            containerColor =
                                when {
                                    showStop -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                },
                            elevation =
                                FloatingActionButtonDefaults.elevation(
                                    defaultElevation = 0.dp,
                                    pressedElevation = 2.dp,
                                ),
                        ) {
                            Icon(
                                imageVector = if (showStop) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (showStop) "停止生成" else "发送",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun InputFormatChip(
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = Modifier.height(28.dp),
        border =
            AssistChipDefaults.assistChipBorder(
                enabled = true,
                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            ),
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

/**
 * 插入一对格式字符：有选中文本时包裹选区，没有选区时把光标放在两个字符之间。
 * 作为纯函数保留，避免按钮点击时直接修改输入状态造成边界错误。
 */
internal fun insertPair(
    value: TextFieldValue,
    opening: String,
    closing: String,
): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val selected = value.text.substring(start, end)
    val replacement = opening + selected + closing
    val newText = value.text.replaceRange(start, end, replacement)
    val cursor = if (selected.isEmpty()) start + opening.length else start + replacement.length
    return value.copy(
        text = newText,
        selection = TextRange(cursor),
    )
}
