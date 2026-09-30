package com.mistbell.tavern.android.ui.character

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.EmptyStateView
import com.mistbell.tavern.android.ui.components.SearchBar

@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态（原由 detekt 基线吸收）
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
                        // Batch import button with label（酒馆数据文件夹：角色卡 PNG + 世界书 JSON 一批导入）
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
                                    text = "批量导入（酒馆文件夹）",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            SmallFloatingActionButton(
                                onClick = {
                                    fabExpanded = false
                                    if (!isBatchImporting) folderImportLauncher.launch(null)
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = "批量导入（酒馆文件夹）",
                                )
                            }
                        }

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
                                    // "*/*"：JSON 卡与 PNG 埋卡都要可选；MIME 过滤交给导入器双路嗅探
                                    importLauncher.launch("*/*")
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
                items(filteredCharacters, key = { it.id }) { character ->
                    ModernCharacterCard(
                        character = character,
                        onClick = { onCharacterClick(character) },
                        onLongClick = { /* 待实现：显示操作菜单 */ },
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
