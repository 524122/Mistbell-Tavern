package com.mistbell.tavern.android.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 用户模式枚举
 */
enum class UserMode {
    BEGINNER, // 新手模式 - 简化界面，隐藏高级功能
    ADVANCED, // 老手模式 - 完整功能，高级设置
}

/**
 * 用户模式数据类
 */
data class UserModeConfig(
    val mode: UserMode,
    val showAdvancedSettings: Boolean,
    val showTechnicalDetails: Boolean,
    val showExperimentalFeatures: Boolean,
)

/**
 * 默认配置
 */
fun UserMode.toConfig(): UserModeConfig {
    return when (this) {
        UserMode.BEGINNER ->
            UserModeConfig(
                mode = UserMode.BEGINNER,
                showAdvancedSettings = false,
                showTechnicalDetails = false,
                showExperimentalFeatures = false,
            )
        UserMode.ADVANCED ->
            UserModeConfig(
                mode = UserMode.ADVANCED,
                showAdvancedSettings = true,
                showTechnicalDetails = true,
                showExperimentalFeatures = true,
            )
    }
}

/**
 * 用户模式选择对话框
 */
@Composable
fun UserModeSelectionDialog(
    currentMode: UserMode,
    onModeSelected: (UserMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = "选择使用模式",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "根据你的经验选择合适的界面模式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 新手模式卡片
                UserModeCard(
                    title = "新手模式",
                    description = "简化界面，隐藏高级功能，适合初次使用",
                    icon = Icons.Default.School,
                    features =
                        listOf(
                            "简洁的设置选项",
                            "引导式操作流程",
                            "隐藏技术细节",
                            "推荐配置",
                        ),
                    isSelected = currentMode == UserMode.BEGINNER,
                    onClick = { onModeSelected(UserMode.BEGINNER) },
                )

                // 老手模式卡片
                UserModeCard(
                    title = "老手模式",
                    description = "完整功能，高级设置，适合有经验的用户",
                    icon = Icons.Default.Engineering,
                    features =
                        listOf(
                            "完整的设置选项",
                            "高级参数调整",
                            "技术细节展示",
                            "实验性功能",
                        ),
                    isSelected = currentMode == UserMode.ADVANCED,
                    onClick = { onModeSelected(UserMode.ADVANCED) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        },
    )
}

/**
 * 用户模式卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserModeCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    features: List<String>,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
        border =
            if (isSelected) {
                androidx.compose.foundation.BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                )
            } else {
                null
            },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 图标
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // 内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )

                // 特性列表
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    features.forEach { feature ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                }
            }

            // 选中指示器
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * 用户模式切换按钮（用于设置界面）
 */
@Composable
fun UserModeToggle(
    currentMode: UserMode,
    onModeChange: (UserMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 新手模式按钮
            FilterChip(
                selected = currentMode == UserMode.BEGINNER,
                onClick = { onModeChange(UserMode.BEGINNER) },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("新手")
                    }
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )

            // 老手模式按钮
            FilterChip(
                selected = currentMode == UserMode.ADVANCED,
                onClick = { onModeChange(UserMode.ADVANCED) },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Engineering,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("老手")
                    }
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        }
    }
}

/**
 * 高级功能包装器 - 根据用户模式显示/隐藏内容
 */
@Composable
fun AdvancedFeature(
    userModeConfig: UserModeConfig,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = userModeConfig.showAdvancedSettings,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

/**
 * 实验性功能包装器
 */
@Composable
fun ExperimentalFeature(
    userModeConfig: UserModeConfig,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = userModeConfig.showExperimentalFeatures,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}
