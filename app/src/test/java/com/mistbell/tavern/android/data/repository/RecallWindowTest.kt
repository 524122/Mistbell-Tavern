package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.vector.VectorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 召回窗口（RecallWindow）：会话内长期记忆产品决策（2026-10）——
 * 无论传入什么 scope/characterId/ownedSessionIds，窗口恒为 owner + 当前会话：
 * characterId=null、sessionIds={当前会话}（blank→null）、includeUnassigned=false、
 * sessionLimitExceeded=false，vectorSessionValues 不含 "cross_session" 哨兵。
 */
class RecallWindowTest {
    private fun resolve(
        scope: MemoryScope,
        owned: Collection<String> = listOf("s1", "s2", "s3"),
    ) = RecallWindow.resolve("owner", "character", "s1", scope, owned)

    @Test
    fun sessionScopeUsesCurrentSessionOnly() {
        val window = resolve(MemoryScope.SESSION)
        assertEquals(setOf("s1"), window.sessionIds)
        assertNull("窗口恒不按角色收窄", window.characterId)
        assertFalse(window.includeUnassigned)
        assertEquals(setOf("s1"), window.vectorSessionValues)
    }

    @Test
    fun characterScopeIsNormalizedToCurrentSession() {
        val window = resolve(MemoryScope.CHARACTER)
        assertEquals(setOf("s1"), window.sessionIds)
        assertNull(window.characterId)
        assertFalse(window.includeUnassigned)
        assertEquals(setOf("s1"), window.vectorSessionValues)
    }

    @Test
    fun ownerScopeIsNormalizedToCurrentSession() {
        val window = resolve(MemoryScope.OWNER, listOf("s1", "s2", "other"))
        assertNull(window.characterId)
        assertEquals(setOf("s1"), window.sessionIds)
        assertFalse(window.includeUnassigned)
        assertEquals(setOf("s1"), window.vectorSessionValues)
    }

    @Test
    fun vectorSessionValuesNeverContainCharacterLevelSentinel() {
        MemoryScope.entries.forEach { scope ->
            val window = resolve(scope, listOf("s1", "s2"))
            assertTrue(
                "$scope 的向量会话值不得包含 cross_session 哨兵",
                window.vectorSessionValues.orEmpty().none { it == VectorStore.CHARACTER_LEVEL_SESSION },
            )
        }
    }

    @Test
    fun oversizedOwnedSessionListIsIrrelevant() {
        val sessions = (1..RecallWindow.MAX_SESSION_IDS + 1).map { "s$it" }
        val window = resolve(MemoryScope.CHARACTER, sessions)
        assertEquals(setOf("s1"), window.sessionIds)
        assertFalse("会话集合只有当前会话，不存在枚举上限问题", window.sessionLimitExceeded)
        assertEquals(setOf("s1"), window.vectorSessionValues)
    }

    @Test
    fun blankCurrentSessionIdMeansNoSessionBoundary() {
        val window = RecallWindow.resolve("owner", "character", "  ", MemoryScope.SESSION, listOf("s1"))
        assertNull("blank 会话 = 没有会话边界（三通道据此拒绝召回）", window.sessionIds)
        assertNull(window.vectorSessionValues)
        assertFalse(window.sessionLimitExceeded)
    }

    @Test
    fun resolvedScopeIsAlwaysSession() {
        MemoryScope.entries.forEach { scope ->
            assertEquals(MemoryScope.SESSION, resolve(scope).scope)
        }
    }
}
