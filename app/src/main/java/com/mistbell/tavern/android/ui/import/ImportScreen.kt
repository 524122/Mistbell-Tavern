package com.mistbell.tavern.android.ui.import

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.common.ModernTopBar

/** 明细小节的截断上限（超出部分给总数提示，与角色页同步报告一致） */
private const val REPORT_SECTION_MAX_LINES = 50

/**
 * 「导入」页（P3 第 3 项）：把酒馆资产带进来的唯一入口。
 *
 * 结构：五类入口卡片 + 结果报告（同页展示，含「需要高级模式」一栏与诊断明细）。
 * 整页一个可滚动容器（verticalScroll），报告较长时底部的两个选择按钮始终可达——
 * 不在不可滚动的 Column 里塞超一屏内容，也不嵌套 LazyColumn。
 * 当前简洁且确有高级能力时，报告下方给「切换到高级模式」「保持简洁」两个按钮；
 * 已是高级模式只做说明。
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态
@Composable
fun ImportScreen(
    viewModel: ImportViewModel =
        viewModel(
            factory =
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
                ),
        ),
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiMode by viewModel.uiMode.collectAsState()
    val report by viewModel.report.collectAsState()
    val busy by viewModel.busy.collectAsState()

    // 五类入口各自的系统选择器；识别与落库全部走现有导入实现（见 ImportViewModel）
    val cardLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.importCharacterCard(context, it) }
        }
    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                // 取持久读授权：导入中途切后台/Activity 重建不丢文档树访问权
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.importTavernFolder(it)
            }
        }
    val worldBookLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importWorldBook(context, it) }
        }
    val chatLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            // JSONL 使用 ndjson MIME；GetContent 仍允许选择扩展名不标准的酒馆导出
            uri?.let { viewModel.importChatSession(context, it) }
        }
    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.restoreBackup(it) }
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ModernTopBar(
                title = "导入",
                subtitle = "把酒馆资产带进来",
                onBack = onBack,
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            ImportEntryCard(
                title = "角色卡",
                subtitle = "JSON、PNG 埋卡或 CHARX，自动识别格式；同名卡按文件版原地更新",
                icon = Icons.Default.FileUpload,
                enabled = !busy,
                onClick = { cardLauncher.launch("*/*") },
            )
            ImportEntryCard(
                title = "酒馆数据文件夹",
                subtitle = "递归同步 characters 与 worlds 文件夹，已存在的角色与世界书按文件版更新",
                icon = Icons.Default.Folder,
                enabled = !busy,
                onClick = { folderLauncher.launch(null) },
            )
            ImportEntryCard(
                title = "世界书",
                subtitle = "酒馆 worlds 文件夹里的独立世界书 JSON",
                icon = Icons.Default.Book,
                enabled = !busy,
                onClick = { worldBookLauncher.launch(arrayOf("application/json", "application/octet-stream")) },
            )
            ImportEntryCard(
                title = "聊天记录",
                subtitle = "会话导出文件（JSON 或 JSONL）；需已导入对应角色卡",
                icon = Icons.Default.Chat,
                enabled = !busy,
                onClick = { chatLauncher.launch("*/*") },
            )
            ImportEntryCard(
                title = "备份恢复",
                subtitle = "从本应用导出的备份文件（zip）合并恢复全部数据",
                icon = Icons.Default.Restore,
                enabled = !busy,
                onClick = { backupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            )

            report?.let { current ->
                ImportReportCard(
                    report = current,
                    choice = viewModel.modeChoice(current, uiMode),
                    onSwitchToAdvanced = viewModel::switchToAdvanced,
                    onKeepSimple = viewModel::keepSimple,
                )
            }
        }
    }
}

/** 五类入口之一的卡片（图标 + 标题 + 说明，点击唤起对应系统选择器） */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
private fun ImportEntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 导入结果报告卡：概要 +「需要高级模式」一栏 + 诊断明细 + 两个选择。
 * 选择语义见 [ImportReportCoordinator]；这里只按 [ImportModeChoice] 渲染。
 */
@Composable
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
private fun ImportReportCard(
    report: ImportReport,
    choice: ImportModeChoice,
    onSwitchToAdvanced: () -> Unit,
    onKeepSimple: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = report.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            report.summaryLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            report.advanced?.let { advanced -> AdvancedCapabilitySection(advanced) }

            report.sections.forEach { section -> ReportSectionLines(section) }

            ModeChoiceArea(
                choice = choice,
                onSwitchToAdvanced = onSwitchToAdvanced,
                onKeepSimple = onKeepSimple,
            )
        }
    }
}

