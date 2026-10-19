package com.mistbell.tavern.android.data.vector

import com.mistbell.tavern.android.data.repository.MemoryScope
import com.mistbell.tavern.android.data.repository.RecallWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约：群聊 NPC 召回——向量通道**只按 owner + 当前会话过滤，不按 characterId 过滤**。
 * 同一会话里 NPC 角色（不同 character_id）写入的向量必须与主角色的一起被召回；
 * 其它会话的向量即使角色相同也召不回。窗口携带的 characterId 不参与任何过滤。
 */
class GroupNpcRecallContractTest {
    private fun serviceWithSeededStore(): Pair<VectorMemoryService, FakeVectorStore> {
        val store = FakeVectorStore()
        // 当前会话：主角色 + 两个 NPC 各一条
        store.seed(
            "v_main",
            mapOf(
                "owner_id" to "owner",
                "session_id" to "current",
                "character_id" to "main-char",
                "message_id" to "m1",
                "content" to "主角色说的话",
            ),
        )
        store.seed(
            "v_npc_a",
            mapOf(
                "owner_id" to "owner",
                "session_id" to "current",
                "character_id" to "npc-a",
                "message_id" to "m2",
                "content" to "NPC甲透露的往事",
            ),
        )
        store.seed(
            "v_npc_b",
            mapOf(
                "owner_id" to "owner",
                "session_id" to "current",
                "character_id" to "npc-b",
                "message_id" to "m3",
                "content" to "NPC乙提到的秘密",
            ),
        )
        // 别的会话：角色与主角色相同，也不许召进本会话
        store.seed(
            "v_other_session",
            mapOf(
                "owner_id" to "owner",
                "session_id" to "other-session",
                "character_id" to "main-char",
                "message_id" to "m4",
                "content" to "别的会话的隐私",
            ),
        )
        return VectorMemoryService(store, StubEmbeddingService()) to store
    }

    private fun window() = RecallWindow.resolve("owner", "main-char", "current", MemoryScope.SESSION, listOf("current"))

    @Test
    fun `同会话 NPC 内容与主角色一起被召回_不按 characterId 过滤`() {
        val (service, store) = serviceWithSeededStore()

        val outcome = runBlocking { service.searchRelevantMemories("往事", window(), topK = 10) }

        assertTrue(outcome.sessionScoped)
        val ids = outcome.results.map { it.id }.toSet()
        assertEquals(
            "同会话的三条（含两个 NPC）都必须召回，别的会话的必须排除",
            setOf("v_main", "v_npc_a", "v_npc_b"),
            ids,
        )
        val filters = store.searchFilterLog.single()
        assertFalse("检索过滤器不得包含 character_id", filters.containsKey("character_id"))
        assertEquals("owner", filters["owner_id"])
        assertEquals(setOf("current"), filters["session_id"])
    }

    @Test
    fun `窗口传 NPC 的 characterId 也不会收窄召回`() {
        val (service, _) = serviceWithSeededStore()
        // 窗口携带 NPC 角色 id（旧代码会按它过滤）：会话内记忆下它必须被忽略
        val npcWindow = RecallWindow.resolve("owner", "npc-a", "current", MemoryScope.SESSION, listOf("current"))

        val outcome = runBlocking { service.searchRelevantMemories("秘密", npcWindow, topK = 10) }

        assertEquals(
            "不按窗口里的 characterId 收窄：主角色的内容同样在结果里",
            setOf("v_main", "v_npc_a", "v_npc_b"),
            outcome.results.map { it.id }.toSet(),
        )
    }
}
