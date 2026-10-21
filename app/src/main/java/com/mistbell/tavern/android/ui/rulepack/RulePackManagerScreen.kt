package com.mistbell.tavern.android.ui.rulepack

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.local.entity.RulePackEntity
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.ConfirmDeleteDialog
import com.mistbell.tavern.android.ui.components.EmptyStateView

/**
 * 规则包管理：导入 zip / 启用开关 / 删除 / 分享。
 * 规则包 = 声明式扩展包（提示词块 + 输出正则 + 宏），纯数据无代码执行。
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulePackManagerScreen(
    onBack: () -> Unit = {},
    viewModel: RulePackManagerViewModel = viewModel(),
) {
    val packs by viewModel.packs.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var pendingDelete by remember { mutableStateOf<RulePackEntity?>(null) }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let { viewModel.importPack(it) }
        }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ModernTopBar(
                title = "规则包管理",
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入规则包") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        if (packs.isEmpty()) {
            EmptyStateView(
                icon = "📦",
                title = "暂无规则包",
                subtitle = "点击右下角按钮导入 .zip 规则包\n（提示词块 / 输出正则 / 自定义宏）",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues,
            ) {
                items(packs, key = { it.id }) { pack ->
                    RulePackRow(
                        pack = pack,
                        onToggle = { viewModel.setEnabled(pack.id, !pack.enabled) },
                        onDelete = { pendingDelete = pack },
                        onShare = { viewModel.exportPack(pack.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        ConfirmDeleteDialog(
            title = "删除规则包",
            message = "确定删除规则包「${pack.name}」吗？此操作不可撤销。",
            onConfirm = {
                viewModel.deletePack(pack.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun RulePackRow(
    pack: RulePackEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pack.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (pack.enabled) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = "${pack.author} · ${pack.version}" + if (!pack.enabled) " · 已禁用" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = pack.enabled, onCheckedChange = { onToggle() })
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "分享")
        }
    }
}
