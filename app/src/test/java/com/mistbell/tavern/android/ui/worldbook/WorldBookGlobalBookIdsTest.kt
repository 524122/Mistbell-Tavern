package com.mistbell.tavern.android.ui.worldbook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全局世界书多选存储值解析（原设置页内联实现抽出的纯函数）单测：
 * 存储 key 为 world_info_global_book_ids，内容是 JSON 字符串数组，为空/损坏一律降级为空集
 * （消费端语义：空集 = 未配置，使用内置 main 兜底）。
 */
class WorldBookGlobalBookIdsTest {
    @Test
    fun `合法JSON数组解析为集合`() {
        assertEquals(setOf("a", "b"), parseGlobalBookIds("[\"a\",\"b\"]"))
    }

    @Test
    fun `空数组解析为空集`() {
        assertEquals(emptySet<String>(), parseGlobalBookIds("[]"))
    }

    @Test
    fun `空串与null视为空集`() {
        assertEquals(emptySet<String>(), parseGlobalBookIds(""))
        assertEquals(emptySet<String>(), parseGlobalBookIds(null))
    }

    @Test
    fun `损坏JSON降级为空集`() {
        assertEquals(emptySet<String>(), parseGlobalBookIds("[a,b"))
        assertEquals(emptySet<String>(), parseGlobalBookIds("not-json"))
    }

    @Test
    fun `非数组JSON降级为空集`() {
        assertEquals(emptySet<String>(), parseGlobalBookIds("\"main\""))
        assertEquals(emptySet<String>(), parseGlobalBookIds("{\"0\":\"main\"}"))
    }

    @Test
    fun `重复id去重`() {
        assertEquals(setOf("a"), parseGlobalBookIds("[\"a\",\"a\"]"))
    }
}
