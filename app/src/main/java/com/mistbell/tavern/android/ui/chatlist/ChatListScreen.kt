package com.mistbell.tavern.android.ui.chatlist

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.EmptyStateView
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap

/**
 * 聊天列表（嵌入 MainScreen 的主 tab 页）。
 *
 * 顶栏统一走公共 [ModernTopBar]；多选模式下替换为多选操作栏。
 * 长按列表项弹出操作菜单（置顶/标记已读/改名/复制/导出/删除/多选），
 * 菜单与确认 dialog 内聚在 [ModernChatListItem] 中。
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态（原由 detekt 基线吸收）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel = viewModel(),
    onChatClick: (sessionId: String, characterId: String) -> Unit = { _, _ -> },
    onNewChatClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chatItems by viewModel.chatListItems.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val importSuccess by viewModel.importSuccess.collectAsState()
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsState()
    val selectedSessions by viewModel.selectedSessions.collectAsState()

    var showClearAllDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // 文件选择器 Launcher
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                viewModel.importSession(it)
            }
        }

    // 导入错误提示
    LaunchedEffect(importError) {
        importError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearImportError()
        }
    }

    // 导入成功提示
    LaunchedEffect(importSuccess) {
        importSuccess?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearImportSuccess()
        }
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isMultiSelectMode) {
                // 多选模式操作栏（自旧独立模式移植）
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
                ModernTopBar(
                    title = "会话",
                    subtitle = "继续你的故事",
                    actions = {
                        IconButton(onClick = { importLauncher.launch("application/json") }) {
                            Icon(Icons.Default.FileUpload, contentDescription = "导入会话")
                        }
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
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewChatClick,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建对话") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (chatItems.isEmpty()) {
                    item {
                        EmptyStateView(
                            icon = "💬",
                            title = "暂无对话",
                            subtitle = "点击右下角 + 开始新对话",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    // Chat list items - 使用现代化设计
                    items(
                        chatItems,
                        key = { "${it.sessionId}:${it.characterId}" },
                        contentType = { "chat-item" },
                    ) { item ->
                        ModernChatListItem(
                            item = item,
                            onClick = {
                                if (isMultiSelectMode) {
                                    viewModel.toggleSessionSelection(item.sessionId, item.characterId)
                                } else {
                                    onChatClick(item.sessionId, item.characterId)
                                }
                            },
                            isSelected = selectedSessions.contains(Pair(item.sessionId, item.characterId)),
                            isMultiSelectMode = isMultiSelectMode,
                            onEnterMultiSelect = {
                                viewModel.enterMultiSelectMode()
                                viewModel.toggleSessionSelection(item.sessionId, item.characterId)
                            },
                            onTogglePin = { viewModel.togglePin(item.sessionId, item.characterId) },
                            onMarkAsRead = { viewModel.markAsRead(item.sessionId, item.characterId) },
                            onRename = { title ->
                                viewModel.renameSession(item.sessionId, item.characterId, title)
                            },
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
}
