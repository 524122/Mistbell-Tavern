package com.mistbell.tavern.android.ui.import

import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.util.AdvancedCapabilityProfile
import com.mistbell.tavern.android.util.AdvancedCapabilityTotals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 导入报告「两个选择」的状态机单测（P3 第 3 项，docs/UI_MODE.md §5）。
 * writeMode 是记录调用的手写 fake：切换 = 写 ui_mode=advanced（恰好一次）；
 * 保持简洁 = 不写任何设置、报告关闭。
 */
class ImportReportCoordinatorTest {
    companion object {
        private const val KEY_UI_MODE = "ui_mode"
    }

    /** 内存 settings + 写入记录：与生产键值语义一一对应 */
    private class FakeStore {
        val settings = linkedMapOf<String, String>()
        val writtenModes = mutableListOf<UiMode>()

        fun currentMode(): UiMode? = settings[KEY_UI_MODE]?.let { UiMode.fromSetting(it) }
    }

    private lateinit var store: FakeStore
    private lateinit var coordinator: ImportReportCoordinator

    @Before
    fun setUp() {
        store = FakeStore()
        coordinator =
            ImportReportCoordinator(
                writeMode = { mode ->
                    store.writtenModes += mode
                    store.settings[KEY_UI_MODE] = mode.storedValue
                },
            )
    }

    /** 带高级能力的单卡报告 */
    private fun capableReport(): ImportReport =
        ImportReport(
            kind = ImportKind.CHARACTER_CARD,
            headline = "成功导入角色：探测卡",
            advanced = ImportAdvancedSummary.Single(AdvancedCapabilityProfile(regexCount = 2, hasHtmlUi = true)),
        )

    /** 无高级能力的单卡报告 */
    private fun plainReport(): ImportReport =
        ImportReport(
            kind = ImportKind.CHARACTER_CARD,
            headline = "成功导入角色：干净卡",
            advanced = ImportAdvancedSummary.Single(AdvancedCapabilityProfile.NONE),
        )

    /** 当前报告 + 模式 → 选择区（测试内快捷换算，等价于界面的调用方式） */
    private fun choice(mode: UiMode?): ImportModeChoice = coordinator.modeChoiceFor(coordinator.report.value, mode)

    // ---- 两个选择的出现条件 ----

    @Test
    fun `简洁模式且确有高级能力时出现两个选择`() {
        coordinator.publish(capableReport())
        assertEquals(ImportModeChoice.SWITCH_OR_KEEP, choice(UiMode.SIMPLE))
    }

    @Test
    fun `高级模式只做说明不出现切换`() {
        coordinator.publish(capableReport())
        assertEquals(ImportModeChoice.ADVANCED_NOTE, choice(UiMode.ADVANCED))
    }

    @Test
    fun `没有高级能力时不出现选择`() {
        coordinator.publish(plainReport())
        assertEquals(ImportModeChoice.NONE, choice(UiMode.SIMPLE))
        // advanced = null（失败报告 / 世界书与备份等无此栏的导入）同样无选择
        coordinator.publish(ImportReport.failure(ImportKind.WORLD_BOOK, "导入失败：无法解析世界书文件"))
        assertEquals(ImportModeChoice.NONE, choice(UiMode.SIMPLE))
    }

    @Test
    fun `模式未知时不渲染选择`() {
        coordinator.publish(capableReport())
        assertEquals(ImportModeChoice.NONE, choice(null))
    }

    // ---- 切换到高级模式 ----

    @Test
    fun `选切换写ui_mode为advanced且报告保留`() {
        runBlocking {
            coordinator.publish(capableReport())
            coordinator.switchToAdvanced()
            assertEquals(listOf(UiMode.ADVANCED), store.writtenModes)
            assertEquals("advanced", store.settings[KEY_UI_MODE])
            // 报告保留；选择区随模式流更新变为说明（不给切换按钮）
            assertEquals(capableReport(), coordinator.report.value)
            assertEquals(ImportModeChoice.ADVANCED_NOTE, choice(store.currentMode()))
        }
    }

    // ---- 保持简洁 ----

    @Test
    fun `选保持简洁不写任何设置并关闭报告`() {
        runBlocking {
            coordinator.publish(capableReport())
            coordinator.keepSimple()
            assertTrue(store.writtenModes.isEmpty())
            assertNull(store.settings[KEY_UI_MODE])
            assertNull(coordinator.report.value)
            assertEquals(ImportModeChoice.NONE, choice(UiMode.SIMPLE))
        }
    }

