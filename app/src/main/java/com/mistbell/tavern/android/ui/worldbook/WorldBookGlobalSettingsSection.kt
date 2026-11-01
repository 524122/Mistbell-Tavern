package com.mistbell.tavern.android.ui.worldbook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.ui.common.ModernDivider
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import com.mistbell.tavern.android.ui.settings.SettingsViewModel

/**
 * 世界书 Tab 的全局设置分组（原「设置」页「📚 世界书」区整体迁移而来，纯 UI 迁移）：
 * 扫描深度、Token 预算 (%)、预算上限、深度插入进历史、递归扫描、最小激活数、
 * 加深扫描上限、扫描带名字前缀、分组按匹配分选拔、全局世界书多选、
 * Persona 世界书绑定与插入策略——控件集合与设置键均与迁移前完全一致。
 *
 * 设置读写继续走 [SettingsViewModel] 既有流与方法（本文件不含任何新逻辑）；
 * ViewModel 由调用方（MainScreen 世界书 Tab，与设置 Tab 同一 factory）提供。
 * 分组默认收起、点「世界书全局参数」行展开，避免把书籍列表挤出首屏。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun WorldBookGlobalSettingsSection(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    // 默认收起：Tab 主角是书卡列表，点折叠行才展开参数面板
    var expanded by rememberSaveable { mutableStateOf(false) }
    ModernSettingsGroup(title = "⚙️ 全局设置", modifier = modifier) {
        // 折叠行只留一行标题 + chevron：组名「⚙️ 全局设置」已是分组头，不再叠一层大标题/副标题
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "世界书全局参数",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                WorldBookScanBudgetRows(viewModel)
                ModernDivider()
                WorldBookAdvancedRows(viewModel)
            }
        }
    }
}

/** 扫描与预算基础行：扫描深度、Token 预算 (%)、预算上限、@D 落点与递归扫描（纯用户语义文案） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookScanBudgetRows(viewModel: SettingsViewModel) {
    val scanDepth by viewModel.worldInfoScanDepthSetting.collectAsState()
    val budgetPercent by viewModel.worldInfoBudgetPercentSetting.collectAsState()

    WorldBookNumberRow(
        title = "扫描深度",
        subtitle = "关键词匹配扫描最近 N 条消息（默认 2）",
        value = scanDepth,
        placeholder = "2",
        onSave = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_SCAN_DEPTH, it) },
    )
    ModernDivider()
    WorldBookNumberRow(
        title = "Token 预算 (%)",
        subtitle = "世界书内容占上下文预算的百分比，0 = 不裁剪（默认 25）",
        value = budgetPercent,
        placeholder = "25",
        onSave = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_PERCENT, it) },
    )
    ModernDivider()
    WorldBookNumberRow(
        title = "预算上限 (token)",
        subtitle = "世界书 token 预算的绝对上限，0 = 只按百分比",
        value = viewModel.worldInfoBudgetCapSetting.collectAsState().value,
        placeholder = "0",
        onSave = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_CAP, it) },
    )
    ModernDivider()
    // @D 落点开关：开=插进历史内部（酒馆一致）；关=尾部注入（省 token，前缀缓存更长）
    WorldBookSwitchRow(
        title = "深度插入进历史",
        subtitle = "开 = @D 插进历史倒数第 N 条；关 = 注入历史之后，省 token",
        checked = viewModel.worldInfoInHistorySetting.collectAsState().value,
        onToggle = {
            viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_IN_HISTORY, if (it) "1" else "0")
        },
    )
    ModernDivider()
    // 递归扫描开关：开=本轮已激活条目内容参与后续轮匹配（酒馆默认关）
    WorldBookSwitchRow(
        title = "递归扫描",
        subtitle = "开 = 已激活条目的内容会继续触发其他条目（最多 3 轮）",
        checked = viewModel.worldInfoRecursiveSetting.collectAsState().value,
        onToggle = {
            viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_RECURSIVE, if (it) "1" else "0")
        },
    )
}

/** 世界书高级行：min-activations 加深扫描两项 + include_names / 分组择优开关 + 多来源合并（拆出控制 section 长度） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookAdvancedRows(viewModel: SettingsViewModel) {
    WorldBookNumberRow(
        title = "最小激活数",
        subtitle = "激活条目不足 N 条时逐轮加深扫描（0=关闭）",
        value = viewModel.worldInfoMinActivationsSetting.collectAsState().value,
        placeholder = "0",
        onSave = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_MIN_ACTIVATIONS, it) },
    )
    ModernDivider()
    WorldBookNumberRow(
        title = "加深扫描上限",
        subtitle = "最小激活数模式的深度上限（0=不限，到聊天长度为止）",
        value = viewModel.worldInfoMinActivationsDepthMaxSetting.collectAsState().value,
        placeholder = "0",
        onSave = {
            viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_MIN_ACTIVATIONS_DEPTH_MAX, it)
        },
    )
    ModernDivider()
    WorldBookSwitchRow(
        title = "扫描带名字前缀",
        subtitle = "开 = 扫描文本形如「名字: 内容」，名字也可命中关键词",
        checked = viewModel.worldInfoIncludeNamesSetting.collectAsState().value,
        onToggle = {
            viewModel.saveTextSettingQuietly(
                ChatSettingsResolver.KEY_WORLD_INFO_INCLUDE_NAMES,
                if (it) "1" else "0",
            )
        },
    )
    ModernDivider()
    WorldBookSwitchRow(
        title = "分组按匹配分选拔",
        subtitle = "开 = 同组保留命中关键词最多者；关 = 按权重加权随机",
        checked = viewModel.worldInfoUseGroupScoringSetting.collectAsState().value,
        onToggle = {
            viewModel.saveTextSettingQuietly(
                ChatSettingsResolver.KEY_WORLD_INFO_USE_GROUP_SCORING,
                if (it) "1" else "0",
            )
        },
    )
    ModernDivider()
    WorldBookSourcesRows(viewModel)
}

/** 多来源合并（四源合并 + 插入策略）：全局书多选、persona 绑定、排序策略 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookSourcesRows(viewModel: SettingsViewModel) {
    val books by viewModel.allWorldBooks.collectAsState()
    val globalsRaw by viewModel.worldInfoGlobalBookIdsSetting.collectAsState()
    val globals = remember(globalsRaw) { parseGlobalBookIds(globalsRaw) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text("全局世界书（可多选）", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "全不选时使用内置 main 兜底",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        books.forEach { book ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(book.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = book.id in globals,
                    onCheckedChange = { viewModel.toggleGlobalWorldBook(book.id, it) },
                )
            }
        }
    }
    ModernDivider()
    WorldBookBookPickerRow(
        title = "Persona 世界书",
        subtitle = "与「用户人设」绑定的书（不选=不启用）",
        books = books,
        selectedId = viewModel.worldInfoPersonaBookIdSetting.collectAsState().value,
        onSelect = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_PERSONA_BOOK_ID, it) },
    )
    ModernDivider()
    WorldBookStrategyRow(viewModel)
}

/** 解析全局世界书多选存储值（JSON 字符串数组）；为空或损坏一律降级为空集（语义与迁移前内联实现一致，可 JVM 单测） */
internal fun parseGlobalBookIds(raw: String?): Set<String> =
    raw?.let {
        try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(it).toSet()
        } catch (_: Exception) {
            null
        }
    } ?: emptySet()

