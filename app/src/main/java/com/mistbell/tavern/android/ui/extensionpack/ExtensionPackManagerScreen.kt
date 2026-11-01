package com.mistbell.tavern.android.ui.extensionpack

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.extensionpack.PermissionCatalog
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.repository.ExtensionPackRepository
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.ConfirmDeleteDialog
import com.mistbell.tavern.android.ui.components.EmptyStateView

/**
 * 扩展包管理（P1 管理平面 + P2 运行时接线）：导入 zip / 权限清单确认 / 启停 / 删除 / 覆盖安装重载。
 *
 * P2 起启用包的 webviewPreload（门面脚本）注入每个卡片 WebView——官方内置的
 * tavernhelper-compat 即经此页启停；宿主钩子执行仍在 P3。未确认权限的包开关禁用并显示
 * 「未授权」态；启用状态落 Room 跨重启持久；同 provides 名互斥（启用自动停用竞争包）。
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionPackManagerScreen(
    onBack: () -> Unit = {},
    viewModel: ExtensionPackManagerViewModel = viewModel(),
) {
    val packs by viewModel.packs.collectAsState()
    val message by viewModel.message.collectAsState()
    val pendingImport by viewModel.pendingImport.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            // 失败类原因必须让用户来得及读完（E2E 实测：默认 4s 短时 Snackbar 连测试取证都
            // 容易扑空，正常用户在 SAF 返回后更易错过拒绝原因）——失败用 10s 长时展示
            val isFailure =
                it.startsWith("导入失败") || it.startsWith("删除失败") || it.startsWith("切换启用状态失败")
            val duration = if (isFailure) SnackbarDuration.Long else SnackbarDuration.Short
            snackbarHostState.showSnackbar(it, duration = duration)
            viewModel.clearMessage()
        }
    }

    var pendingDelete by remember { mutableStateOf<ExtensionPackEntity?>(null) }
    var detailPack by remember { mutableStateOf<ExtensionPackEntity?>(null) }

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
                title = "扩展包管理",
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
                text = { Text("导入扩展包") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        if (packs.isEmpty()) {
            EmptyStateView(
                icon = "🧩",
                title = "暂无扩展包",
                subtitle =
                    "点击右下角按钮导入 .zip 扩展包\n（带权限声明与门面/钩子脚本的可安装制品）\n\n" +
                        "官方内置包随应用分发并在启动时自动恢复；\n导入扩展包的权限确认与启停立即影响卡片界面",
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
                item(key = "notice") { RuntimeNoticeCard() }
                items(packs, key = { it.id }) { pack ->
                    ExtensionPackRow(
                        pack = pack,
                        onToggle = { viewModel.setEnabled(pack.id, !pack.enabled) },
                        onDelete = { pendingDelete = pack },
                        onShowDetail = { detailPack = pack },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        ConfirmDeleteDialog(
            title = "删除扩展包",
            message = "确定删除扩展包「${pack.name}」吗？包内容与授权记录将一并清除，此操作不可撤销。",
            onConfirm = {
                viewModel.deletePack(pack.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    detailPack?.let { pack ->
        PermissionDetailDialog(
            pack = pack,
            onDismiss = { detailPack = null },
        )
    }

    // 权限清单确认（对标酒馆安装第三方扩展的风险确认，升级为逐条权限 + JS 制品提示形态）：
    // 清单展示的是完整新权限集——覆盖安装权限集扩大时，本次确认即新清单的再确认
    pendingImport?.let { preview ->
        ImportConfirmDialog(
            preview = preview,
            onConfirm = { viewModel.confirmImport() },
            onDismiss = { viewModel.dismissImport() },
        )
    }
}

/** 运行时说明文案（P2 起门面预注入已生效）：启用包的 webviewPreload 注入每个卡片 WebView */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun RuntimeNoticeCard() {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text =
                "启用扩展包的门面脚本（WebView 预注入）已随启停生效——停用内置的「TavernHelper 兼容层」" +
                    "后卡片门面消失、部分卡片界面将不工作；宿主钩子执行将在后续版本启用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ExtensionPackRow(
    pack: ExtensionPackEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onShowDetail: () -> Unit,
) {
    val permissions = ExtensionPackStore.decodePermissions(pack.permissionsJson)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onShowDetail)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pack.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (pack.enabled) FontWeight.Bold else FontWeight.Normal,
            )
            RowSubtitle(pack = pack)
            if (permissions.isNotEmpty()) {
                PermissionBadgesRow(permissions)
            }
            if (pack.hasPreload || pack.hasHooks) {
                ScriptIndicatorsText(pack)
            }
        }
        // 未授权：开关禁用（数据层同样拦截），先确认权限才能启用
        Switch(
            checked = pack.enabled,
            onCheckedChange = { onToggle() },
            enabled = pack.permissionsConfirmed,
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 行副标题：作者/版本/顺序 + 状态后缀；未授权态整行标红，内置包带「内置」标记 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun RowSubtitle(pack: ExtensionPackEntity) {
    val suffix =
        when {
            !pack.permissionsConfirmed -> " · 未授权"
            !pack.enabled -> " · 已禁用"
            else -> ""
        }
    val builtin = if (pack.isBuiltin) " · 内置" else ""
    Text(
        text = "${pack.author} · v${pack.version} · 顺序 ${pack.loadingOrder}$builtin$suffix",
        style = MaterialTheme.typography.bodySmall,
        color =
            if (!pack.permissionsConfirmed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/** 权限徽标行：中文短名（目录外权限兜底显示原始声明值），超出 [PERMISSION_BADGE_LIMIT] 折叠 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun PermissionBadgesRow(permissions: List<String>) {
    Row(
        modifier =
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val shown = permissions.take(PERMISSION_BADGE_LIMIT)
        val badges =
            shown.map { PermissionCatalog.resolve(it)?.displayName ?: it } +
                if (permissions.size > shown.size) listOf("+${permissions.size - shown.size}") else emptyList()
        badges.forEach { label ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 脚本制品指示行：门面/钩子脚本随包安装（P1 不执行） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ScriptIndicatorsText(pack: ExtensionPackEntity) {
    val labels =
        listOfNotNull(
            "门面脚本".takeIf { pack.hasPreload },
            "钩子脚本".takeIf { pack.hasHooks },
        )
    Text(
        text = labels.joinToString(" / ") + "（门面脚本随启停生效；宿主钩子执行在后续版本启用）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** 权限详情：逐条权限中文名 + 说明 + JS 制品指示（列表行点击打开） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun PermissionDetailDialog(
    pack: ExtensionPackEntity,
    onDismiss: () -> Unit,
) {
    val permissions = ExtensionPackStore.decodePermissions(pack.permissionsJson)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扩展包详情") },
        text = {
            Column {
                Text("「${pack.name}」 v${pack.version} · ${pack.author}")
                Text(
                    text = "加载顺序 ${pack.loadingOrder} · 安装于 ${pack.installedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (permissions.isEmpty()) {
                    Text(
                        text = "无权限声明",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    PermissionEntries(permissions)
                }
                if (pack.hasPreload || pack.hasHooks) {
                    ScriptArtifactsText(pack)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

/** 详情中的权限清单小节：标题 + 逐条「中文名：说明」（目录外权限兜底显示原始值） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun PermissionEntries(permissions: List<String>) {
    Text(
        text = "权限清单（${permissions.size} 项）",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 8.dp),
    )
    permissions.forEach { raw ->
        val entry = PermissionCatalog.resolve(raw)
        Text(
            text =
                if (entry != null) {
                    "• ${entry.displayName}：${entry.description}"
                } else {
                    "• $raw"
                },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 详情中的脚本制品小节 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ScriptArtifactsText(pack: ExtensionPackEntity) {
    Text(
        text =
            buildString {
                append("携带脚本制品：")
                if (pack.hasPreload) append("WebView 预注入门面 JS（启用时注入每个卡片界面）；")
                if (pack.hasHooks) append("宿主钩子 JS（执行接线在后续版本启用）；")
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 导入确认对话框：逐条权限中文名 + 说明 + JS 制品提示（升级「包含可执行脚本」先例） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ImportConfirmDialog(
    preview: ExtensionPackRepository.ExtensionPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val manifest = preview.bundle.manifest
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扩展包权限确认") },
        text = {
            Column {
                Text("「${manifest.name}」 v${manifest.version} · ${manifest.author}")
                if (preview.permissions.isNotEmpty()) {
                    Text(
                        text = "该扩展包声明了以下权限（${preview.permissions.size} 项）：",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    preview.permissions.forEach { entry ->
                        Text(
                            text = "• ${entry.displayName}：${entry.description}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                val jsHints =
                    listOfNotNull(
                        "WebView 预注入门面脚本（${manifest.webviewPreload}）".takeIf { manifest.webviewPreload != null },
                        "宿主钩子脚本（${manifest.hostHooks}）".takeIf { manifest.hostHooks != null },
                        "沙箱脚本（${manifest.script}）".takeIf { preview.bundle.content.scriptSource != null },
                    )
                if (jsHints.isNotEmpty()) {
                    Text(
                        text = "包含 JS 脚本制品：\n" + jsHints.joinToString("\n") { "• $it" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    text = "请只安装你信任来源的扩展包；确认「信任并导入」即代表批准以上全部权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("信任并导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private const val PERMISSION_BADGE_LIMIT = 3
