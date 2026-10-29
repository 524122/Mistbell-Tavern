package com.mistbell.tavern.android.ui.regex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.SectionHeader

/**
 * 「正则」Tab：全局正则 + 角色卡正则两段式入口。
 * 全局规则先于卡级执行（酒馆 GLOBAL→SCOPED 优先级）；卡级可逐角色停用。
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态（原由 detekt 基线吸收）
@Composable
fun RegexHubScreen(
    onOpenGlobalRegex: () -> Unit,
    onOpenCharacterRegex: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegexHubViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ModernTopBar(title = "正则", subtitle = "塑造 AI 的输出与显示")
        },
    ) { paddingValues ->
        if (!state.loaded) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "global-entry") {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenGlobalRegex),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("全局正则", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text =
                                    when (state.globalCount) {
                                        0 -> "尚无规则 · 点此创建，对所有会话生效"
                                        1 -> "1 条规则 · 对所有会话生效，先于角色卡执行"
                                        else -> "${state.globalCount} 条规则 · 对所有会话生效，先于角色卡执行"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "card-section-header") {
                SectionHeader("角色卡正则")
            }

            if (state.characters.isEmpty()) {
                item(key = "card-empty") {
                    Text(
                        "暂无角色。导入或创建角色卡后，卡内嵌的正则脚本会汇总在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(items = state.characters, key = { it.characterId }) { summary ->
                val enabled = summary.characterId !in state.disabledCharacterIds
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenCharacterRegex(summary.characterId) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(summary.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text =
                                    when (summary.ruleCount) {
                                        0 -> "无规则"
                                        1 -> "1 条规则"
                                        else -> "${summary.ruleCount} 条规则"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                viewModel.setCharacterRegexEnabled(summary.characterId, it)
                            },
                        )
                    }
                }
            }

            item(key = "card-hint") {
                Text(
                    "开关控制该角色卡内嵌正则是否生效（导入卡的脚本有改写显示与提示词的能力，" +
                        "不信任的卡可以单独停用）；全局正则不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
