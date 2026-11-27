package com.mistbell.tavern.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 启动期界面模式缺省与升级判定（P3 第 2 项，docs/UI_MODE.md §5）单测：
 * 三种结果各覆盖、四个生态信号各一条、显式设置过不弹窗、全新安装不弹窗直接简洁、
 * 弹过不再弹（含值缺失的保守分支）。
 */
class UiModeStartupTest {
    private fun inputs(
        explicitModeRaw: String? = null,
        onboardingDone: Boolean = true,
        promptAlreadyShown: Boolean = false,
        signals: UiModeEcosystemSignals = UiModeEcosystemSignals.NONE,
    ) = UiModeStartupInputs(explicitModeRaw, onboardingDone, promptAlreadyShown, signals)

    /** 信号构造（四参具名，测试里一行放不下全字段） */
    private fun signals(
        regex: Boolean = false,
        packs: Boolean = false,
        html: Boolean = false,
        cards: Boolean = false,
    ): UiModeEcosystemSignals =
        UiModeEcosystemSignals(
            hasRegexRules = regex,
            hasEnabledPacks = packs,
            hasHtmlUiEnabled = html,
            hasImportedTavernCard = cards,
        )

    // ---- 三种结果 ----

    @Test
    fun `结果一 显式设置过保持该值`() {
        val resolution = resolveStartupUiMode(inputs(explicitModeRaw = "simple"))
        assertEquals(UiModeStartupResolution.Keep(UiMode.SIMPLE), resolution)

        val advanced = resolveStartupUiMode(inputs(explicitModeRaw = "advanced"))
        assertEquals(UiModeStartupResolution.Keep(UiMode.ADVANCED), advanced)
    }

    @Test
    fun `结果二 全新安装默认简洁且不弹窗`() {
        val resolution = resolveStartupUiMode(inputs(onboardingDone = false))
        assertEquals(UiModeStartupResolution.FreshInstallSimple, resolution)
    }

    @Test
    fun `结果三 升级首启弹窗 无信号预选简洁`() {
        val resolution = resolveStartupUiMode(inputs())
        assertEquals(UiModeStartupResolution.Prompt(UiMode.SIMPLE), resolution)
    }

    // ---- 四个生态信号各一条（任一为真预选高级）----

    @Test
    fun `信号一 全局正则预选高级`() {
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.ADVANCED),
            resolveStartupUiMode(inputs(signals = signals(regex = true))),
        )
    }

    @Test
    fun `信号二 扩展包预选高级`() {
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.ADVANCED),
            resolveStartupUiMode(inputs(signals = signals(packs = true))),
        )
    }

    @Test
    fun `信号三 HTML界面预选高级`() {
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.ADVANCED),
            resolveStartupUiMode(inputs(signals = signals(html = true))),
        )
    }

    @Test
    fun `信号四 导入过酒馆卡预选高级`() {
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.ADVANCED),
            resolveStartupUiMode(inputs(signals = signals(cards = true))),
        )
    }

    @Test
    fun `无信号预选简洁`() {
        assertFalse(UiModeEcosystemSignals.NONE.hasAny)
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.SIMPLE),
            resolveStartupUiMode(inputs(signals = UiModeEcosystemSignals.NONE)),
        )
    }

    // ---- 边界 ----

    @Test
    fun `显式设置过时即使有信号也不弹窗`() {
        val all = signals(regex = true, packs = true, html = true, cards = true)
        assertEquals(
            UiModeStartupResolution.Keep(UiMode.SIMPLE),
            resolveStartupUiMode(inputs(explicitModeRaw = "simple", signals = all)),
        )
    }

    @Test
    fun `全新安装时即使有信号也不弹窗`() {
        val all = signals(regex = true, packs = true, html = true, cards = true)
        assertEquals(
            UiModeStartupResolution.FreshInstallSimple,
            resolveStartupUiMode(inputs(onboardingDone = false, signals = all)),
        )
    }

    @Test
    fun `弹过标记存在时不再弹窗`() {
        val resolution = resolveStartupUiMode(inputs(promptAlreadyShown = true))
        assertEquals(UiModeStartupResolution.Keep(UiMode.ADVANCED), resolution)
    }

    @Test
    fun `无法识别的旧值视为从未设置`() {
        // 旧稿枚举残留 "beginner"（从未接线落库）不算显式设置：升级用户照常走弹窗路径
        val resolution = resolveStartupUiMode(inputs(explicitModeRaw = "beginner"))
        assertEquals(UiModeStartupResolution.Prompt(UiMode.SIMPLE), resolution)
        // 不可识别值在有信号时预选高级
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.ADVANCED),
            resolveStartupUiMode(inputs(explicitModeRaw = "beginner", signals = signals(cards = true))),
        )
    }

    @Test
    fun `空串与空白视为从未设置`() {
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.SIMPLE),
            resolveStartupUiMode(inputs(explicitModeRaw = "")),
        )
        assertEquals(
            UiModeStartupResolution.Prompt(UiMode.SIMPLE),
            resolveStartupUiMode(inputs(explicitModeRaw = "  ")),
        )
    }

    @Test
    fun `大小写与首尾空白容错`() {
        assertEquals(
            UiModeStartupResolution.Keep(UiMode.SIMPLE),
            resolveStartupUiMode(inputs(explicitModeRaw = " SIMPLE ")),
        )
    }

    @Test
    fun `执行闸门缺省不变 fromSetting空值仍是高级`() {
        // 第 1 项的棘轮：缺省解析不受本项影响（全新安装的简洁是显式落库，不是改缺省）
        assertEquals(UiMode.ADVANCED, UiMode.fromSetting(null))
        assertEquals(UiMode.ADVANCED, UiMode.fromSetting("unknown"))
    }
}
