package com.mistbell.tavern.android.ui.main

import com.mistbell.tavern.android.data.model.UiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主导航按模式收拢（P3 第 2 项，docs/UI_MODE.md §5）单测：
 * 模式 → 可见 Tab 列表、选中 Tab 被隐藏时的回落、模式未知（null）不渲染、稳定 id 恢复。
 */
class MainTabTest {
    // ---- 模式 → 可见 Tab ----

    @Test
    fun `简洁模式可见三个Tab 聊天角色设置`() {
        assertEquals(
            listOf(MainTab.CHAT, MainTab.CHARACTERS, MainTab.SETTINGS),
            visibleMainTabs(UiMode.SIMPLE),
        )
    }

    @Test
    fun `高级模式保持现状五个Tab`() {
        assertEquals(
            listOf(MainTab.CHAT, MainTab.CHARACTERS, MainTab.WORLD_BOOK, MainTab.REGEX, MainTab.SETTINGS),
            visibleMainTabs(UiMode.ADVANCED),
        )
    }

    @Test
    fun `简洁模式下世界书与正则不出现在可见列表`() {
        val tabs = visibleMainTabs(UiMode.SIMPLE)
        assertTrue(MainTab.WORLD_BOOK !in tabs)
        assertTrue(MainTab.REGEX !in tabs)
    }

    // ---- 模式未知（null，常驻流尚未读回）：不渲染随模式变化的东西，与闸门「未知不放行」同口径 ----

    @Test
    fun `模式未知时可见Tab为空列表`() {
        assertEquals(emptyList<MainTab>(), visibleMainTabs(null))
        assertTrue(visibleMainTabs(null).isEmpty())
    }

    @Test
    fun `模式未知时选中的Tab回落到聊天`() {
        assertEquals(MainTab.CHAT, settledMainTab(MainTab.WORLD_BOOK, null))
        assertEquals(MainTab.CHAT, settledMainTab(MainTab.REGEX, null))
        assertEquals(MainTab.CHAT, settledMainTab(MainTab.SETTINGS, null))
        assertEquals(MainTab.CHAT, settledMainTab(MainTab.CHAT, null))
    }

    // ---- 选中被隐藏时的回落 ----

    @Test
    fun `选中世界书切到简洁回落到聊天`() {
        assertEquals(MainTab.CHAT, settledMainTab(MainTab.WORLD_BOOK, UiMode.SIMPLE))
    }

    @Test
    fun `选中正则切到简洁回落到聊天`() {
        assertEquals(MainTab.CHAT, settledMainTab(MainTab.REGEX, UiMode.SIMPLE))
    }

    @Test
    fun `可见的选中Tab不回落`() {
        assertEquals(MainTab.SETTINGS, settledMainTab(MainTab.SETTINGS, UiMode.SIMPLE))
        assertEquals(MainTab.WORLD_BOOK, settledMainTab(MainTab.WORLD_BOOK, UiMode.ADVANCED))
    }

    // ---- 稳定 id ----

    @Test
    fun `id往返恢复`() {
        MainTab.entries.forEach { tab -> assertEquals(tab, MainTab.fromId(tab.id)) }
    }

    @Test
    fun `未知id与旧版下标残值回落到聊天`() {
        assertEquals(MainTab.CHAT, MainTab.fromId(null))
        assertEquals(MainTab.CHAT, MainTab.fromId(""))
        // 旧版本保存的是列表下标 0–4（"1" 曾表示「角色」）；新保存键换名后这类残值就是未知 id
        assertEquals(MainTab.CHAT, MainTab.fromId("1"))
        assertEquals(MainTab.CHAT, MainTab.fromId("world_book_list"))
        assertNotEquals(MainTab.SAVE_KEY, "0")
    }

    @Test
    fun `保存键换名避免恢复旧下标`() {
        // 下标时代的值是 "0"–"4"；新键必须不同名，否则升级后可能恢复出旧的下标值
        assertTrue(MainTab.SAVE_KEY != "main_selected_tab")
    }
}
