package com.mistbell.tavern.android.ui.import

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.repository.BackupManager
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.util.AdvancedCapabilityProfile
import com.mistbell.tavern.android.util.AdvancedCapabilityTotals
import com.mistbell.tavern.android.util.CharacterImportPersist
import com.mistbell.tavern.android.util.CharacterImporter
import com.mistbell.tavern.android.util.SessionImportOutcome
import com.mistbell.tavern.android.util.SessionImportPersist
import com.mistbell.tavern.android.util.StSyncImporter
import com.mistbell.tavern.android.util.WorldBookImportOutcome
import com.mistbell.tavern.android.util.WorldBookImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 导入页的五类入口（与规划 P3 第 3 项一一对应） */
enum class ImportKind {
    /** 角色卡（JSON / PNG 埋卡 / CHARX，沿用 CharacterImporter 的现有识别） */
    CHARACTER_CARD,

    /** 酒馆数据文件夹（characters + worlds 批量同步） */
    TAVERN_FOLDER,

    /** 独立世界书 JSON */
    WORLD_BOOK,

    /** 聊天记录（会话导出 JSON / JSONL） */
    CHAT_SESSION,

    /** 全量备份恢复 */
    BACKUP,
}

/** 报告里的一节明细（标题 + 行列表；空列表整节不渲染，页面层负责截断） */
data class ImportReportSection(
    val title: String,
    val lines: List<String>,
)

/**
 * 报告的「需要高级模式」一栏：单卡是能力概况，批量是聚合（数量 + 涉及角色名）。
 * [hasAny] 是「确有高级能力」的统一判据——两个选择只在此为真时出现。
 */
sealed interface ImportAdvancedSummary {
    val hasAny: Boolean

    /** 单卡：CharacterImportResult.advancedCapabilities */
    data class Single(val profile: AdvancedCapabilityProfile) : ImportAdvancedSummary {
        override val hasAny: Boolean get() = profile.hasAny
    }

    /** 批量：SyncReport.advancedCapabilities */
    data class Batch(val totals: AdvancedCapabilityTotals) : ImportAdvancedSummary {
        override val hasAny: Boolean get() = totals.hasAny
    }
}

/**
 * 一次导入的结果报告（导入完成后同页展示；null = 无待展示报告）。
 * 原来只进 logcat 的诊断提示在 [sections] 里成为页面可见的明细。
 */
data class ImportReport(
    val kind: ImportKind,
    val headline: String,
    /** 概要行（计数类信息） */
    val summaryLines: List<String> = emptyList(),
    /** 明细小节（诊断提示 / 同名跳过 / 跳过文件 / 失败文件等） */
    val sections: List<ImportReportSection> = emptyList(),
    /** 「需要高级模式」一栏；null = 该类导入没有此栏或未检出任何能力 */
    val advanced: ImportAdvancedSummary? = null,
) {
    /** 失败报告（无高级栏；headline 即失败原因） */
    companion object {
        fun failure(
            kind: ImportKind,
            reason: String,
        ): ImportReport = ImportReport(kind = kind, headline = reason)
    }
}

/** 报告下方的选择区（纯函数换算，JVM 可单测） */
enum class ImportModeChoice {
    /** 无高级能力（或模式未知）：只展示常规报告，无选择 */
    NONE,

    /** 当前简洁且确有高级能力：给「切换到高级模式」「保持简洁」两个按钮 */
    SWITCH_OR_KEEP,

    /** 当前已高级：只做说明（这些能力照常运行），不给切换按钮 */
    ADVANCED_NOTE,
}

/**
 * 导入报告的发布与「两个选择」的落库语义（零 Android 依赖，JVM 可单测；
 * 形态照 UiModePromptCoordinator——本类持状态，[ImportViewModel] 是薄壳）。
 *
 * 状态机：
 * - 发布报告（导入完成）→ 界面按 [modeChoiceFor] 渲染选择区；
 * - 「切换到高级模式」→ 只写 `ui_mode = advanced`（不关报告；模式流更新后选择区自动变为说明）；
 * - 「保持简洁」→ 不写任何设置、关掉报告（高级能力保持关闭，闸门自行保持）；
 * - 失败报告同样占据报告位（advanced = null，永不出选择）。
 */
