package com.mistbell.tavern.android.ui.worldbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
        ModalBottomSheet(
            onDismissRequest = { viewModel.hideEntryForm() },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
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

                // Insert position
                var positionExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = positionExpanded, onExpandedChange = { positionExpanded = it }) {
                    OutlinedTextField(
                        value = if (entryForm.insertPosition == "before_prompt") "提示词前" else "提示词后",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("插入位置") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = positionExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                    )
                    ExposedDropdownMenu(expanded = positionExpanded, onDismissRequest = { positionExpanded = false }) {
                        DropdownMenuItem(text = { Text("提示词前") }, onClick = {
                            viewModel.updateEntryForm { copy(insertPosition = "before_prompt") }
                            positionExpanded = false
                        })
                        DropdownMenuItem(text = { Text("提示词后") }, onClick = {
                            viewModel.updateEntryForm { copy(insertPosition = "after_prompt") }
                            positionExpanded = false
                        })
                    }
                }

                // Depth
                var depthText by remember(entryForm.depth) { mutableStateOf(entryForm.depth.toString()) }
                FormTextField(
                    value = depthText,
                    onValueChange = {
                        depthText = it
                        it.toIntOrNull()?.let { d -> viewModel.updateEntryForm { copy(depth = d.coerceIn(1, 10)) } }
                    },
                    label = "深度 (1-10)",
                    placeholder = "1",
                )

                // Toggles
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

                Button(
                    onClick = { viewModel.saveEntry() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("保存") }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
