package com.mistbell.tavern.android.data.model

import com.mistbell.tavern.android.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面模式单一真相（P3 执行边界，docs/UI_MODE.md）单测：
 * 能力全表（高级全开 / 简洁全关）+ settings 原始值解析的缺省与容错。
 */
class UiModeTest {
    // ---- enabledCapabilities 全表 ----

    @Test
    fun `高级模式能力全部开启`() {
        val caps = enabledCapabilities(UiMode.ADVANCED)
        assertTrue(caps.cardAndGlobalRegex)
        assertTrue(caps.rulePackContent)
        assertTrue(caps.rulePackScripts)
        assertTrue(caps.extensionPackHooks)
        assertTrue(caps.cardScriptsAndHtmlUi)
        assertTrue(caps.mvuVariableUpdates)
        assertTrue(caps.rulePacksAllowed)
    }

    @Test
    fun `简洁模式能力全部关闭`() {
        val caps = enabledCapabilities(UiMode.SIMPLE)
        assertFalse(caps.cardAndGlobalRegex)
        assertFalse(caps.rulePackContent)
        assertFalse(caps.rulePackScripts)
        assertFalse(caps.extensionPackHooks)
        assertFalse(caps.cardScriptsAndHtmlUi)
        assertFalse(caps.mvuVariableUpdates)
        assertFalse(caps.rulePacksAllowed)
    }

    // ---- fromSetting：缺省与容错 ----

    @Test
    fun `缺省与空值解析为高级`() {
        assertSame(UiMode.ADVANCED, UiMode.fromSetting(null))
        assertSame(UiMode.ADVANCED, UiMode.fromSetting(""))
        assertSame(UiMode.ADVANCED, UiMode.fromSetting("   "))
    }

    @Test
    fun `无法识别的值解析为高级`() {
        assertSame(UiMode.ADVANCED, UiMode.fromSetting("beginner"))
        assertSame(UiMode.ADVANCED, UiMode.fromSetting("garbage"))
        assertSame(UiMode.ADVANCED, UiMode.fromSetting("advanced-mode"))
    }

    @Test
    fun `大小写与首尾空白容错`() {
        assertSame(UiMode.SIMPLE, UiMode.fromSetting("simple"))
        assertSame(UiMode.SIMPLE, UiMode.fromSetting("SIMPLE"))
        assertSame(UiMode.SIMPLE, UiMode.fromSetting("Simple"))
        assertSame(UiMode.SIMPLE, UiMode.fromSetting("  simple  "))
        assertSame(UiMode.ADVANCED, UiMode.fromSetting("advanced"))
        assertSame(UiMode.ADVANCED, UiMode.fromSetting("ADVANCED"))
    }

    // ---- 存库值 ----

    @Test
    fun `存库值为小写名字`() {
        assertEquals("simple", UiMode.SIMPLE.storedValue)
        assertEquals("advanced", UiMode.ADVANCED.storedValue)
    }

    @Test
    fun `设置键名为 ui_mode`() {
        assertEquals("ui_mode", SettingsRepository.KEY_UI_MODE)
    }
}
