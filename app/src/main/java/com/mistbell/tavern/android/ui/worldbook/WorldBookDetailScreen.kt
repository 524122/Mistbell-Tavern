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
            FormTextArea(
                value = entryForm.content,
                onValueChange = { viewModel.updateEntryForm { copy(content = it) } },
                label = "内容",
                placeholder = "世界书条目内容...",
                minLines = 4,
            )

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

/** 启用/常量开关行 */
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
}

/** 深度（仅 @D 档显示，1-10）与触发概率（0-1）两个数字字段 */
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
                it.toIntOrNull()?.let { d -> viewModel.updateEntryForm { copy(depth = d.coerceIn(0, 10)) } }
            },
            label = "插入深度 @D (1-10)",
            placeholder = "历史倒数第D条之前",
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
}
