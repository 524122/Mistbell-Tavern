package com.mistbell.tavern.android.ui.quickreply

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.mistbell.tavern.android.data.local.entity.QuickReplyEntity
import com.mistbell.tavern.android.ui.common.ModernTopBar

/** 全局快捷回复管理：增删改。动作语义见编辑器对话框。 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReplyManagerScreen(
    onBack: () -> Unit = {},
    viewModel: QuickReplyManagerViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var editing by remember { mutableStateOf<QuickReplyEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { ModernTopBar(title = "快捷回复", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = null
                    showEditor = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建快捷回复") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        if (items.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⚡", style = MaterialTheme.typography.displayMedium)
                Text(
                    text = "暂无快捷回复",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "点击右下角按钮新建；规则包也可携带快捷回复（导入即生效）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues,
            ) {
                items(items, key = { it.id }) { item ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editing = item
                                    showEditor = true
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = "${actionLabel(item.action)} · ${item.payload.take(PAYLOAD_PREVIEW_LENGTH)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.delete(item.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    if (showEditor) {
        QuickReplyEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onConfirm = { label, action, payload ->
                viewModel.save(editing?.id, label, action, payload)
                showEditor = false
            },
        )
    }
}

internal fun actionLabel(action: String): String =
    when (action) {
        "send" -> "直接发送"
        "insert" -> "填入输入框"
        "command" -> "执行命令"
        else -> action
    }

private const val PAYLOAD_PREVIEW_LENGTH = 40

@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun QuickReplyEditorDialog(
    initial: QuickReplyEntity?,
    onDismiss: () -> Unit,
    onConfirm: (label: String, action: String, payload: String) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var action by remember { mutableStateOf(initial?.action ?: "insert") }
    var payload by remember { mutableStateOf(initial?.payload ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建快捷回复" else "编辑快捷回复") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("按钮文案") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("insert", "send", "command").forEach { option ->
                        FilterChip(
                            selected = action == option,
                            onClick = { action = option },
                            label = { Text(actionLabel(option)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = {
                        Text(
                            when (action) {
                                "command" -> "命令（须以 / 开头，如 /note 内容）"
                                "send" -> "发送内容（宏会在发送时渲染）"
                                else -> "填入内容"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label, action, payload) },
                enabled = label.isNotBlank() && payload.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
