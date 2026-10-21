package com.mistbell.tavern.android.ui.worldbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.util.StInterop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookDetailScreen(
    bookId: String,
    onBack: () -> Unit = {},
    viewModel: WorldBookEditorViewModel = viewModel(),
) {
    val worldBooks by viewModel.worldBooks.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val message by viewModel.message.collectAsState()
    val showEntryForm by viewModel.showEntryForm.collectAsState()
    val entryForm by viewModel.entryForm.collectAsState()

    var showDeleteEntryDialog by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 选择当前世界书
    LaunchedEffect(bookId) {
        viewModel.selectBook(bookId)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val selectedBook = worldBooks.find { it.id == bookId }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(22.dp))
                        }
                        Text(
                            text = selectedBook?.name ?: "世界书详情",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.showNewEntryForm() }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加条目")
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 统计信息 - 三列布局
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 总计
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "${entries.size}",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "总计",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 启用
                    val activeCount = entries.count { !it.disable }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "$activeCount",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "启用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    // 禁用
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "${entries.size - activeCount}",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "禁用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                item { EmptyStateView("📝", "暂无条目", "点击右上角 + 添加") }
            }

            items(entries, key = { it.id }) { entry ->
                Card(
                    modifier =
                        Modifier.fillMaxWidth().clickable {
                            viewModel.showEditEntryForm(entry)
                        },
                    shape = RoundedCornerShape(10.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (entry.disable) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 左侧内容 - 限制最大宽度，预留右侧空间
                            Column(
                                modifier = Modifier.weight(1f, fill = false),
                            ) {
                                Text(
                                    entry.comment.ifBlank { "未命名条目" },
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                        if (entry.disable) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                                if (entry.key.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "关键词: ${entry.key.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // 位置摘要：@D 档显示 "[角色] @D深度"（对齐酒馆条目上的 @D 徽标）
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    entryPlacementSummary(entry.insertPosition, entry.depth, entry.depthRole),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                // 显示内容预览
                                if (entry.content.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        entry.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // 右侧固定区域 - 常量标签 + 开关
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 常量标签占位 - 即使不显示也占用空间
                                Box(
                                    modifier = Modifier.width(56.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (entry.constant) {
                                        AssistChip(
                                            onClick = {},
                                            label = {
                                                Text(
                                                    "常量",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                }
                                // 开关
                                Switch(
                                    checked = !entry.disable,
                                    onCheckedChange = { enabled ->
                                        viewModel.updateEntry(
                                            entry.id,
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("disable", kotlinx.serialization.json.JsonPrimitive(!enabled))
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    showDeleteEntryDialog = entry.id
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除条目确认对话框
    showDeleteEntryDialog?.let { entryId ->
        AlertDialog(
            onDismissRequest = { showDeleteEntryDialog = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除这个条目吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEntry(entryId)
                        showDeleteEntryDialog = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteEntryDialog = null }) { Text("取消") }
            },
        )
    }

    // 编辑条目表单
    if (showEntryForm) {
        EntryEditSheet(viewModel, entryForm)
    }
}

/** 条目编辑底部表单：字段较多（名称/关键词/内容/位置/深度/概率/角色），独立组合降低父函数复杂度 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditSheet(
    viewModel: WorldBookEditorViewModel,
    entryForm: WorldBookEntryForm,
) {
    ModalBottomSheet(
        onDismissRequest = { viewModel.hideEntryForm() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // 表单字段较高（名称/关键词/内容/位置/深度/概率），小屏上不滚动会挤掉底部按钮
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (viewModel.editingEntryId.value != null) "编辑条目" else "新建条目",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            EntryContentFields(entryForm, viewModel)

            // Insert position（6 锚点与酒馆对齐）
            // 插入位置：酒馆式 9 选项（6 锚点 + [系统/用户/AI] 插入深度 @D）
            WorldBookPlacementField(
                insertPosition = entryForm.insertPosition,
                depth = entryForm.depth,
                depthRole = entryForm.depthRole,
            ) { position, depth, role ->
                viewModel.updateEntryForm { copy(insertPosition = position, depth = depth, depthRole = role) }
            }

            // 深度（@D 档 1-10，仅选中插入深度档时显示）与触发概率（0-1）
            EntryNumericFields(entryForm, viewModel)

            // 粘性/冷却/延迟/分组/预算豁免（酒馆数值语义）
            EntryTimedFields(entryForm, viewModel)

            // 递归控制与扫描面扩展（酒馆条目级高级项）
            EntryAdvancedFields(entryForm, viewModel)

            // 角色过滤（酒馆 characterFilter：按角色名/标签限定生效范围）
            EntryCharacterFilterFields(entryForm, viewModel)

            // Toggles
            EntryFormToggles(entryForm, viewModel)

            Button(
                onClick = { viewModel.saveEntry() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text("保存") }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** 内容字段块：名称/关键词/触发语义（次关键词+四态逻辑）/内容 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun EntryContentFields(
    entryForm: WorldBookEntryForm,
    viewModel: WorldBookEditorViewModel,
) {
    FormTextField(
        value = entryForm.comment,
        onValueChange = { viewModel.updateEntryForm { copy(comment = it) } },
        label = "名称",
        placeholder = "条目名称",
    )
    FormTextField(
        value = entryForm.keys,
        onValueChange = { viewModel.updateEntryForm { copy(keys = it) } },
        label = "关键词（逗号分隔）",
        placeholder = "关键词1, 关键词2",
    )
    EntryTriggeringFields(entryForm, viewModel)
    FormTextArea(
        value = entryForm.content,
        onValueChange = { viewModel.updateEntryForm { copy(content = it) } },
        label = "内容",
        placeholder = "世界书条目内容...",
        minLines = 4,
    )
}

/** 触发语义字段：次关键词（逗号分隔）+ 四态逻辑下拉（仅在填了次关键词时显示） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun EntryTriggeringFields(
    entryForm: WorldBookEntryForm,
    viewModel: WorldBookEditorViewModel,
) {
    FormTextField(
        value = entryForm.secondaryKeys,
        onValueChange = { viewModel.updateEntryForm { copy(secondaryKeys = it) } },
        label = "次关键词（逗号分隔，可留空）",
        placeholder = "与关键词按下方逻辑联动",
    )
    if (entryForm.secondaryKeys.isNotBlank()) {
        WorldBookLogicField(
            selectiveLogic = entryForm.selectiveLogic,
            onSelect = { logic -> viewModel.updateEntryForm { copy(selectiveLogic = logic) } },
        )
    }
}

/** 高级触发控制：递归三开关（酒馆 exclude/prevent/delayUntilRecursion）+ 扫描面扩展（matchXxx） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun EntryAdvancedFields(
    entryForm: WorldBookEntryForm,
    viewModel: WorldBookEditorViewModel,
) {
    Text("高级触发控制", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Switch(
                checked = entryForm.excludeRecursion,
                onCheckedChange = { viewModel.updateEntryForm { copy(excludeRecursion = it) } },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("内容不进递归", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Switch(
                checked = entryForm.preventRecursion,
                onCheckedChange = { viewModel.updateEntryForm { copy(preventRecursion = it) } },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("仅首轮激活", style = MaterialTheme.typography.bodySmall)
        }
    }
    TimedNumberField("递归延迟层级（0=不限）", entryForm.delayUntilRecursion, Modifier.fillMaxWidth()) { text ->
        viewModel.updateEntryForm {
            if (text.isBlank() || text.toIntOrNull() != null) copy(delayUntilRecursion = text) else this
        }
    }
    Text(
        "扫描时并入（matchXxx）",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MatchScopeSwitch("用户人设", entryForm.matchPersonaDescription, Modifier.weight(1f)) {
            viewModel.updateEntryForm { copy(matchPersonaDescription = it) }
        }
        MatchScopeSwitch("角色描述", entryForm.matchCharacterDescription, Modifier.weight(1f)) {
            viewModel.updateEntryForm { copy(matchCharacterDescription = it) }
        }
        MatchScopeSwitch("场景", entryForm.matchScenario, Modifier.weight(1f)) {
            viewModel.updateEntryForm { copy(matchScenario = it) }
        }
    }
}

/** 扫描面开关小件 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun MatchScopeSwitch(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** 定时与分组字段：粘性/冷却/延迟轮数（0=关闭，酒馆数值语义）、分组名、预算豁免 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun EntryTimedFields(
    entryForm: WorldBookEntryForm,
    viewModel: WorldBookEditorViewModel,
) {
    // 轮数输入过滤：仅接受非负整数或空（空按 0 存）
    fun timed(
        text: String,
        current: String,
    ): String = if (text.isBlank() || text.toIntOrNull() != null) text else current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimedNumberField("粘性轮数", entryForm.sticky, Modifier.weight(1f)) { text ->
            viewModel.updateEntryForm { copy(sticky = timed(text, sticky)) }
        }
        TimedNumberField("冷却轮数", entryForm.cooldown, Modifier.weight(1f)) { text ->
            viewModel.updateEntryForm { copy(cooldown = timed(text, cooldown)) }
        }
        TimedNumberField("延迟轮数", entryForm.delay, Modifier.weight(1f)) { text ->
            viewModel.updateEntryForm { copy(delay = timed(text, delay)) }
        }
    }
    FormTextField(
        value = entryForm.groupName,
        onValueChange = { viewModel.updateEntryForm { copy(groupName = it) } },
        label = "分组（同组只激活一条，可留空）",
        placeholder = "分组名",
    )
    // 分组竞争细项（仅填了分组名时有意义）：权重加权随机 / 覆盖直接胜出
    if (entryForm.groupName.isNotBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimedNumberField("分组权重", entryForm.groupWeight, Modifier.weight(1f)) { text ->
                viewModel.updateEntryForm {
                    if (text.isBlank() || text.toIntOrNull() != null) copy(groupWeight = text) else this
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Switch(
                    checked = entryForm.groupOverride,
                    onCheckedChange = { viewModel.updateEntryForm { copy(groupOverride = it) } },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("覆盖同组", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = entryForm.ignoreBudget,
            onCheckedChange = { viewModel.updateEntryForm { copy(ignoreBudget = it) } },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("不占 token 预算", style = MaterialTheme.typography.bodyMedium)
    }
}

/** 定时轮数小数字框：仅接受非负整数或空（空按 0 存） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun TimedNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    FormTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        placeholder = "0",
        modifier = modifier,
    )
}

/** 启用/常量/大小写敏感/全字匹配开关行 */
@Composable
private fun EntryFormToggles(
    entryForm: WorldBookEntryForm,
    viewModel: WorldBookEditorViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = !entryForm.disable, onCheckedChange = { viewModel.updateEntryForm { copy(disable = !it) } })
            Spacer(modifier = Modifier.width(8.dp))
            Text("启用", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = entryForm.constant, onCheckedChange = { viewModel.updateEntryForm { copy(constant = it) } })
            Spacer(modifier = Modifier.width(8.dp))
            Text("常量", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = entryForm.caseSensitive,
                onCheckedChange = { viewModel.updateEntryForm { copy(caseSensitive = it) } },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("大小写敏感", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = entryForm.matchWholeWords,
                onCheckedChange = { viewModel.updateEntryForm { copy(matchWholeWords = it) } },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("全字匹配", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 深度（仅 @D 档显示，1-10）与触发概率（0-1）、扫描深度（空=跟随全局）三个数字字段 */
@Composable
private fun EntryNumericFields(
    entryForm: WorldBookEntryForm,
    viewModel: WorldBookEditorViewModel,
) {
    if (entryForm.depth > 0) {
        var depthText by remember(entryForm.depth) { mutableStateOf(entryForm.depth.toString()) }
        FormTextField(
            value = depthText,
            onValueChange = {
                depthText = it
                it.toIntOrNull()?.let { d ->
                    viewModel.updateEntryForm { copy(depth = d.coerceIn(0, StInterop.MAX_DEPTH)) }
                }
            },
            label = "插入深度 @D (1-1000)",
            placeholder = "倒数第N条之前（酒馆上限 1000）",
        )
    }

    var probabilityText by remember(entryForm.probability) {
        mutableStateOf(entryForm.probability.toString())
    }
    FormTextField(
        value = probabilityText,
        onValueChange = {
            probabilityText = it
            it.toDoubleOrNull()?.let { p ->
                val clamped = p.coerceIn(MIN_PROBABILITY, MAX_PROBABILITY)
                viewModel.updateEntryForm { copy(probability = clamped) }
            }
        },
        label = "触发概率 (0-1)",
        placeholder = "1",
    )

    // 扫描深度（酒馆 scanDepth）：空=跟随全局设置（默认 2）
    FormTextField(
        value = entryForm.scanDepth,
        onValueChange = { text ->
            viewModel.updateEntryForm {
                if (text.isBlank() || text.toIntOrNull() != null) copy(scanDepth = text) else this
            }
        },
        label = "扫描深度（留空跟随全局）",
        placeholder = "例如 4",
    )
}
