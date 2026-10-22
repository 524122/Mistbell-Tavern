package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.ui.common.ModernDivider
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import com.mistbell.tavern.android.ui.common.ModernSettingsItem
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.ContextTokenLimitSelector
import com.mistbell.tavern.android.ui.components.formatTokenLimit

/**
 * 主设置界面（现代化风格，唯一设置入口）。
 *
 * 原 SettingsScreen 的功能已全部并入本页（双轨 UI 收敛）：
 * 模型参数（编辑默认 API 配置的采样与超时/重试）、上下文长度、深色模式、
 * 提示词模板、全量备份/恢复、反馈日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Compose 屏幕级组件的既有形态
@Composable
fun ModernSettingsScreen(
    onBack: (() -> Unit)? = null,
    onNavigateToThemeManager: () -> Unit,
    onNavigateToPromptManagement: () -> Unit,
    onNavigateToRulePacks: () -> Unit = {},
    onNavigateToQuickReplies: () -> Unit = {},
    onNavigateToModelParams: () -> Unit = {},
    onNavigateToVersionChangelog: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showAdvancedSettings by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // API 配置卡片区（左右滑动切换，长按预览所有配置）
    val apiConfigViewModel: ApiConfigViewModel = viewModel()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ModernTopBar(
                title = "设置",
                subtitle = "偏好与配置",
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ============ 基础设置（直接展示）============

            ApiSettingsSection(
                apiConfigViewModel = apiConfigViewModel,
            )

            ConversationSettingsSection(viewModel)

            WorldBookSettingsSection(viewModel)

            AppearanceSettingsSection(viewModel, onNavigateToThemeManager)

            DataManagementSection(viewModel, isLoading)

            // ============ 高级设置（折叠）============

            ModernSettingsGroup(title = "⚙️ 高级设置") {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "高级功能",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "提示词、采样、网络与实验功能",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TextButton(onClick = { showAdvancedSettings = !showAdvancedSettings }) {
                        Text(if (showAdvancedSettings) "收起" else "展开")
                        Icon(
                            imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showAdvancedSettings,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptSettingsSection(onNavigateToPromptManagement, onNavigateToRulePacks, onNavigateToQuickReplies)

                    ModelParamsEntrySection(onNavigateToModelParams)

                    ExperimentalFeaturesSection()
                }
            }

            AboutSection(
                onNavigateToVersionChangelog = onNavigateToVersionChangelog,
                onNavigateToAbout = onNavigateToAbout,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * API 设置区域（卡片堆叠式管理为主入口）
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级 section 的既有形态
@Composable
private fun ApiSettingsSection(
    apiConfigViewModel: ApiConfigViewModel,
) {
    ModernSettingsGroup(title = "🔧 AI 连接") {
        // 内联 API 卡片管理：左右滑动切换配置，长按预览所有配置
        InlineApiCardsSection(viewModel = apiConfigViewModel)
    }
}

/**
 * 模型参数入口：下钻到独立页面（编辑默认 API 配置的采样与超时/重试）
 */
@Suppress("FunctionNaming")
@Composable
private fun ModelParamsEntrySection(onNavigateToModelParams: () -> Unit) {
    ModernSettingsGroup(title = "🎛️ 模型参数") {
        ModernSettingsItem(
            title = "模型参数",
            subtitle = "采样、输出预算、超时与重试（作用于默认 API 配置）",
            icon = Icons.Default.Tune,
            onClick = onNavigateToModelParams,
        )
    }
}

