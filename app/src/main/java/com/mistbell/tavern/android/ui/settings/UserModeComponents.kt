@file:Suppress("MatchingDeclarationName") // 文件按组件族命名（对话框/开关/包装器），ROADMAP 引用的就是这个文件名

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
import com.mistbell.tavern.android.data.model.UiMode

/**
 * 界面模式组件（P3「界面模式」设置项；模式本体是 data.model.UiMode 的单一真相）。
 *
 * [UserModeConfig] 是**界面可见性**配置（哪些设置区块展示），与执行闸门的
 * EnabledCapabilities 是两回事：可见性收拢是 P3 第 2 项，本文件当前只服务设置页的
 * 「界面模式」切换入口。
 */
data class UserModeConfig(
    val mode: UiMode,
    val showAdvancedSettings: Boolean,
    val showTechnicalDetails: Boolean,
    val showExperimentalFeatures: Boolean,
)

/**
 * 默认配置
 */
fun UiMode.toConfig(): UserModeConfig {
    return when (this) {
        UiMode.SIMPLE ->
            UserModeConfig(
                mode = UiMode.SIMPLE,
                showAdvancedSettings = false,
                showTechnicalDetails = false,
                showExperimentalFeatures = false,
            )
        UiMode.ADVANCED ->
            UserModeConfig(
                mode = UiMode.ADVANCED,
                showAdvancedSettings = true,
                showTechnicalDetails = true,
                showExperimentalFeatures = true,
            )
    }
}

/** 模式中文名（设置项与对话框共用同一份文案） */
val UiMode.displayName: String
    get() =
        when (this) {
            UiMode.SIMPLE -> "简洁"
            UiMode.ADVANCED -> "高级"
        }

/** 简洁模式下不运行的能力清单（规划原文的两句话之一；对话框与设置副标题共用） */
private const val SIMPLE_DISABLED_SUMMARY =
    "简洁模式下这些能力不运行：正则、规则包（含脚本）、扩展包钩子、卡内脚本与 HTML 界面、MVU 变量更新。"

/** 提示词不受影响的说明（规划原文的另一句） */
private const val PROMPT_PASSTHROUGH_NOTE =
    "全局书、人设书和自定义提示词仍会进提示词，只是不能在简洁模式里改。"

/**
 * 界面模式选择对话框
 */
@Composable
fun UserModeSelectionDialog(
    currentMode: UiMode,
    onModeSelected: (UiMode) -> Unit,
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
                text = "选择界面模式",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                UserModePrinciplesText()

                // 简洁模式卡片
                UserModeCard(
                    title = "简洁模式",
                    description = "三个标签页的默认界面，高级能力不在后台运行",
                    icon = Icons.Default.School,
                    features =
                        listOf(
                            "聊天、角色、设置三栏",
                            "正则、脚本、HTML 界面不运行",
                            "适合只用基础功能的用户",
                        ),
                    isSelected = currentMode == UiMode.SIMPLE,
                    onClick = { onModeSelected(UiMode.SIMPLE) },
                )

                // 高级模式卡片
                UserModeCard(
                    title = "高级模式",
                    description = "完整功能，与酒馆生态导入能力配合",
                    icon = Icons.Default.Engineering,
                    features =
                        listOf(
                            "全部设置与世界书入口",
                            "正则、脚本、HTML 界面照常运行",
                            "适合导入酒馆资产的用户",
                        ),
                    isSelected = currentMode == UiMode.ADVANCED,
                    onClick = { onModeSelected(UiMode.ADVANCED) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
    )
}

/** 对话框开头两句话（规划原文）：简洁模式关什么；提示词不受影响的边界 */
@Composable
private fun UserModePrinciplesText() {
    Text(
        text = SIMPLE_DISABLED_SUMMARY,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = PROMPT_PASSTHROUGH_NOTE,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * 界面模式卡片
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
 * 界面模式切换按钮（用于设置界面）
 */
@Composable
fun UserModeToggle(
    currentMode: UiMode,
    onModeChange: (UiMode) -> Unit,
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
            // 简洁模式按钮
            FilterChip(
                selected = currentMode == UiMode.SIMPLE,
                onClick = { onModeChange(UiMode.SIMPLE) },
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
                        Text("简洁")
                    }
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )

            // 高级模式按钮
            FilterChip(
                selected = currentMode == UiMode.ADVANCED,
                onClick = { onModeChange(UiMode.ADVANCED) },
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
                        Text("高级")
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
