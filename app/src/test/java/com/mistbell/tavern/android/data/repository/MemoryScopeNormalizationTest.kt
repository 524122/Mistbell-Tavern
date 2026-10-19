package com.mistbell.tavern.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 契约：旧设置档位（character/owner）被归一化后**不得扩大召回窗口**——
 * 旧备份/旧设置里的档位值经 MemoryScope.fromRaw 解析为 SESSION，
 * 再经 RecallWindow.resolve 得到的窗口恒为 owner + 当前会话（characterId=null、
 * 单会话、includeUnassigned=false）：升级后绝不出现"旧档位复活跨会话召回"。
 */
class MemoryScopeNormalizationTest {
    @Test
    fun `旧 character 档位解析出的窗口只有当前会话`() {
        val scope = ChatSettingsResolver.memoryScope("character")
        val window = RecallWindow.resolve("owner", "character", "s1", scope, listOf("s1", "s2", "s3"))

        assertEquals(MemoryScope.SESSION, scope)
        assertEquals(setOf("s1"), window.sessionIds)
        assertNull(window.characterId)
        assertEquals(setOf("s1"), window.vectorSessionValues)
    }

    @Test
    fun `旧 owner 档位解析出的窗口只有当前会话`() {
        val scope = ChatSettingsResolver.memoryScope("owner")
        val window = RecallWindow.resolve("owner", "character", "s1", scope, listOf("s1", "s2"))

        assertEquals(MemoryScope.SESSION, scope)
        assertEquals(setOf("s1"), window.sessionIds)
        assertNull(window.characterId)
    }

    @Test
    fun `settings KV 键保留_读取恒为 SESSION`() {
        assertEquals("memory_recall_scope", MemoryScope.KEY)
        listOf(null, "", "session", "character", "owner", "CHARACTER", "junk").forEach { raw ->
            assertEquals("KV 值 '$raw' 必须归一化为 SESSION", MemoryScope.SESSION, MemoryScope.fromRaw(raw))
        }
    }

    @Test
    fun `废弃档位枚举仍保留_存储值可写回旧字符串`() {
        assertEquals("character", MemoryScope.toRaw(MemoryScope.CHARACTER))
        assertEquals("owner", MemoryScope.toRaw(MemoryScope.OWNER))
        assertEquals("session", MemoryScope.toRaw(MemoryScope.SESSION))
    }
}