class ImportReportCoordinator(
    /** ui_mode 落库（唯一写入口；「保持简洁」绝不经过它） */
    private val writeMode: suspend (UiMode) -> Unit,
) {
    private val _report = MutableStateFlow<ImportReport?>(null)

    /** 非空 = 页面展示这份报告 */
    val report: StateFlow<ImportReport?> = _report.asStateFlow()

    /** 报告下方的选择区（唯一换算点）：确有高级能力才区分两模式，模式未知不渲染 */
    fun modeChoiceFor(
        report: ImportReport?,
        mode: UiMode?,
    ): ImportModeChoice =
        when {
            report?.advanced?.hasAny != true -> ImportModeChoice.NONE
            mode == UiMode.SIMPLE -> ImportModeChoice.SWITCH_OR_KEEP
            mode == UiMode.ADVANCED -> ImportModeChoice.ADVANCED_NOTE
            // 模式未知（null）：不渲染随模式变化的东西，与 docs/UI_MODE.md §1 同一口径
            else -> ImportModeChoice.NONE
        }

    /** 发布（覆盖）当前报告 */
    fun publish(report: ImportReport) {
        _report.value = report
    }

    /** 「保持简洁」：不写任何设置，只关掉报告——高级能力保持关闭，不在后台悄悄跑 */
    fun keepSimple() {
        _report.value = null
    }

    /** 「切换到高级模式」：落库 ui_mode = advanced；报告保留展示 */
    suspend fun switchToAdvanced() {
        writeMode(UiMode.ADVANCED)
    }
}

/**
 * 「导入」页 ViewModel（P3 第 3 项）：五类入口全部调用现有导入实现——
 * 角色卡 [CharacterImporter.importFromUri] + 共享落库 [CharacterImportPersist]，
 * 文件夹 [StSyncImporter.syncFromFolder]，世界书 [WorldBookImporter]，
 * 聊天记录 [SessionImportPersist]，备份 [BackupManager.restoreFromUri]。
 */
class ImportViewModel(application: Application) : AndroidViewModel(application) {
    // AndroidViewModel 自带 getApplication()：直接取宿主应用的容器，不新增 instance 全局读取（计数棘轮）
    private val db = getApplication<TavernApplication>().container.database

    private val settingsRepository = SettingsRepository()

    /** 进程级常驻模式流直通（不做本页 stateIn，docs/UI_MODE.md §1 口径） */
    val uiMode: StateFlow<UiMode?> = getApplication<TavernApplication>().container.uiMode

    private val coordinator = ImportReportCoordinator(writeMode = { settingsRepository.setUiMode(it) })

    /** 非空 = 展示这份导入报告 */
    val report: StateFlow<ImportReport?> = coordinator.report

    private val _busy = MutableStateFlow(false)

    /** 任一导入进行中（入口按钮禁用 + 进度条） */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 报告下方的选择区（透传协调器的唯一换算点） */
    fun modeChoice(
        report: ImportReport?,
        mode: UiMode?,
    ): ImportModeChoice = coordinator.modeChoiceFor(report, mode)

    /** 「切换到高级模式」：写 ui_mode=advanced（执行闸门与 Tab 收拢经既有流响应式生效） */
    fun switchToAdvanced() {
        viewModelScope.launch { coordinator.switchToAdvanced() }
    }

    /** 「保持简洁」：不写任何设置、关掉报告 */
    fun keepSimple() {
        coordinator.keepSimple()
    }

    /** 入口一：角色卡（JSON / PNG 埋卡 / CHARX，沿用现有格式识别） */
    fun importCharacterCard(
        context: Context,
        uri: Uri,
    ) = launchImport(ImportKind.CHARACTER_CARD) {
        val result = CharacterImporter.importFromUri(context, uri)
        if (result == null) {
            ImportReport.failure(ImportKind.CHARACTER_CARD, "导入失败：无法解析角色卡文件（支持 JSON、PNG 埋卡与 CHARX）")
        } else {
            val outcome = CharacterImportPersist.persist(db, result)
            ImportReport(
                kind = ImportKind.CHARACTER_CARD,
                headline =
                    (if (outcome.updatedExistingCharacter) "已更新角色" else "成功导入角色") +
                        "：${outcome.characterName}",
                summaryLines =
                    buildList {
                        if (result.worldBook != null) {
                            add(
                                "世界书${if (outcome.worldBookUpdatedExisting) "更新" else "新增"} " +
                                    "${outcome.worldBookEntryCount} 条条目：${result.worldBook.name}",
                            )
                        }
                    },
                sections =
                    if (result.warnings.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(ImportReportSection("诊断提示", result.warnings))
                    },
                advanced = ImportAdvancedSummary.Single(result.advancedCapabilities),
            )
        }
    }

