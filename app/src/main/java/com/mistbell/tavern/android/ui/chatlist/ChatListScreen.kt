package com.mistbell.tavern.android.ui.chatlist

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.chat.CompositeCharacterAvatar
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap
import com.mistbell.tavern.android.util.SessionExportFormat
import com.mistbell.tavern.android.util.SessionExporter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel = viewModel(),
    onChatClick: (sessionId: String, characterId: String) -> Unit = { _, _ -> },
    onNewChatClick: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {},
    showBottomBar: Boolean = true,
    showTopBar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val chatItems by viewModel.chatListItems.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsState()
    val selectedSessions by viewModel.selectedSessions.collectAsState()

    var showClearAllDialog by remember { mutableStateOf(false) }
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

    if (!showTopBar && !showBottomBar) {
        // Embedded in MainScreen - just show content
        Scaffold(
            modifier =
                modifier
                    .fillMaxSize()
                    .clearFocusOnTap()
                    .statusBarsPadding(),
            containerColor = MaterialTheme.colorScheme.background,
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
                            // Import chat button with label
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
                                        text = "导入对话",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        fabExpanded = false
                                        // TODO: 导入对话
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) {
                                    Icon(
                                        Icons.Default.FileUpload,
                                        contentDescription = "导入对话",
                                    )
                                }
                            }

                            // New chat button with label
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
                                        text = "新建对话",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        fabExpanded = false
                                        onNewChatClick()
                                    },
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "新建对话",
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
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // Fixed main title with menu
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "聊天列表",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "菜单",
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("清空所有对话", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showClearAllDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteSweep,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                }

                // Scrollable content
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    // Section title
                    item {
                        Text(
                            text = "最近聊天",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    // Chat list items
                    items(chatItems, key = { it.sessionId }) { item ->
                        ChatListItemMaterial(
                            item = item,
                            onClick = { onChatClick(item.sessionId, item.characterId) },
                            onTogglePin = { viewModel.togglePin(item.sessionId, item.characterId) },
                            onMarkAsRead = { viewModel.markAsRead(item.sessionId, item.characterId) },
                            onRename = { title -> viewModel.renameSession(item.sessionId, item.characterId, title) },
                            onCopy = { viewModel.copySession(context, item.sessionId, item.characterId) },
                            onDelete = { viewModel.deleteSession(item.sessionId, item.characterId) },
                            onExport = { format, fileName, onDone ->
                                viewModel.exportSession(
                                    context = context,
                                    sessionId = item.sessionId,
                                    characterId = item.characterId,
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

        // Clear all dialog
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                title = { Text("清空所有对话") },
                text = { Text("确定要删除所有聊天记录吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearAllDialog = false
                            viewModel.deleteAllSessions()
                        },
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }

        return
    }

    // Standalone mode with full Scaffold

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
        topBar = {
            if (showTopBar) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .statusBarsPadding(),
                ) {
                    // Top app bar with search or multi-select toolbar
                    if (isMultiSelectMode) {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "${selectedSessions.size} 已选择",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { viewModel.exitMultiSelectMode() }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "退出",
                                    )
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { viewModel.selectAllSessions() },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SelectAll,
                                        contentDescription = "全选",
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.exportSelectedSessions(context) { uris ->
                                            if (uris.isNotEmpty()) {
                                                val shareIntent =
                                                    android.content.Intent().apply {
                                                        action = android.content.Intent.ACTION_SEND_MULTIPLE
                                                        type = "application/json"
                                                        putParcelableArrayListExtra(
                                                            android.content.Intent.EXTRA_STREAM,
                                                            ArrayList(uris),
                                                        )
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                context.startActivity(
                                                    android.content.Intent.createChooser(shareIntent, "导出会话"),
                                                )
                                            }
                                        }
                                    },
                                    enabled = selectedSessions.isNotEmpty(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FileUpload,
                                        contentDescription = "导出",
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deleteSelectedSessions() },
                                    enabled = selectedSessions.isNotEmpty(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint =
                                            if (selectedSessions.isNotEmpty()) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                }
                            },
                            colors =
                                TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "暮铃",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            colors =
                                TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                        )
                    }

                    // Search bar
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { viewModel.updateSearchQuery(it) },
                        onSearch = { },
                        active = false,
                        onActiveChange = { },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = {
                            Text("搜索聊天或角色")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                            )
                        },
                    ) { }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建聊天",
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = {
                            viewModel.selectTab(0)
                            onTabSelected(0)
                        },
                        icon = {
                            Icon(Icons.Default.Chat, contentDescription = null)
                        },
                        label = {
                            Text("聊天")
                        },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = {
                            viewModel.selectTab(1)
                            onTabSelected(1)
                        },
                        icon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                        label = {
                            Text("角色")
                        },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = {
                            viewModel.selectTab(2)
                            onTabSelected(2)
                        },
                        icon = {
                            Icon(Icons.Default.Book, contentDescription = null)
                        },
                        label = {
                            Text("世界书")
                        },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = {
                            viewModel.selectTab(3)
                            onTabSelected(3)
                        },
                        icon = {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        },
                        label = {
                            Text("设置")
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (showTopBar) {
                            Modifier.padding(
                                paddingValues,
                            )
                        } else {
                            Modifier.padding(bottom = paddingValues.calculateBottomPadding())
                        },
                    )
                    .then(if (!showTopBar) Modifier.statusBarsPadding() else Modifier),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Section header
            item {
                Text(
                    text = "最近聊天",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Chat list items
            items(chatItems, key = { it.sessionId }) { item ->
                ChatListItemMaterial(
                    item = item,
                    isMultiSelectMode = isMultiSelectMode,
                    isSelected = selectedSessions.contains(Pair(item.sessionId, item.characterId)),
                    onClick = {
                        if (isMultiSelectMode) {
                            viewModel.toggleSessionSelection(item.sessionId, item.characterId)
                        } else {
                            onChatClick(item.sessionId, item.characterId)
                        }
                    },
                    onLongClick = {
                        if (!isMultiSelectMode) {
                            viewModel.enterMultiSelectMode()
                            viewModel.toggleSessionSelection(item.sessionId, item.characterId)
                        }
                    },
                    onTogglePin = { viewModel.togglePin(item.sessionId, item.characterId) },
                    onMarkAsRead = { viewModel.markAsRead(item.sessionId, item.characterId) },
                    onRename = { title -> viewModel.renameSession(item.sessionId, item.characterId, title) },
                    onCopy = { viewModel.copySession(context, item.sessionId, item.characterId) },
                    onDelete = { viewModel.deleteSession(item.sessionId, item.characterId) },
                    onExport = { format, fileName, onDone ->
                        viewModel.exportSession(
                            context = context,
                            sessionId = item.sessionId,
                            characterId = item.characterId,
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatListItemMaterial(
    item: ChatListItem,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onMarkAsRead: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
    onExport: (SessionExportFormat, String, (com.mistbell.tavern.android.util.SessionExportResult?) -> Unit) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    var showBottomSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFileName by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (!isMultiSelectMode) {
                            showBottomSheet = true // 显示底部抽屉菜单
                        }
                    },
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Checkbox in multi-select mode
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 12.dp),
                )
            }

            CompositeCharacterAvatar(
                characters = item.participantCharacters,
                modifier = Modifier.size(56.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        // Pinned icon
                        if (item.isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "置顶",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = item.sessionTitle.ifBlank { item.characterName },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Muted icon
                        if (item.isMuted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.VolumeOff,
                                contentDescription = "免打扰",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = item.lastMessageTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Show sender label if available
                    val displayMessage =
                        if (item.lastMessageSender.isNotBlank()) {
                            "${item.lastMessageSender}: ${item.lastMessage}"
                        } else {
                            item.lastMessage
                        }

                    Text(
                        text = displayMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.unreadCount > 0 && !item.isMuted) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ) {
                            Text(
                                text = item.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        // Long-press bottom sheet (only in normal mode)
        if (showBottomSheet && !isMultiSelectMode) {
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
                        headlineContent = { Text(if (item.isPinned) "取消置顶" else "置顶") },
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

                    // Mark as read
                    if (item.unreadCount > 0) {
                        ListItem(
                            headlineContent = { Text("标记为已读") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.DoneAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    showBottomSheet = false
                                    onMarkAsRead()
                                },
                        )
                    }

                    // Rename
                    ListItem(
                        headlineContent = { Text("改名") },
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
                                newName = item.sessionTitle.ifBlank { item.characterName }
                                showRenameDialog = true
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
                                Icons.Outlined.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                showBottomSheet = false
                                exportFileName =
                                    SessionExporter.buildFileName(
                                        item.sessionTitle.ifBlank { item.characterName },
                                        item.sessionId,
                                        SessionExportFormat.JSON.extension,
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

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("改名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
                    placeholder = { Text(item.characterName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        onRename(newName)
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "格式：JSON",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = exportFileName,
                        onValueChange = { exportFileName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存到：${SessionExporter.displayLocation(exportFileName)}",
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
                                SessionExporter.buildFileName(
                                    item.sessionTitle.ifBlank { item.characterName },
                                    item.sessionId,
                                    SessionExportFormat.JSON.extension,
                                )
                            }
                        showExportDialog = false
                        onExport(SessionExportFormat.JSON, finalName) { result ->
                            result?.let {
                                Toast.makeText(context, "已保存到 ${it.location}", Toast.LENGTH_SHORT).show()
                            }
                        }
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

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除") },
            text = { Text("确定要删除这个会话吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
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
}
