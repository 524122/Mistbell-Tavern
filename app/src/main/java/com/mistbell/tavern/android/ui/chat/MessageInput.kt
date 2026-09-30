package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
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

    // 动画效果
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 8.dp else 2.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        label = "elevation",
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isFocused) 28.dp else 24.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "corner",
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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

        // 输入框主体
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
            shape = RoundedCornerShape(cornerRadius),
            shadowElevation = elevation,
            tonalElevation = if (isFocused) 3.dp else 1.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 文本输入区域
                Surface(
                    modifier =
                        Modifier
                            .weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                ) {
                    TextField(
                        value = textFieldValue,
                        onValueChange = { textFieldValue = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused },
                        placeholder = {
                            Text(
                                text = if (participants.isNotEmpty()) "输入消息... (@提及角色)" else "输入消息...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions =
                            KeyboardActions(
                                onSend = {
                                    if (isEnabled) {
                                        onSend(textFieldValue.text.trim())
                                        textFieldValue = TextFieldValue("")
                                        focusManager.clearFocus()
                                    }
                                },
                            ),
                        maxLines = 6,
                        enabled = enabled,
                    )
                }

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
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        containerColor =
                            when {
                                showStop -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 8.dp,
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