    /** 入口二：酒馆数据文件夹（characters + worlds 递归同步，幂等） */
    fun importTavernFolder(treeUri: Uri) =
        launchImport(ImportKind.TAVERN_FOLDER) {
            val sync = StSyncImporter.syncFromFolder(getApplication(), treeUri, db)
            val summaryLines =
                buildList {
                    add("角色：新增 ${sync.createdCharacters}，更新 ${sync.updatedCharacters}")
                    add("世界书：新增 ${sync.createdBooks}，更新 ${sync.updatedBooks}")
                    if (sync.skippedSprites > 0) add("跳过无埋卡图片 ${sync.skippedSprites} 张（立绘/表情差分）")
                }
            val sections =
                buildList {
                    if (sync.duplicateNames.isNotEmpty()) add(ImportReportSection("同名跳过（先到先得）", sync.duplicateNames))
                    if (sync.skippedFiles.isNotEmpty()) add(ImportReportSection("跳过文件", sync.skippedFiles))
                    if (sync.failedFiles.isNotEmpty()) add(ImportReportSection("失败文件", sync.failedFiles))
                }
            ImportReport(
                kind = ImportKind.TAVERN_FOLDER,
                headline = "酒馆数据文件夹同步完成",
                summaryLines = summaryLines,
                sections = sections,
                advanced = ImportAdvancedSummary.Batch(sync.advancedCapabilities),
            )
        }

    /** 入口三：独立世界书 JSON */
    fun importWorldBook(
        context: Context,
        uri: Uri,
    ) = launchImport(ImportKind.WORLD_BOOK) {
        when (val outcome = WorldBookImporter.importFromUri(context, uri, db)) {
            is WorldBookImportOutcome.Success ->
                ImportReport(
                    kind = ImportKind.WORLD_BOOK,
                    headline = "成功导入世界书：${outcome.bookName}",
                    summaryLines = listOf("条目 ${outcome.entryCount} 条"),
                )
            is WorldBookImportOutcome.ParseFailed ->
                ImportReport.failure(ImportKind.WORLD_BOOK, "导入失败：无法解析世界书文件")
            is WorldBookImportOutcome.IoFailed ->
                ImportReport.failure(ImportKind.WORLD_BOOK, "导入失败: ${outcome.reason}")
        }
    }

    /** 入口四：聊天记录（会话导出 JSON / JSONL） */
    fun importChatSession(
        context: Context,
        uri: Uri,
    ) = launchImport(ImportKind.CHAT_SESSION) {
        when (val outcome = SessionImportPersist.importFromUri(context, uri, db)) {
            is SessionImportOutcome.Success ->
                ImportReport(
                    kind = ImportKind.CHAT_SESSION,
                    headline = outcome.summary,
                    summaryLines = listOf("会话已在聊天列表出现，角色保持原归属"),
                )
            is SessionImportOutcome.Failure ->
                ImportReport.failure(ImportKind.CHAT_SESSION, outcome.reason)
        }
    }

    /** 入口五：全量备份恢复（与设置页同一实现 BackupManager） */
    fun restoreBackup(uri: Uri) =
        launchImport(ImportKind.BACKUP) {
            val summary = BackupManager(getApplication()).restoreFromUri(uri)
            val skippedSuffix =
                if (summary.totalSkipped > 0) "（跳过已存在 ${summary.totalSkipped} 项）" else ""
            ImportReport(
                kind = ImportKind.BACKUP,
                headline = "恢复完成：合并 ${summary.totalAdded} 项$skippedSuffix",
            )
        }

    /** 统一的执行外壳：串行（同一次只跑一个）、异常转失败报告、结束后发布报告（与既有导入路径同口径） */
    @Suppress("TooGenericExceptionCaught")
    private fun launchImport(
        kind: ImportKind,
        block: suspend () -> ImportReport,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                coordinator.publish(block())
            } catch (e: Exception) {
                android.util.Log.e("ImportPage", "导入失败（$kind）", e)
                coordinator.publish(ImportReport.failure(kind, "导入失败: ${e.message}"))
            } finally {
                _busy.value = false
            }
        }
    }
}
