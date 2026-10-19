package com.mistbell.tavern.android.data.vector

import com.mistbell.tavern.android.data.repository.MemoryScope
import com.mistbell.tavern.android.data.repository.RecallWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向量召回的作用域投影：窗口恒为 owner + 当前会话——
 * 检索过滤器只带 owner_id + session_id（集合为 {当前会话}），**永不带 character_id**
 * （群聊 NPC 与主角色同属一次对话，不按说话者过滤）；
 * 窗口缺 owner/会话边界 → 拒绝召回（空结果，sessionScoped=false，不触碰存储）；
 * 误传多会话窗口 → 收窄为当前会话，召回照常（sessionScoped=true）。
 */
class VectorMemoryServiceScopeTest {
    private fun window(
        scope: MemoryScope,
        sessions: List<String>,
        ownerId: String = "owner",
        currentSessionId: String = "current",
    ) = RecallWindow.resolve(ownerId, "character", currentSessionId, scope, sessions)

    @Test
    fun sessionSearchFiltersOwnerAndCurrentSessionOnly() {
        val store = FakeVectorStore()
        runBlocking {
            VectorMemoryService(store, StubEmbeddingService()).searchRelevantMemories(
                "query",
                window(MemoryScope.SESSION, listOf("current")),
                5,
            )
        }
        val filters = store.searchFilterLog.single()
        assertEquals("owner", filters["owner_id"])
        assertEquals(setOf("current"), filters["session_id"])
        assertFalse(
            "向量召回不按角色过滤（群聊 NPC 与主角色同属一次对话）",
            filters.containsKey("character_id"),
        )
    }

    @Test
    fun characterScopeSearchIsNormalizedToCurrentSession() {
        val store = FakeVectorStore()
        runBlocking {
            VectorMemoryService(store, StubEmbeddingService()).searchRelevantMemories(
                "query",
                window(MemoryScope.CHARACTER, listOf("current", "other")),
                5,
            )
        }
        val filters = store.searchFilterLog.single()
        assertEquals(setOf("current"), filters["session_id"])
        assertFalse(filters.containsKey("character_id"))
    }

    @Test
    fun ownerScopeSearchIsNormalizedToCurrentSession() {
        val store = FakeVectorStore()
        runBlocking {
            VectorMemoryService(store, StubEmbeddingService()).searchRelevantMemories(
                "query",
                window(MemoryScope.OWNER, listOf("current", "other")),
                5,
            )
        }
        val filters = store.searchFilterLog.single()
        assertEquals(setOf("current"), filters["session_id"])
        assertFalse(filters.containsKey("character_id"))
        assertTrue(
            "向量会话值不得包含 cross_session 角色级哨兵",
            (filters["session_id"] as Set<*>).none { it == VectorStore.CHARACTER_LEVEL_SESSION },
        )
    }

    @Test
    fun multiSessionWindowIsNarrowedToCurrentSessionAndSearchStillRuns() {
        val store = FakeVectorStore()
        store.seed(
            "v_npc",
            mapOf(
                "owner_id" to "owner",
                "session_id" to "current",
                "character_id" to "npc-a",
                "message_id" to "m1",
                "content" to "NPC 说的往事",
            ),
        )
        val sessions = (1..RecallWindow.MAX_SESSION_IDS + 1).map { "s$it" } + "current"
        val outcome =
            runBlocking {
                VectorMemoryService(store, StubEmbeddingService()).searchRelevantMemories(
                    "query",
                    window(MemoryScope.CHARACTER, sessions),
                    5,
                )
            }
        assertTrue("多会话窗口只收窄、不拒绝召回", outcome.sessionScoped)
        val filters = store.searchFilterLog.single()
        assertEquals("收窄为当前会话", setOf("current"), filters["session_id"])
        assertEquals("同会话内容照常召回", listOf("v_npc"), outcome.results.map { it.id })
    }

    @Test
    fun blankOwnerRefusesRecall() {
        val store = FakeVectorStore()
        val outcome =
            runBlocking {
                VectorMemoryService(store, StubEmbeddingService()).searchRelevantMemories(
                    "query",
                    window(MemoryScope.SESSION, listOf("current"), ownerId = "  "),
                    5,
                )
            }
        assertTrue(outcome.results.isEmpty())
        assertFalse(outcome.sessionScoped)
        assertTrue("拒绝召回时不得触碰存储", store.searchFilterLog.isEmpty())
    }

    @Test
    fun blankCurrentSessionRefusesRecall() {
        val store = FakeVectorStore()
        val outcome =
            runBlocking {
                VectorMemoryService(store, StubEmbeddingService()).searchRelevantMemories(
                    "query",
                    window(MemoryScope.SESSION, listOf("current"), currentSessionId = ""),
                    5,
                )
            }
        assertTrue(outcome.results.isEmpty())
        assertFalse(outcome.sessionScoped)
        assertTrue("拒绝召回时不得触碰存储", store.searchFilterLog.isEmpty())
    }

    @Test
    fun deleteRegistersMessageIdsBeforeLateEmbedding() {
        val store = FakeVectorStore()
        runBlocking {
            val service = VectorMemoryService(store, StubEmbeddingService())
            service.deleteSessionVectors("owner", "session", messageIds = listOf("message"))
            assertFalse(store.canIndexMessage("message"))
            assertNull(service.storeMessage("late", "owner", "character", "session", "message", "ai_message"))
        }
    }
}
