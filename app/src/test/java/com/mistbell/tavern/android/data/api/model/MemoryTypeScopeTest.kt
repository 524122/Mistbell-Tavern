package com.mistbell.tavern.android.data.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆类型与归属层级的规则（2026-10 会话内长期记忆）：
 *
 * profile 类（character_info/identity/preference/relationship）**仅用于分类/排序**
 * （召回选择器第二轮优待它们），**不再影响作用域**——extractedSessionId 对一切类型
 * （含 profile 类）都返回 sourceSessionId：一切抽取落库的记忆都归属产生它的会话，
 * 永不落角色级（session_id 为空）。
 */
class MemoryTypeScopeTest {
    @Test
    fun `profile 类覆盖角色与用户画像四种类型`() {
        assertEquals(
            setOf("character_info", "identity", "preference", "relationship"),
            MemoryType.PROFILE_TYPES,
        )
        MemoryType.PROFILE_TYPES.forEach { assertTrue(it, MemoryType.isProfileType(it)) }
    }

    @Test
    fun `事件与具体条目不属于 profile 类`() {
        listOf(MemoryType.EVENT, MemoryType.ITEM, MemoryType.LOCATION, MemoryType.FACT, MemoryType.EMOTION)
            .forEach { assertFalse(it, MemoryType.isProfileType(it)) }
    }

    @Test
    fun `类型判断大小写不敏感`() {
        assertTrue(MemoryType.isProfileType("CHARACTER_INFO"))
        assertTrue(MemoryType.isProfileType("Preference"))
    }

    @Test
    fun `profile 类抽取后保留源会话戳`() {
        assertEquals("s1", MemoryType.extractedSessionId(MemoryType.IDENTITY, "s1"))
        assertEquals("s1", MemoryType.extractedSessionId(MemoryType.PREFERENCE, "s1"))
    }

    @Test
    fun `事件类抽取后保留源会话戳`() {
        assertEquals("s1", MemoryType.extractedSessionId(MemoryType.EVENT, "s1"))
        assertEquals("s1", MemoryType.extractedSessionId(MemoryType.FACT, "s1"))
    }
}