/**
 * 对话设置区域
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ConversationSettingsSection(viewModel: SettingsViewModel) {
    val defaultContextTokens by viewModel.defaultContextTokens.collectAsState()

    ModernSettingsGroup(title = "💬 对话设置") {
        // 上下文长度：新会话的默认上下文 token 预算（与会话级设置共用同一组预设档位）
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "默认上下文长度",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "新会话的默认上下文 token 预算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatTokenLimit(defaultContextTokens),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ContextTokenLimitSelector(
                value = defaultContextTokens,
                // 全局页自身即真相源：组件不提供"跟随全局"档，回调值恒非空
                onValueChange = { value -> value?.let { viewModel.setDefaultContextTokens(it) } },
            )
        }
    }
}

/**
 * 世界书全局设置区域：扫描深度与 token 预算（对齐酒馆 World Info 全局项；
 * 空值=跟随内置默认，默认值的唯一来源是 ChatSettingsResolver）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookSettingsSection(viewModel: SettingsViewModel) {
    val scanDepth by viewModel.worldInfoScanDepthSetting.collectAsState()
    val budgetPercent by viewModel.worldInfoBudgetPercentSetting.collectAsState()

    ModernSettingsGroup(title = "📚 世界书") {
        WorldBookNumberRow(
            title = "扫描深度",
            subtitle = "关键词匹配扫描最近 N 条消息（默认 2，对齐酒馆）",
            value = scanDepth,
            placeholder = "2",
            onSave = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_SCAN_DEPTH, it) },
        )
        ModernDivider()
        WorldBookNumberRow(
            title = "Token 预算 (%)",
            subtitle = "世界书内容占上下文预算的百分比，0 = 不裁剪（默认 25，对齐酒馆）",
            value = budgetPercent,
            placeholder = "25",
            onSave = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_PERCENT, it) },
        )
        ModernDivider()
        WorldBookNumberRow(
            title = "预算上限 (token)",
            subtitle = "世界书 token 预算的绝对上限，0 = 只按百分比（对齐酒馆 budget_cap）",
            value = viewModel.worldInfoBudgetCapSetting.collectAsState().value,
            placeholder = "0",
            onSave = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_CAP, it) },
        )
        ModernDivider()
        // @D 落点开关：开=插进历史内部（酒馆一致）；关=尾部注入（省 token，前缀缓存更长）
        WorldBookSwitchRow(
            title = "深度插入进历史",
            subtitle = "开 = 与酒馆一致（@D 插进历史倒数第 N 条）；关 = 注入历史之后，省 token",
            checked = viewModel.worldInfoInHistorySetting.collectAsState().value,
            onToggle = {
                viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_IN_HISTORY, if (it) "1" else "0")
            },
        )
        ModernDivider()
        // 递归扫描开关：开=本轮已激活条目内容参与后续轮匹配（酒馆默认关）
        WorldBookSwitchRow(
            title = "递归扫描",
            subtitle = "开 = 已激活条目的内容会继续触发其他条目（最多 3 轮，对齐酒馆）",
            checked = viewModel.worldInfoRecursiveSetting.collectAsState().value,
            onToggle = {
                viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_RECURSIVE, if (it) "1" else "0")
            },
        )
        ModernDivider()
        WorldBookAdvancedRows(viewModel)
    }
}

/** 世界书高级行：min-activations 加深扫描两项 + include_names 开关（拆出控制 section 长度） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookAdvancedRows(viewModel: SettingsViewModel) {
    WorldBookNumberRow(
        title = "最小激活数",
        subtitle = "激活条目不足 N 条时逐轮加深扫描（酒馆 min_activations，0=关闭）",
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
        subtitle = "开 = 扫描文本形如「名字: 内容」，名字也可命中关键词（酒馆默认开）",
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
        subtitle = "开 = 同组保留命中关键词最多者（酒馆 group scoring）；关 = 按权重加权随机",
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

/** 多来源合并（对齐酒馆四源 + 插入策略）：全局书多选、persona 绑定、排序策略 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookSourcesRows(viewModel: SettingsViewModel) {
    val books by viewModel.allWorldBooks.collectAsState()
    val globalsRaw by viewModel.worldInfoGlobalBookIdsSetting.collectAsState()
    val globals =
        remember(globalsRaw) {
            try {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(globalsRaw).toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text("全局世界书（可多选）", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "酒馆 global lore；全不选时使用内置 main 兜底",
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
        subtitle = "酒馆 persona lore：与「用户人设」绑定的书（不选=不启用）",
        books = books,
        selectedId = viewModel.worldInfoPersonaBookIdSetting.collectAsState().value,
        onSelect = { viewModel.saveTextSettingQuietly(ChatSettingsResolver.KEY_WORLD_INFO_PERSONA_BOOK_ID, it) },
    )
    ModernDivider()
    WorldBookStrategyRow(viewModel)
}

/** 单选书下拉行（"不选"+全部书） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldBookBookPickerRow(
    title: String,
    subtitle: String,
    books: List<com.mistbell.tavern.android.data.local.entity.WorldBookEntity>,
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

/** 插入策略三档（酒馆 world_info_insertion_strategy） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun WorldBookStrategyRow(viewModel: SettingsViewModel) {
    val strategyRaw by viewModel.worldInfoCharacterStrategySetting.collectAsState()
    val strategy = ChatSettingsResolver.worldInfoCharacterStrategy(strategyRaw)
    val options =
        listOf(
            ChatSettingsResolver.STRATEGY_CHARACTER_FIRST to "角色书优先（默认）",
            ChatSettingsResolver.STRATEGY_EVENLY to "混排（evenly）",
            ChatSettingsResolver.STRATEGY_GLOBAL_FIRST to "全局书优先",
        )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text("插入策略", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "多来源条目同位置时的排序（对齐酒馆 world_info_character_strategy）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** 数字设置行：仅接受整数或空（空=恢复默认）；合法即逐键静默保存 */
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                if (input.isBlank() || input.toIntOrNull() != null) {
                    text = input
                    onSave(input)
                }
            },
            placeholder = { Text(placeholder) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 提示词设置区域：自定义提示词管理入口 + 全局提示词模板（主提示词/用户名/人设/群聊规范）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun PromptSettingsSection(
    onNavigateToPromptManagement: () -> Unit,
    onNavigateToRulePacks: () -> Unit = {},
    onNavigateToQuickReplies: () -> Unit = {},
) {
    val promptViewModel: CustomPromptViewModel = viewModel()
    val enabledPromptsCount by promptViewModel.enabledPromptsCount.collectAsState()

    ModernSettingsGroup(title = "📝 提示词管理") {
        ModernSettingsItem(
            title = "自定义提示词",
            subtitle = "管理破甲、文风等提示词 • 当前启用: $enabledPromptsCount 个",
            icon = Icons.Default.Edit,
            onClick = onNavigateToPromptManagement,
        )
        ModernSettingsItem(
            title = "规则包",
            subtitle = "导入/分享规则包：提示词块、输出正则、自定义宏",
            icon = Icons.Default.Edit,
            onClick = onNavigateToRulePacks,
        )
        ModernSettingsItem(
            title = "快捷回复",
            subtitle = "输入区上方的可点击按钮：发送、填入、执行命令",
            icon = Icons.Default.Edit,
            onClick = onNavigateToQuickReplies,
        )
    }

    // 全局提示词模板卡片（键常量与解析见 ChatSettingsResolver）
    PromptTemplateCard(viewModel())
}

/**
 * 外观设置区域
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级 section 的既有形态
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsSection(
    viewModel: SettingsViewModel,
    onNavigateToThemeManager: () -> Unit,
) {
    val darkMode by viewModel.darkMode.collectAsState()
    var darkModeExpanded by remember { mutableStateOf(false) }

    ModernSettingsGroup(title = "🎨 外观") {
        ModernSettingsItem(
            title = "主题管理",
            subtitle = "主题色、字体大小、消息样式",
            icon = Icons.Default.Palette,
            onClick = onNavigateToThemeManager,
        )

        ModernDivider()

        // 深色模式：跟随系统 / 浅色 / 深色
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "深色模式",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "跟随系统 / 手动切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExposedDropdownMenuBox(
                expanded = darkModeExpanded,
                onExpandedChange = { darkModeExpanded = it },
            ) {
                Surface(
                    onClick = { darkModeExpanded = true },
                    modifier =
                        Modifier
                            .menuAnchor()
                            .width(108.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Text(
                        text =
                            when (darkMode) {
                                "light" -> "浅色"
                                "dark" -> "深色"
                                else -> "跟随系统"
                            },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                ExposedDropdownMenu(
                    expanded = darkModeExpanded,
                    onDismissRequest = { darkModeExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("跟随系统") },
                        onClick = {
                            viewModel.setDarkMode("system")
                            darkModeExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("浅色") },
                        onClick = {
                            viewModel.setDarkMode("light")
                            darkModeExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("深色") },
                        onClick = {
                            viewModel.setDarkMode("dark")
                            darkModeExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * 数据管理区域（全量备份/恢复）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun DataManagementSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
) {
    DataBackupCard(viewModel = viewModel, isLoading = isLoading)
}

/**
 * 实验性功能区域（功能就绪前仅展示说明，不提供死开关）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ExperimentalFeaturesSection() {
    ModernSettingsGroup(title = "🔬 实验性功能") {
        HtmlUiToggleItem()
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "函数调用、多模态等实验能力正在开发中，就绪前不开放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * HTML 界面开关：默认关；开启前弹风险确认（楼层 HTML 在沙箱 WebView 渲染，
 * 无网络（仅图片）、无文件访问，变量会话级隔离）。
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 组件按官方约定 PascalCase 命名；单开关项含确认对话框
@Composable
private fun HtmlUiToggleItem() {
    val db = com.mistbell.tavern.android.TavernApplication.instance.container.database
    val scope = rememberCoroutineScope()
    val enabled by
        db.settingsDao().observeValue("html_ui_enabled")
            .collectAsStateWithLifecycle(initialValue = null)
    var showConfirm by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("HTML 界面", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "楼层 HTML 在沙箱 WebView 中渲染（禁网络/文件/地理位置，仅放行图片）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled == "1",
            onCheckedChange = { wantOn ->
                if (wantOn) {
                    showConfirm = true
                } else {
                    scope.launch {
                        db.settingsDao().upsert(
                            com.mistbell.tavern.android.data.local.entity.SettingsEntity("html_ui_enabled", "0"),
                        )
                    }
                }
            },
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("启用 HTML 界面？") },
            text = {
                Text(
                    "部分角色卡（如含酒馆助手脚本的卡）会在消息中携带可交互 HTML 界面。\n\n" +
                        "本功能在沙箱 WebView 中渲染：禁止网络（仅图片可加载）、禁止文件访问、禁止地理位置，" +
                        "卡片脚本只能访问本会话的变量与最近消息。\n\n" +
                        "仍请只对信任的卡片开启。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    scope.launch {
                        db.settingsDao().upsert(
                            com.mistbell.tavern.android.data.local.entity.SettingsEntity("html_ui_enabled", "1"),
                        )
                    }
                }) {
                    Text("启用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 关于区域
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun AboutSection(
    onNavigateToVersionChangelog: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    var showCrashLogDialog by remember { mutableStateOf(false) }

    ModernSettingsGroup(title = "ℹ️ 关于") {
        ModernSettingsItem(
            title = "版本日志",
            subtitle = "查看版本更新记录",
            icon = Icons.Default.Description,
            onClick = onNavigateToVersionChangelog,
        )

        ModernDivider()

        ModernSettingsItem(
            title = "反馈日志",
            subtitle = "查看崩溃日志、导出反馈给开发者",
            icon = Icons.Default.Refresh,
            onClick = { showCrashLogDialog = true },
        )

        ModernDivider()

        ModernSettingsItem(
            title = "关于",
            subtitle = "应用信息、帮助文档",
            icon = Icons.Default.Info,
            onClick = onNavigateToAbout,
        )
    }

    if (showCrashLogDialog) {
        CrashLogDialog(onDismiss = { showCrashLogDialog = false })
    }
}
