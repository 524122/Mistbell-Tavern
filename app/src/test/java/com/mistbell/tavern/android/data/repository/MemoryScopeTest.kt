package com.mistbell.tavern.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 召回范围的 KV 解析：会话内长期记忆产品决策（2026-10）——
 * **一切输入（含 null/非法值/旧档位值 character/owner）都归一化为 SESSION**；
 * CHARACTER/OWNER 仅为兼容旧设置值/旧备份保留枚举项（toRaw 仍写回旧字符串）。
 */
class MemoryScopeTest {
    @Test
    fun `一切输入都归一化为 SESSION`() {
        assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw(null))
        assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw(""))
        assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw("nonsense"))
        assertEquals(MemoryScope.SESSION, ChatSettingsResolver.memoryScope("nonsense"))
    }

    @Test
    fun `旧档位值也归一化为 SESSION_双向映射不再成立`() {
        assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw("character"))
        assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw("owner"))
        assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw("session"))
        assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw("CHARACTER"))
        MemoryScope.entries.forEach { scope ->
            assertEquals(MemoryScope.SESSION, MemoryScope.fromRaw(MemoryScope.toRaw(scope)))
        }
    }

    @Test
    fun `三档的存储值互不相同_旧数据仍可写回`() {
        val raw = MemoryScope.entries.map { MemoryScope.toRaw(it) }
        assertEquals(raw.size, raw.toSet().size)
        assertEquals("session", MemoryScope.toRaw(MemoryScope.SESSION))
        assertEquals("character", MemoryScope.toRaw(MemoryScope.CHARACTER))
        assertEquals("owner", MemoryScope.toRaw(MemoryScope.OWNER))
    }
}
