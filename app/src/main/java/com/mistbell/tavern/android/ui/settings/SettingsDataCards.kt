package com.mistbell.tavern.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据备份/恢复卡片（S4 全量备份：单 zip 含角色/会话/消息/记忆/设置/主题包）。
 * 原位于 SettingsScreen.kt，双轨 UI 收敛时迁出。
 */
@Suppress("FunctionNaming")
@Composable
internal fun DataBackupCard(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
) {
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val backupLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            uri?.let { viewModel.createBackup(it) }
        }
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingRestoreUri = uri
                showRestoreConfirm = true
            }
        }

    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsNavItem(
                title = "创建备份",
                subtitle = "全部角色、会话、记忆、设置与主题包打包为单个 zip",
                onClick = {
                    val stamp =
                        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault())
                            .format(java.util.Date())
                    backupLauncher.launch("mistbell-backup-$stamp.zip")
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsNavItem(
                title = "从备份恢复",
                subtitle = "合并恢复，已存在的数据跳过，不会删除本地内容",
                onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            )

            if (isLoading) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp).heightIn(min = 8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showRestoreConfirm && pendingRestoreUri != null) {
        RestoreConfirmDialog(
            onConfirm = {
                showRestoreConfirm = false
                pendingRestoreUri?.let { viewModel.restoreBackup(it) }
                pendingRestoreUri = null
            },
            onDismiss = {
                showRestoreConfirm = false
                pendingRestoreUri = null
            },
        )
    }
}

/** 恢复确认对话框：合并语义明示（已存在跳过、不删本地），确认后才动数据 */
@Suppress("FunctionNaming")
@Composable
private fun RestoreConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从备份恢复") },
        text = {
            Text("将以合并方式恢复备份中的角色、会话、记忆、设置与主题包：已存在的数据跳过，本地现有内容不会被删除。确定继续？")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("恢复") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 记忆提取提示词编辑对话框。原位于 SettingsScreen.kt，双轨 UI 收敛时迁出。 */
@Suppress("FunctionNaming", "LongMethod") // Compose 对话框组件的既有形态
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryExtractionPromptDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val memoryPrompt by viewModel.memoryExtractionPrompt.collectAsState()
    var editedPrompt by remember(memoryPrompt) { mutableStateOf(memoryPrompt) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "记忆提取提示词",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "自定义 LLM 提取长期记忆时使用的提示词。提示词中必须包含 %s 占位符用于插入对话内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = editedPrompt,
                    onValueChange = { editedPrompt = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 300.dp, max = 500.dp),
                    placeholder = {
                        Text(
                            "输入记忆提取提示词...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle =
                        MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 20.sp,
                        ),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            editedPrompt = viewModel.getDefaultMemoryExtractionPrompt()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("恢复默认")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        viewModel.saveMemoryExtractionPrompt(editedPrompt)
                        onDismiss()
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 崩溃日志/反馈对话框。原位于 SettingsScreen.kt，双轨 UI 收敛时迁出。 */
@Suppress("FunctionNaming", "LongMethod") // Compose 对话框组件的既有形态
@Composable
internal fun CrashLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // null = 正在收集（抓取 logcat 可能阻塞，必须在后台线程构建）
    var report by remember { mutableStateOf<String?>(null) }

    // 打开对话框时在 IO 线程构建诊断报告：崩溃记录 + 过滤后的运行日志
    LaunchedEffect(Unit) {
        report =
            withContext(Dispatchers.IO) {
                CrashLogger.buildDiagnosticReport(context)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "问题反馈与日志",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val currentReport = report
                if (currentReport == null) {
                    Text(
                        text = "正在收集诊断信息…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "以下为崩溃记录与本次运行的最近日志（含导航/点击轨迹，可定位「点击失效、页面未升起」等问题）。已自动过滤聊天内容与 API Key，可导出后通过 Issue 反馈给开发者。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = ButtonDefaults.outlinedButtonBorder,
                    ) {
                        Text(
                            text = currentReport,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp,
                                ),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val uri = CrashLogger.exportReport(context, currentReport)
                                if (uri != null) {
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            CrashLogger.createShareIntent(uri),
                                            "导出诊断日志",
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("导出日志")
                        }
                        OutlinedButton(
                            onClick = {
                                // 仅能清除落盘的崩溃记录；logcat 属系统缓冲区无法清。清后重建报告。
                                CrashLogger.clearLogs(context)
                                report = null
                                coroutineScope.launch {
                                    report =
                                        withContext(Dispatchers.IO) {
                                            CrashLogger.buildDiagnosticReport(context)
                                        }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("清除崩溃记录")
                        }
                    }
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