    // ---- 报告流可收集 ----

    @Test
    fun `发布后可从StateFlow读到报告`() {
        runBlocking {
            coordinator.publish(capableReport())
            assertEquals(capableReport(), coordinator.report.first())
            coordinator.keepSimple()
            assertNull(coordinator.report.first())
        }
    }

    // ---- hasAny 谓词（单卡 / 批量两种栏位） ----

    @Test
    fun `单卡与批量的hasAny按能力判定`() {
        assertFalse(ImportAdvancedSummary.Single(AdvancedCapabilityProfile.NONE).hasAny)
        assertTrue(ImportAdvancedSummary.Single(AdvancedCapabilityProfile(hasMvuUpdates = true)).hasAny)
        assertFalse(ImportAdvancedSummary.Batch(AdvancedCapabilityTotals.NONE).hasAny)
        assertTrue(
            ImportAdvancedSummary.Batch(AdvancedCapabilityTotals(regexCount = 1, regexCharacters = listOf("甲"))).hasAny,
        )
    }

    @Test
    fun `批量聚合报告同样按模式给选择`() {
        val batchReport =
            ImportReport(
                kind = ImportKind.TAVERN_FOLDER,
                headline = "酒馆数据文件夹同步完成",
                advanced =
                    ImportAdvancedSummary.Batch(
                        AdvancedCapabilityTotals(
                            scriptCount = 2,
                            scriptCharacters = listOf("甲", "乙"),
                            mvuCount = 1,
                            mvuCharacters = listOf("丙"),
                        ),
                    ),
            )
        coordinator.publish(batchReport)
        assertEquals(ImportModeChoice.SWITCH_OR_KEEP, choice(UiMode.SIMPLE))
        assertEquals(ImportModeChoice.ADVANCED_NOTE, choice(UiMode.ADVANCED))
    }

    // ---- 报告行的换算（纯函数） ----

    @Test
    fun `单卡报告行按数量列出`() {
        val lines =
            advancedCapabilityLines(
                ImportAdvancedSummary.Single(
                    AdvancedCapabilityProfile(regexCount = 3, scriptCount = 2, hasHtmlUi = true, hasMvuUpdates = true),
                ),
            )
        assertEquals(listOf("正则 3 条", "卡内脚本 2 段", "HTML 界面", "状态栏变量更新"), lines)
    }

    @Test
    fun `干净卡报告行为空即不出现该栏内容`() {
        val lines = advancedCapabilityLines(ImportAdvancedSummary.Single(AdvancedCapabilityProfile.NONE))
        assertTrue(lines.isEmpty())
    }

    /** 装机实测发现的缺陷：干净卡导入后出现了一栏「未检出需要高级模式的能力」——整栏都不该出现 */
    @Test
    fun `干净卡_全干净的批量_没有这一栏的导入_都不渲染需要高级模式一栏`() {
        assertFalse(showAdvancedSection(ImportAdvancedSummary.Single(AdvancedCapabilityProfile.NONE)))
        assertFalse(showAdvancedSection(ImportAdvancedSummary.Batch(AdvancedCapabilityTotals.NONE)))
        assertFalse(showAdvancedSection(null))
        assertTrue(
            showAdvancedSection(
                ImportAdvancedSummary.Single(AdvancedCapabilityProfile(hasMvuUpdates = true)),
            ),
        )
        assertTrue(
            showAdvancedSection(
                ImportAdvancedSummary.Batch(AdvancedCapabilityTotals(scriptCount = 1, scriptCharacters = listOf("甲"))),
            ),
        )
    }

    @Test
    fun `批量报告行附涉及角色名`() {
        val lines =
            advancedCapabilityLines(
                ImportAdvancedSummary.Batch(
                    AdvancedCapabilityTotals(
                        regexCount = 4,
                        regexCharacters = listOf("甲", "乙"),
                        htmlUiCount = 1,
                        htmlUiCharacters = listOf("丙"),
                    ),
                ),
            )
        assertEquals(listOf("正则 4 条（涉及角色：甲、乙）", "HTML 界面（涉及角色：丙）"), lines)
    }
}
