package com.mistbell.tavern.android.ui.character

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.util.CharacterExportFormat
import com.mistbell.tavern.android.util.CharacterExportResult
import com.mistbell.tavern.android.util.CharacterExporter
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CharacterListScreen(
    viewModel: CharacterListViewModel =
        viewModel(
            factory =
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
                ),
        ),
    onCharacterClick: (Character) -> Unit = {},
    onEditCharacter: (String) -> Unit = {},
    onNewCharacter: () -> Unit = {},
    onBack: () -> Unit = {},
    showTopBarBackButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val filteredCharacters by viewModel.filteredCharacters.collectAsState()
    val sessionCounts by viewModel.sessionCounts.collectAsState()
    val pinnedCharacterIds by viewModel.pinnedCharacterIds.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var fabExpanded by remember { mutableStateOf(false) }

    // File picker for importing characters
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                viewModel.importCharacter(context, it)
            }
        }

    val fabRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (fabExpanded) 45f else 0f,
        animationSpec =
            androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
            ),
        label = "fab_rotation",
    )

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
        topBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding(),
            ) {
                // Top app bar
                TopAppBar(
                    title = {
                        Text(
                            text = "角色",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        if (showTopBarBackButton) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                )

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = {
                        Text("搜索角色名称或设置")
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
        },
        floatingActionButton = {
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
                        // Import button with label
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
                                    text = "导入角色",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            SmallFloatingActionButton(
                                onClick = {
                                    fabExpanded = false
                                    importLauncher.launch("application/json")
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Icon(
                                    Icons.Default.FileUpload,
                                    contentDescription = "导入角色",
                                )
                            }
                        }

                        // New character button with label
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
                                    text = "新建角色",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            SmallFloatingActionButton(
                                onClick = {
                                    fabExpanded = false
                                    onNewCharacter()
                                },
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "新建角色",
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
        },
    ) { paddingValues ->
        if (filteredCharacters.isEmpty()) {
            // Empty state
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "😊",
                        fontSize = 64.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isBlank()) "还没有角色" else "没有找到角色",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (searchQuery.isBlank()) "点击+创建第一个角色" else "试试其他搜索词",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(filteredCharacters, key = { it.id }) { character ->
                    CharacterCardItem(
                        character = character,
                        isPinned = pinnedCharacterIds.contains(character.id),
                        sessionCount = sessionCounts[character.id] ?: 0,
                        onClick = { onCharacterClick(character) },
                        onEdit = { onEditCharacter(character.id) },
                        onDelete = { viewModel.deleteCharacter(character.id) },
                        onTogglePin = { viewModel.togglePin(character.id) },
                        onCopy = { viewModel.copyCharacter(context, character) },
                        onExport = { format, fileName, onDone ->
                            viewModel.exportCharacter(
                                context = context,
                                character = character,
                                format = format,
                                fileName = fileName,
                                onComplete = onDone,
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CharacterCardItem(
    character: Character,
    isPinned: Boolean = false,
    sessionCount: Int = 0,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: (CharacterExportFormat, String, (CharacterExportResult?) -> Unit) -> Unit,
    onTogglePin: () -> Unit = {},
    onCopy: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showBottomSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(CharacterExportFormat.JSON) }
    var exportFileName by remember { mutableStateOf("") }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showBottomSheet = true },
                ),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Avatar
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(parseCharacterColor(character.color)),
                contentAlignment = Alignment.Center,
            ) {
                if (character.avatarData.isNotBlank()) {
                    // 手动解析data URI并显示
                    val bitmap =
                        remember(character.avatarData) {
                            com.mistbell.tavern.android.util.ImageUtils.dataUriToBitmap(character.avatarData)
                        }

                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = character.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        // Fallback to emoji if bitmap parsing fails
                        Text(
                            text = getCharacterEmoji(character.name),
                            fontSize = 28.sp,
                        )
                    }
                } else {
                    Text(
                        text = getCharacterEmoji(character.name),
                        fontSize = 28.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isPinned) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$sessionCount 对话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (character.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = character.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Tags from personality
                if (character.personality.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val tags = extractPersonalityTags(character.personality)
                        items(tags.take(3)) { tag ->
                            SuggestionChip(
                                onClick = { },
                                label = {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors =
                                    SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                            )
                        }
                    }
                }
            }

            // Long-press bottom sheet
            if (showBottomSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showBottomSheet = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 32.dp),
                    ) {
                        // Pin/Unpin
                        ListItem(
                            headlineContent = { Text(if (isPinned) "取消置顶" else "置顶") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    showBottomSheet = false
                                    onTogglePin()
                                },
                        )

                        // Edit
                        ListItem(
                            headlineContent = { Text("编辑") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    showBottomSheet = false
                                    onEdit()
                                },
                        )

                        // Copy
                        ListItem(
                            headlineContent = { Text("复制") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    showBottomSheet = false
                                    onCopy()
                                },
                        )

                        // Export
                        ListItem(
                            headlineContent = { Text("导出") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    showBottomSheet = false
                                    exportFormat = CharacterExportFormat.JSON
                                    exportFileName =
                                        CharacterExporter.buildFileName(
                                            character.name,
                                            character.id,
                                            CharacterExportFormat.JSON.extension,
                                        )
                                    showExportDialog = true
                                },
                        )

                        HorizontalDivider()

                        // Delete
                        ListItem(
                            headlineContent = {
                                Text(
                                    "删除",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    showBottomSheet = false
                                    showDeleteDialog = true
                                },
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除角色") },
            text = { Text("确定删除「${character.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出角色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "格式",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CharacterExportFormat.entries.forEach { format ->
                            FilterChip(
                                selected = exportFormat == format,
                                onClick = {
                                    exportFormat = format
                                    exportFileName =
                                        CharacterExporter.buildFileName(
                                            character.name,
                                            character.id,
                                            format.extension,
                                        )
                                },
                                label = { Text(format.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = exportFileName,
                        onValueChange = { exportFileName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存到：${CharacterExporter.displayLocation(exportFileName)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalName =
                            exportFileName.trim().ifBlank {
                                CharacterExporter.buildFileName(character.name, character.id, exportFormat.extension)
                            }
                        showExportDialog = false
                        onExport(exportFormat, finalName) { }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun parseCharacterColor(colorString: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(colorString))
    } catch (e: Exception) {
        Color(0xFF6750A4) // Material primary as fallback
    }
}

private fun getCharacterEmoji(name: String): String {
    val emojiMap =
        mapOf(
            "米拉" to "❄️",
            "个男人的女性修仙界" to "🌍",
            "Seraphina" to "👩",
            "酒馆旧梦" to "🍷",
            "奇幻世界观测试" to "🌏",
            "米拉・新对话" to "✨",
            "咖啡店里的雨夜" to "☔",
            "赛博城边缘" to "🏙️",
            "空白角色" to "✨",
            "世界观察者" to "🌏",
        )

    return emojiMap[name] ?: run {
        val hash = name.hashCode()
        val emojis =
            listOf(
                "🌟", "🎭", "🎨", "🎪", "🎯", "🎲", "🎵", "🎸", "🎹", "🎺",
                "🎻", "🎼", "🎾", "🎿", "🏀", "🏈", "🏉", "🏊", "🏋️", "🌸",
                "🌺", "🌻", "🌼", "🌷", "🌹", "🥀", "🌾", "🍀", "🍁", "🍂",
            )
        emojis[hash.absoluteValue % emojis.size]
    }
}

private fun extractPersonalityTags(personality: String): List<String> {
    return personality
        .split(Regex("[,，、；;]"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
