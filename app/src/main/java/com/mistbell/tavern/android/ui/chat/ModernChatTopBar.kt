package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.model.Character

@Composable
fun ModernChatTopBar(
    chatTitle: String,
    displayCharacters: List<Character>,
    isScrolled: Boolean = false,
    onBackClick: () -> Unit,
    onPromptClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 滚动时增加阴影效果
    val elevation by animateDpAsState(
        targetValue = if (isScrolled) 4.dp else 0.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "elevation",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = elevation,
        tonalElevation = if (isScrolled) 3.dp else 0.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            // 状态栏占位
            Spacer(modifier = Modifier.statusBarsPadding())

            // 主导航栏
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 返回按钮
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 中间：头像 + 标题
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    // 复合头像
                    CompositeCharacterAvatar(
                        characters = displayCharacters,
                        modifier = Modifier.size(40.dp),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // 标题和副标题
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = chatTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            // 文字超出时显示省略号
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // 副标题：显示角色数量
                        if (displayCharacters.isNotEmpty()) {
                            Text(
                                text =
                                    if (displayCharacters.size > 1) {
                                        "${displayCharacters.size} 位参与者"
                                    } else {
                                        "单人对话"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 右侧按钮组
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 提示词预览按钮
                    IconButton(
                        onClick = onPromptClick,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "查看提示词",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 设置按钮
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 底部分割线（滚动时显示）
            if (isScrolled) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}
