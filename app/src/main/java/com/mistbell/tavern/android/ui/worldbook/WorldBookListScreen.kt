package com.mistbell.tavern.android.ui.worldbook

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap
import com.mistbell.tavern.android.util.WorldBookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookListScreen(
    onBack: () -> Unit = {},
    // 可空：传入则导航外跳；为 null（默认/导航图当前用法）则在本屏内联展开条目列表。
    // 不能用 `onBookClick: (String)->Unit = {}` 再拿 `!= {}` 判断——每个 {} 都是新实例，
    // 比较恒为 true，会导致点击永远走空操作分支、进不去条目（见下方 clickable）。
    onBookClick: ((String) -> Unit)? = null,
    viewModel: WorldBookEditorViewModel = viewModel(),
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val worldBooks by viewModel.worldBooks.collectAsState()
    val selectedBookId by viewModel.selectedBookId.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val showEntryForm by viewModel.showEntryForm.collectAsState()
    val entryForm by viewModel.entryForm.collectAsState()
    val message by viewModel.message.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var showNewBookDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }
    var showDeleteBookDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteEntryDialog by remember { mutableStateOf<String?>(null) }
    var fabExpanded by remember { mutableStateOf(false) }

    val fabRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (fabExpanded) 45f else 0f,
        animationSpec =
            androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
            ),
        label = "fab_rotation",
    )

    val snackbarHostState = remember { SnackbarHostState() }

    // 导入世界书：SAF 选 JSON → WorldBookParser 解析 → 落库（参照角色导入的提示风格）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            try {
                                val jsonString =
                                    context.contentResolver.openInputStream(uri)
                                        ?.bufferedReader()?.use { it.readText() }
                                if (jsonString != null) {
                                    // 兜底书名：取文件显示名去后缀，取不到用固定文案
                                    val fallbackName =
                                        queryDisplayName(context, uri)
                                            ?.substringBeforeLast('.')
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "导入的世界书"
                                    WorldBookParser.parse(jsonString, fallbackName)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("WorldBookImport", "导入世界书失败", e)
                                null
                            }
                        }
                    if (result != null) {
                        val (book, entryList) = result
                        try {
                            withContext(Dispatchers.IO) {
                                val db = TavernApplication.instance.database
                                db.worldBookDao().upsertBook(book)
                                if (entryList.isNotEmpty()) {
                                    db.worldBookDao().upsertEntries(entryList)
                                }
                            }
                            snackbarHostState.showSnackbar(
                                "成功导入世界书：${book.name}（${entryList.size} 条条目）",
                            )
                            // 刷新列表
                            viewModel.loadFromServer()
                        } catch (e: Exception) {
                            android.util.Log.e("WorldBookImport", "保存世界书失败", e)
                            snackbarHostState.showSnackbar("导入失败: ${e.message}")
                        }
                    } else {
                        snackbarHostState.showSnackbar("导入失败：无法解析世界书文件")
                    }
                }
            }
        }

    // Back handler: if viewing entries, go back to book list; otherwise exit
    BackHandler {
        if (selectedBookId != null) viewModel.clearSelectedBook() else onBack()
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(Unit) { viewModel.loadFromServer() }

    val selectedBook = worldBooks.find { it.id == selectedBookId }
    val totalEntries = worldBooks.sumOf { it.entries.size }
    val activeEntries = worldBooks.sumOf { book -> book.entries.count { !it.disable } }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .clearFocusOnTap(),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showBackButton || selectedBookId != null) {
                            IconButton(onClick = {
                                if (selectedBookId != null) viewModel.clearSelectedBook() else onBack()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(22.dp))
                            }
                        }
                        Text(
                            text = selectedBook?.name ?: "世界书管理",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedBookId != null) {
                            TextButton(onClick = { viewModel.showNewEntryForm() }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加条目")
                            }
                        }
                        // Refresh button
                        IconButton(onClick = { viewModel.loadFromServer() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(18.dp))
                        }
                    }

                    // Search bar (only show when not in entry list view)
                    if (selectedBookId == null) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = {
                                Text("搜索世界书名称或条目")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "搜索",
                                )
                            },
                            shape = RoundedCornerShape(28.dp),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            singleLine = true,
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        floatingActionButton = {
            if (selectedBookId == null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Sub FABs - shown when expanded
                    androidx.compose.animation.AnimatedVisibility(
                        visible = fabExpanded,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Import worldbook button with label
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    shadowElevation = 2.dp,
                                ) {
                                    Text(
                                        text = "导入世界书",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        fabExpanded = false
                                        // SAF 选择 JSON 世界书文件
                                        importLauncher.launch(
                                            arrayOf("application/json", "application/octet-stream"),
                                        )
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) {
                                    Icon(
                                        Icons.Default.FileUpload,
                                        contentDescription = "导入世界书",
                                    )
                                }
                            }

                            // New worldbook button with label
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    shadowElevation = 2.dp,
                                ) {
                                    Text(
                                        text = "新建世界书",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        fabExpanded = false
                                        showNewBookDialog = true
                                    },
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "新建世界书",
                                    )
                                }
                            }
                        }
                    }

                    // Main FAB
                    FloatingActionButton(
                        onClick = { fabExpanded = !fabExpanded },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = if (fabExpanded) "收起" else "展开",
                            modifier = Modifier.rotate(fabRotation),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding(),
        ) {
            if (selectedBookId == null) {
                // Book list view
                if (worldBooks.isEmpty()) {
                    // Empty state - centered
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyStateView("📖", "暂无世界书", "点击右下角 + 创建")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(worldBooks, key = { it.id }) { book ->
                            Card(
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        val cb = onBookClick
                                        if (cb != null) {
                                            cb(book.id)
                                        } else {
                                            viewModel.selectBook(book.id)
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(book.name, fontWeight = FontWeight.Medium)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "${book.entries.size} 个条目",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                "·",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            val activeCount = book.entries.count { !it.disable }
                                            Text(
                                                "$activeCount 个启用",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showDeleteBookDialog = book.id }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Entry list view
                LazyColumn(
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
                            modifier = Modifier.fillMaxWidth(),
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
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                                    TextButton(onClick = { viewModel.showEditEntryForm(entry) }) {
                                        Text("编辑")
                                    }
                                    TextButton(onClick = { showDeleteEntryDialog = entry.id }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // New world book dialog
    if (showNewBookDialog) {
        AlertDialog(
            onDismissRequest = { showNewBookDialog = false },
            title = { Text("新建世界书") },
            text = {
                FormTextField(
                    value = newBookName,
                    onValueChange = { newBookName = it },
                    label = "名称",
                    placeholder = "世界书名称",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newBookName.isNotBlank()) {
                        viewModel.createWorldBook(newBookName)
                        newBookName = ""
                        showNewBookDialog = false
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewBookDialog = false }) { Text("取消") }
            },
        )
    }

    // Delete book confirmation
    showDeleteBookDialog?.let { bookId ->
        ConfirmDeleteDialog(
            message = "确定要删除这个世界书及其所有条目吗？",
            onConfirm = {
                viewModel.deleteWorldBook(bookId)
                showDeleteBookDialog = null
            },
            onDismiss = { showDeleteBookDialog = null },
        )
    }

    // Delete entry confirmation
    showDeleteEntryDialog?.let { entryId ->
        ConfirmDeleteDialog(
            message = "确定要删除这个条目吗？",
            onConfirm = {
                viewModel.deleteEntry(entryId)
                showDeleteEntryDialog = null
            },
            onDismiss = { showDeleteEntryDialog = null },
        )
    }

    // Entry form bottom sheet
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

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 查询 SAF 文件的显示名（用于世界书导入的书名兜底），失败返回 null */
private fun queryDisplayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}