/** 「需要高级模式」一栏：按数量列出命中的能力 + 包宏占位符说明（规划原文） */
@Composable
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
private fun AdvancedCapabilitySection(advanced: ImportAdvancedSummary) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    Text(
        text = "需要高级模式",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val lines = remember(advanced) { advancedCapabilityLines(advanced) }
    if (lines.isEmpty()) {
        Text(
            text = "未检出需要高级模式的能力。",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        lines.forEach { line ->
            Text(text = "• $line", style = MaterialTheme.typography.bodySmall)
        }
    }
    // 规划要求导入报告写明：包宏的 {{x}} 占位符原样留下，不处理
    Text(
        text = "说明：规则包的 {{宏}} 占位符在简洁模式下会原样留在文本里，不做处理。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 报告下方的选择区（简洁模式两个按钮 / 高级模式只说明 / 无选择不渲染） */
@Composable
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
private fun ModeChoiceArea(
    choice: ImportModeChoice,
    onSwitchToAdvanced: () -> Unit,
    onKeepSimple: () -> Unit,
) {
    when (choice) {
        ImportModeChoice.SWITCH_OR_KEEP -> {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Text(
                text = "以上能力要切换到高级模式才会生效；保持简洁时它们保持关闭，不在后台运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSwitchToAdvanced,
                    modifier = Modifier.weight(1f),
                ) { Text("切换到高级模式") }
                OutlinedButton(
                    onClick = onKeepSimple,
                    modifier = Modifier.weight(1f),
                ) { Text("保持简洁") }
            }
        }
        ImportModeChoice.ADVANCED_NOTE -> {
            Text(
                text = "当前为高级模式：以上能力照常运行，无需切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ImportModeChoice.NONE -> Unit
    }
}

/** 明细小节（诊断提示 / 同名跳过 / 跳过文件 / 失败文件）；空列表不渲染，超限截断并提示总数 */
@Composable
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
private fun ReportSectionLines(section: ImportReportSection) {
    if (section.lines.isEmpty()) return
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = section.title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    section.lines.take(REPORT_SECTION_MAX_LINES).forEach { line ->
        Text(text = "• $line", style = MaterialTheme.typography.bodySmall)
    }
    if (section.lines.size > REPORT_SECTION_MAX_LINES) {
        Text(
            text = "…共 ${section.lines.size} 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「需要高级模式」一栏的行（纯函数，唯一换算点）：
 * 单卡按数量（正则 N 条 / 卡内脚本 N 段 / HTML 界面 / 状态栏变量更新），
 * 批量附涉及角色名。
 */
internal fun advancedCapabilityLines(advanced: ImportAdvancedSummary): List<String> =
    when (advanced) {
        is ImportAdvancedSummary.Single ->
            buildList {
                if (advanced.profile.regexCount > 0) add("正则 ${advanced.profile.regexCount} 条")
                if (advanced.profile.scriptCount > 0) add("卡内脚本 ${advanced.profile.scriptCount} 段")
                if (advanced.profile.hasHtmlUi) add("HTML 界面")
                if (advanced.profile.hasMvuUpdates) add("状态栏变量更新")
            }
        is ImportAdvancedSummary.Batch ->
            buildList {
                val totals = advanced.totals
                if (totals.regexCount > 0) {
                    add("正则 ${totals.regexCount} 条${characterNamesSuffix(totals.regexCharacters)}")
                }
                if (totals.scriptCount > 0) {
                    add("卡内脚本 ${totals.scriptCount} 段${characterNamesSuffix(totals.scriptCharacters)}")
                }
                if (totals.htmlUiCount > 0) {
                    add("HTML 界面${characterNamesSuffix(totals.htmlUiCharacters)}")
                }
                if (totals.mvuCount > 0) {
                    add("状态栏变量更新${characterNamesSuffix(totals.mvuCharacters)}")
                }
            }
    }

/** 涉及角色名的后缀（空列表无后缀） */
private fun characterNamesSuffix(names: List<String>): String {
    if (names.isEmpty()) return ""
    return "（涉及角色：${names.joinToString("、")}）"
}
