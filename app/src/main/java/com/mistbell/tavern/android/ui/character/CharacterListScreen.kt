package com.mistbell.tavern.android.ui.character

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.EmptyStateView
import com.mistbell.tavern.android.ui.components.SearchBar
import com.mistbell.tavern.android.util.CharacterExportFormat
import com.mistbell.tavern.android.util.CharacterExporter

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // Compose 屏幕级组件的既有形态
@OptIn(ExperimentalMaterial3Api::class)
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
    var actionCharacter by remember { mutableStateOf<Character?>(null) }
    var deleteCharacter by remember { mutableStateOf<Character?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // File picker for importing characters
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                viewModel.importCharacter(context, it)
            }
        }

    // 批量导入：选酒馆数据文件夹 → 递归扫描角色卡+世界书（已存在的按新版本更新，不重复导入）
    val isBatchImporting by viewModel.isBatchImporting.collectAsState()
    val batchImportReport by viewModel.batchImportReport.collectAsState()
    val folderImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            uri?.let {
                // 取持久读授权：导入中途切后台/Activity 重建不丢文档树访问权
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.importFromFolder(it)
            }
        }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    actionCharacter?.let { character ->
        ModalBottomSheet(
            onDismissRequest = { actionCharacter = null },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                CharacterActionItem("开始对话", Icons.Default.Chat) {
                    actionCharacter = null
                    onCharacterClick(character)
                }
                CharacterActionItem("编辑角色", Icons.Default.Edit) {
                    actionCharacter = null
                    onEditCharacter(character.id)
                }
                CharacterActionItem("置顶 / 取消置顶", Icons.Default.PushPin) {
                    viewModel.togglePin(character.id)
                    actionCharacter = null
                }
                CharacterActionItem("复制角色信息", Icons.Default.ContentCopy) {
                    viewModel.copyCharacter(context, character)
                    actionCharacter = null
                }
                CharacterActionItem("导出角色卡（JSON）", Icons.Default.FileDownload) {
                    viewModel.exportCharacter(
                        context = context,
                        character = character,
                        format = CharacterExportFormat.JSON,
                        fileName =
                            CharacterExporter.buildFileName(
                                character.name,
                                character.id,
                                CharacterExportFormat.JSON.extension,
                            ),
                    ) { result ->
                        result?.let { Toast.makeText(context, "已保存到 ${it.location}", Toast.LENGTH_SHORT).show() }
                    }
                    actionCharacter = null
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
                CharacterActionItem("删除角色", Icons.Default.Delete, destructive = true) {
                    actionCharacter = null
                    deleteCharacter = character
                }
            }
        }
    }

    deleteCharacter?.let { character ->
        AlertDialog(
            onDismissRequest = { deleteCharacter = null },
            title = { Text("删除角色") },
            text = { Text("确定删除“${character.name}”吗？相关会话也可能无法继续使用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCharacter(character.id)
                        deleteCharacter = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteCharacter = null }) { Text("取消") } },
        )
    }

    // 批量导入结果报告：计数 + 可滚动明细（同名/跳过/失败）
    batchImportReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewModel.clearBatchImportReport() },
            title = { Text("批量导入完成") },
            text = {
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("角色：新增 ${report.createdCharacters}，更新 ${report.updatedCharacters}")
                    Text("世界书：新增 ${report.createdBooks}，更新 ${report.updatedBooks}")
                    if (report.skippedSprites > 0) {
                        Text("跳过无埋卡图片 ${report.skippedSprites} 张（立绘/表情差分）")
                    }
                    SyncReportSection("同名跳过（先到先得）", report.duplicateNames)
                    SyncReportSection("跳过文件", report.skippedFiles)
                    SyncReportSection("失败文件", report.failedFiles)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearBatchImportReport() }) {
                    Text("确定")
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ModernTopBar(
                    title = "角色",
                    subtitle = "管理你的角色",
                    onBack = if (showTopBarBackButton) onBack else null,
                    actions = {
                        IconButton(onClick = { importLauncher.launch("*/*") }) {
                            Icon(Icons.Default.FileUpload, contentDescription = "导入角色")
                        }
                        IconButton(
                            onClick = { if (!isBatchImporting) folderImportLauncher.launch(null) },
                            enabled = !isBatchImporting,
                        ) {
                            if (isBatchImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Folder, contentDescription = "批量导入")
                            }
                        }
                    },
                )
                SearchBar(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = "搜索角色名称或设置",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewCharacter,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建角色") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { paddingValues ->
        if (filteredCharacters.isEmpty()) {
            EmptyStateView(
                icon = "🎭",
                title = if (searchQuery.isBlank()) "还没有角色" else "没有找到角色",
                subtitle = if (searchQuery.isBlank()) "点击右下角 + 创建第一个角色" else "试试其他搜索词",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            )
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(filteredCharacters, key = { it.id }, contentType = { "character-card" }) { character ->
                    ModernCharacterCard(
                        character = character,
                        onClick = { onCharacterClick(character) },
                        onLongClick = { actionCharacter = character },
                        onMenuClick = { actionCharacter = character },
                        isSelected = false,
                    )
                }
            }
        }
    }
}

/** 同步报告明细小节：空列表不渲染；超 50 条截断并提示总数 */
@Composable
private fun SyncReportSection(
    title: String,
    lines: List<String>,
) {
    if (lines.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    lines.take(50).forEach { line ->
        Text(
            text = "• $line",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (lines.size > 50) {
        Text(
            text = "…共 ${lines.size} 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun CharacterActionItem(
    title: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
