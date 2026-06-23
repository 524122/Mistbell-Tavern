package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.ui.theme.*

@Composable
fun MessageInput(
    onSend: (String) -> Unit,
    enabled: Boolean = true
) {
    var text by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val isEnabled = text.isNotBlank() && enabled

    Column(
        modifier = Modifier
            .widthIn(max = 780.dp),  // 只保留宽度限制，其他由外层控制
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Input wrapper - max-width 780dp centered
        Box(modifier = Modifier.widthIn(max = 780.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        if (isFocused) 3.dp else 1.dp,
                        RoundedCornerShape(22.dp),
                        ambientColor = if (isFocused) AccentBlue.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.04f)
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        if (isFocused) AccentBlue else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .onFocusChanged { isFocused = it.isFocused },
                    placeholder = {
                        Text(
                            "输入消息...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (isEnabled) {
                                onSend(text.trim())
                                text = ""
                                focusManager.clearFocus()
                            }
                        }
                    ),
                    maxLines = 4,
                    enabled = enabled
                )

                // Send button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(
                            if (isEnabled) 4.dp else 0.dp,
                            CircleShape,
                            ambientColor = if (isEnabled) AccentBlue.copy(alpha = 0.35f) else Color.Transparent
                        )
                        .clip(CircleShape)
                        .background(
                            if (isEnabled) AccentBlue
                            else MaterialTheme.colorScheme.outline
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (isEnabled) {
                                onSend(text.trim())
                                text = ""
                                focusManager.clearFocus()
                            }
                        },
                        enabled = isEnabled,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (isEnabled) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