/** 单选书下拉行（"不选"+全部书） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldBookBookPickerRow(
    title: String,
    subtitle: String,
    books: List<WorldBookEntity>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("" to "不选") + books.map { it.id to it.name }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = options.firstOrNull { it.first == selectedId }?.second ?: "不选",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = RoundedCornerShape(8.dp),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onSelect(id)
                        expanded = false
                    })
                }
            }
        }
    }
}

/** 插入策略三档（角色书优先/混排/全局书优先，存储值不变）；FlowRow 保证窄屏自动换行不挤压 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldBookStrategyRow(viewModel: SettingsViewModel) {
    val strategyRaw by viewModel.worldInfoCharacterStrategySetting.collectAsState()
    val strategy = ChatSettingsResolver.worldInfoCharacterStrategy(strategyRaw)
    val options =
        listOf(
            ChatSettingsResolver.STRATEGY_CHARACTER_FIRST to "角色书优先",
            ChatSettingsResolver.STRATEGY_EVENLY to "混排",
            ChatSettingsResolver.STRATEGY_GLOBAL_FIRST to "全局书优先",
        )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text("插入策略", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "多来源条目同位置时的排序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = strategy == value,
                    onClick = {
                        viewModel.saveTextSettingQuietly(
                            ChatSettingsResolver.KEY_WORLD_INFO_CHARACTER_STRATEGY,
                            value.toString(),
                        )
                    },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                )
            }
        }
    }
}

/** 开关设置行：标题 + 说明 + 开关（1/0 存储由调用方转换） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/**
 * 数字设置行（紧凑单行形态）：左侧标题 + 说明、右侧 80dp 行内小输入框（48dp 触达高度，singleLine）。
 * 仅接受整数或空（空=恢复默认）；合法即逐键静默保存——校验与保存语义与全宽版完全一致。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookNumberRow(
    title: String,
    subtitle: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit,
) {
    // 以存储值为准重置本地草稿：备份恢复/他处修改后输入框同步
    var text by remember(value) { mutableStateOf(value) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                if (input.isBlank() || input.toIntOrNull() != null) {
                    text = input
                    onSave(input)
                }
            },
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.width(80.dp).height(48.dp),
        )
    }
}
