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
 * 界面模式组件（P3「界面模式」设置项 + 第 2 项「按模式收拢界面」的界面可见性表；
 * 模式本体是 data.model.UiMode 的单一真相）。
 *
 * [UserModeConfig] 是**界面可见性**配置：只决定入口/区块是否展示，与执行闸门的
 * EnabledCapabilities（某能力是否运行，data.model）是两回事——简洁模式下世界书四源、
 * 自定义提示词、会话记忆等照常执行，只是改它们的入口收进高级模式。
 *
 * 三个布尔的语义（第 2 项定稿；每个入口归哪一项见各 Composable 调用点）：
 * - [showAdvancedSettings]：「⚙️ 高级设置」折叠组——自定义提示词 / 规则包 / 扩展包、
 *   全局提示词模板（主提示词 / 用户名 / 人设 / 群聊规范）。
 * - [showTechnicalDetails]：技术细节项——世界书入口（Tab 已收拢，这里是会话世界书绑定）、
 *   会话附加指令、原生渲染引擎开关。
 * - [showExperimentalFeatures]：「🔬 实验性功能」整组（含 HTML 界面开关）。
 *
 * 恒可见（不设字段，与模式无关——「照常执行」或与执行无关的入口）：API 连接、模型参数、
 * 对话设置（默认上下文长度）、外观与主题、快捷回复、数据备份、会话记忆开关与记忆管理入口、
 * 界面模式本身、关于。可见性表的单测见 UserModeConfigVisibilityTest。
 */
data class UserModeConfig(
    val mode: UiMode,
    val showAdvancedSettings: Boolean,
    val showTechnicalDetails: Boolean,
    val showExperimentalFeatures: Boolean,
) {
    /** 「📝 提示词管理」组（自定义提示词 / 规则包 / 扩展包 + 全局提示词模板卡） */
    val showPromptManagement: Boolean
        get() = showAdvancedSettings

    /** 世界书编辑 / 绑定入口（主导航 Tab 与会话世界书选择） */
    val showWorldBookEntries: Boolean
        get() = showTechnicalDetails

    /** 会话附加指令（author note）编辑区 */
    val showSessionAuthorNote: Boolean
        get() = showTechnicalDetails

    /** 原生渲染引擎（布局主题）开关 */
    val showLayoutRendererToggle: Boolean
        get() = showTechnicalDetails
}

/**
 * 模式 → 可见性配置（唯一换算点；简单模式三组全收，高级模式全开）
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

// ---- 模式相关入口的渲染谓词（纯函数，供包装器与 JVM 单测共用） ----
//
// 配置为 null（模式未知：AppContainer.uiMode 尚未从数据库读回）一律 false——界面与执行闸门
// 的「未知不放行」同一口径（docs/UI_MODE.md §1 通用约定）：宁可少渲染一帧，不闪错误的入口。
// 三个谓词独立成具名扩展，是为了「未知不渲染」可被 JVM 单测（UserModeConfigVisibilityTest）。

/** 「⚙️ 高级设置」折叠组（提示词管理组 / 全局提示词模板） */
internal val UserModeConfig?.advancedVisible: Boolean
    get() = this?.showAdvancedSettings == true

/** 技术细节项（世界书绑定 / 附加指令 / 原生渲染引擎开关） */
internal val UserModeConfig?.technicalDetailVisible: Boolean
    get() = this?.showTechnicalDetails == true

/** 「🔬 实验性功能」整组（含 HTML 界面开关） */
internal val UserModeConfig?.experimentalVisible: Boolean
    get() = this?.showExperimentalFeatures == true

/**
 * 高级功能包装器 - 根据用户模式显示/隐藏内容。
 * 配置为 null（模式未知）时不渲染：口径见 [advancedVisible]。
 */
@Composable
fun AdvancedFeature(
    userModeConfig: UserModeConfig?,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = userModeConfig.advancedVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

/**
 * 技术细节包装器（世界书入口 / 附加指令 / 原生渲染引擎开关）。
 * 配置为 null（模式未知）时不渲染，口径同 [AdvancedFeature]。
 */
@Composable
fun TechnicalDetailFeature(
    userModeConfig: UserModeConfig?,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = userModeConfig.technicalDetailVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

/**
 * 实验性功能包装器。配置为 null（模式未知）时不渲染，口径同 [AdvancedFeature]。
 */
@Composable
fun ExperimentalFeature(
    userModeConfig: UserModeConfig?,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = userModeConfig.experimentalVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}
