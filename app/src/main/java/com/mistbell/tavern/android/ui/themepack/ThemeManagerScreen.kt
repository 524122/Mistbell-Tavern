package com.mistbell.tavern.android.ui.themepack

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.theme.ThemeSupport
import com.mistbell.tavern.android.ui.components.ConfirmDeleteDialog
import com.mistbell.tavern.android.ui.components.EmptyStateView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeManagerScreen(
    onBack: () -> Unit = {},
    viewModel: ThemeManagerViewModel = viewModel()
) {
    val context = LocalContext.current
    val packs by viewModel.packs.collectAsState()
    val activeThemeId by viewModel.activeThemeId.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 待删除确认的主题包
    var pendingDelete by remember { mutableStateOf<ThemePackEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 一次性读取，不需要持久授权
        uri?.let { viewModel.importPack(it) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("主题管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入主题") }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (packs.isEmpty()) {
            EmptyStateView(
                icon = "🎨",
                title = "暂无主题包",
                subtitle = "点击右下角按钮导入 .zip 主题包",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues
            ) {
                // 默认（不使用主题）
                item {
                    val selected = activeThemeId.isNullOrBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setActive(null) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "默认（不使用主题）",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        RadioButton(selected = selected, onClick = { viewModel.setActive(null) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                items(packs, key = { it.id }) { pack ->
                    val selected = activeThemeId == pack.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setActive(pack.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pack.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "${pack.author} · ${pack.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                ColorSwatchPreview(tokensJson = pack.tokensJson)
                            }
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.setActive(pack.id) }
                            )
                            IconButton(onClick = { pendingDelete = pack }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            IconButton(onClick = { viewModel.exportPack(pack.id) }) {
                                Icon(Icons.Default.Share, contentDescription = "分享")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        ConfirmDeleteDialog(
            title = "删除主题",
            message = "确定删除主题「${pack.name}」吗？此操作不可撤销。",
            onConfirm = {
                viewModel.deletePack(pack.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// 从 tokensJson 解析非空颜色并渲染 16dp 圆形色块
@Composable
private fun ColorSwatchPreview(tokensJson: String) {
    val tokens = remember(tokensJson) { ThemeSupport.parseTokens(tokensJson) }
    val colors = listOfNotNull(
        tokens?.colors?.primary,
        tokens?.colors?.userBubble,
        tokens?.colors?.assistantBubble,
        tokens?.colors?.background,
        tokens?.colors?.surface
    )
    if (tokens == null) {
        Text(
            text = "无效主题",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    Row(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        colors.forEach { hex ->
            val color = ThemeSupport.parseHexColor(hex)
            if (color != null) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}
