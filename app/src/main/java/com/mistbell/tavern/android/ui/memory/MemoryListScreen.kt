package com.mistbell.tavern.android.ui.memory

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.theme.AccentBlue
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap

private val LAYER_LABELS = mapOf(
    "profile" to "档案", "relationship" to "关系", "episodic" to "事件", "core" to "核心"
)
private val TYPE_LABELS = mapOf(
    "note" to "笔记", "fact" to "事实", "identity" to "身份", "preference" to "偏好",
    "relationship" to "关系", "goal" to "目标", "event" to "事件", "emotion" to "情绪", "core" to "核心"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryListScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = viewModel()
) {
    val memories by viewModel.filteredMemories.collectAsState()
    val allMemories by viewModel.memories.collectAsState()
    val layerFilter by viewModel.layerFilter.collectAsState()
    val showForm by viewModel.showForm.collectAsState()
    val form by viewModel.form.collectAsState()
    val isBackfilling by viewModel.isBackfilling.collectAsState()
    val message by viewModel.message.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    // Initialize with default values
    LaunchedEffect(Unit) { viewModel.init("local-user", "mira") }

    val avgImportance = if (allMemories.isNotEmpty()) allMemories.map { it.importance }.average() else 0.0
    val layerCounts = allMemories.groupBy { it.layer }.mapValues { it.value.size }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .clearFocusOnTap(),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(22.dp))
                        }
                        Text("记忆管理", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.loadFromServer() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(18.dp))
                        }
                        TextButton(onClick = { viewModel.backfill() }, enabled = !isBackfilling) {
                            if (isBackfilling) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("回填")
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showNewForm() },
                containerColor = AccentBlue,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, "新建记忆") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Stats
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("总数", "${allMemories.size}", Modifier.weight(1f))
                    StatCard("平均重要度", "%.1f".format(avgImportance), Modifier.weight(1f))
                    StatCard("活跃", "${allMemories.count { it.status == "active" }}", Modifier.weight(1f))
                }
            }

            // Layer filter chips
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = layerFilter == null,
                            onClick = { viewModel.setLayerFilter(null) },
                            label = { Text("全部") }
                        )
                    }
                    LAYER_LABELS.forEach { (key, label) ->
                        item {
                            FilterChip(
                                selected = layerFilter == key,
                                onClick = { viewModel.setLayerFilter(if (layerFilter == key) null else key) },
                                label = { Text("$label (${layerCounts[key] ?: 0})") }
                            )
                        }
                    }
                }
            }

            if (memories.isEmpty()) {
                item { EmptyStateView("🧠", "暂无记忆", "点击右下角 + 或使用回填功能") }
            }

            items(memories, key = { it.id }) { memory ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            viewModel.deleteMemory(memory.id)
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        val color by animateColorAsState(
                            if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.surface,
                            label = "swipe_color"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color, RoundedCornerShape(10.dp))
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.onError)
                        }
                    },
                    enableDismissFromStartToEnd = false
                ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    memory.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { showDeleteDialog = memory.id }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text(LAYER_LABELS[memory.layer] ?: memory.layer, style = MaterialTheme.typography.labelSmall) }
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text(TYPE_LABELS[memory.type] ?: memory.type, style = MaterialTheme.typography.labelSmall) }
                            )
                            Text(
                                "重要度: %.1f".format(memory.importance),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                        // Tap to edit
                        TextButton(
                            onClick = { viewModel.showEditForm(memory) },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("编辑") }
                    }
                }
            }
        }
    }
    }

    // Delete confirmation
    showDeleteDialog?.let { memoryId ->
        ConfirmDeleteDialog(
            message = "确定要删除这条记忆吗？",
            onConfirm = { viewModel.deleteMemory(memoryId); showDeleteDialog = null },
            onDismiss = { showDeleteDialog = null }
        )
    }

    // Memory form bottom sheet
    if (showForm) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.updateForm { MemoryForm() }; /* close handled by _showForm */ },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (viewModel.editingMemoryId.value != null) "编辑记忆" else "新建记忆",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                FormTextArea(
                    value = form.content,
                    onValueChange = { viewModel.updateForm { copy(content = it) } },
                    label = "内容",
                    placeholder = "记忆内容...",
                    minLines = 3
                )

                // Layer dropdown
                var layerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = layerExpanded, onExpandedChange = { layerExpanded = it }) {
                    OutlinedTextField(
                        value = LAYER_LABELS[form.layer] ?: form.layer,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("层级") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = layerExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(expanded = layerExpanded, onDismissRequest = { layerExpanded = false }) {
                        LAYER_LABELS.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                viewModel.updateForm { copy(layer = key) }; layerExpanded = false
                            })
                        }
                    }
                }

                // Type dropdown
                var typeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = TYPE_LABELS[form.type] ?: form.type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        TYPE_LABELS.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                viewModel.updateForm { copy(type = key) }; typeExpanded = false
                            })
                        }
                    }
                }

                // Importance slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("重要度", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("%.1f".format(form.importance), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = form.importance.toFloat(),
                        onValueChange = { viewModel.updateForm { copy(importance = it.toDouble()) } },
                        valueRange = 0f..1f,
                        steps = 9,
                        colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
                    )
                }

                // Stability slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("稳定性", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("%.1f".format(form.stability), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = form.stability.toFloat(),
                        onValueChange = { viewModel.updateForm { copy(stability = it.toDouble()) } },
                        valueRange = 0f..1f,
                        steps = 9,
                        colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
                    )
                }

                // Subject-Relation-Object triple
                Text("三元组（可选）", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                FormTextField(
                    value = form.subject,
                    onValueChange = { viewModel.updateForm { copy(subject = it) } },
                    label = "主体",
                    placeholder = "例如：Alice"
                )

                FormTextField(
                    value = form.relation,
                    onValueChange = { viewModel.updateForm { copy(relation = it) } },
                    label = "关系",
                    placeholder = "例如：喜欢"
                )

                FormTextField(
                    value = form.objectValue,
                    onValueChange = { viewModel.updateForm { copy(objectValue = it) } },
                    label = "客体",
                    placeholder = "例如：咖啡"
                )

                // Tags
                var tagInput by remember { mutableStateOf("") }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { tagInput = it },
                            label = { Text("标签") },
                            placeholder = { Text("输入标签后按回车") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            trailingIcon = {
                                if (tagInput.isNotBlank()) {
                                    IconButton(onClick = {
                                        viewModel.updateForm { copy(tags = tags + tagInput.trim()) }
                                        tagInput = ""
                                    }) {
                                        Icon(Icons.Default.Add, "添加标签", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        )
                    }
                    if (form.tags.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(form.tags) { tag ->
                                FilterChip(
                                    selected = false,
                                    onClick = { viewModel.updateForm { copy(tags = tags - tag) } },
                                    label = { Text(tag) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                                    }
                                )
                            }
                        }
                    }
                }

                // Aliases
                var aliasInput by remember { mutableStateOf("") }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = aliasInput,
                            onValueChange = { aliasInput = it },
                            label = { Text("别名") },
                            placeholder = { Text("输入别名后按回车") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            trailingIcon = {
                                if (aliasInput.isNotBlank()) {
                                    IconButton(onClick = {
                                        viewModel.updateForm { copy(aliases = aliases + aliasInput.trim()) }
                                        aliasInput = ""
                                    }) {
                                        Icon(Icons.Default.Add, "添加别名", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        )
                    }
                    if (form.aliases.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(form.aliases) { alias ->
                                FilterChip(
                                    selected = false,
                                    onClick = { viewModel.updateForm { copy(aliases = aliases - alias) } },
                                    label = { Text(alias) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                                    }
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { viewModel.saveMemory() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("保存") }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AccentBlue)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
