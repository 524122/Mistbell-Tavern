package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.vector.FakeVectorStore
import com.mistbell.tavern.android.data.vector.StubEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引重建：这是"被淘汰的向量无法自动重建"的唯一恢复入口，所以幂等性与边界都必须锁住。
 */
class VectorIndexRebuilderTest {
    private fun message(
        id: String,
        role: String = "assistant",
        createdAt: String = "2026-01-01T00:00:00Z",
    ) = MessageEntity(
        id = id,
        sessionId = "s1",
        ownerId = "u1",
        characterId = "c1",
        role = role,
        content = "第 $id 条内容",
        thinking = null,
        createdAt = createdAt,
        memoryIdsJson = "",
        swipesJson = "",
        swipeIndex = 0,
        thinkingSwipesJson = "",
        isRead = true,
    )

    private fun builder(
        store: FakeVectorStore,
        messages: List<MessageEntity>,
        available: Boolean = true,
    ) = VectorIndexRebuilder.forTest(
        vectorMemoryService = VectorMemoryService(store, StubEmbeddingService()),
        source = { _, limit -> messages.take(limit) },
        isEmbeddingAvailable = { available },
    )

    @Test
    fun `未索引的消息全部灌回索引`() {
        val store = FakeVectorStore()
        val messages = listOf(message("m1"), message("m2", role = "user"), message("m3"))

        val result = runBlocking { builder(store, messages).rebuild("u1") }

        assertEquals(3, result.embedded)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(3, result.total)
        assertEquals(3, runBlocking { store.count() })
    }

    @Test
    fun `已在索引中的消息被跳过_重复重建是廉价的`() {
        val store = FakeVectorStore()
        // 种子必须带 fingerprint：首次写入会清掉"不属于当前 embedding 指纹"的向量，
        // 不带指纹的种子会被当成遗留数据删掉，测的就不是"跳过"了
        store.seed(
            "v_existing",
            mapOf(
                "message_id" to "m1",
                "content_type" to "ai_message",
                "embedding_fingerprint" to "stub:model-a:4",
            ),
        )
        val messages = listOf(message("m1"), message("m2"))

        val result = runBlocking { builder(store, messages).rebuild("u1") }

        assertEquals(1, result.embedded)
        assertEquals(1, result.skipped)
        assertEquals("跳过而不是重复写入——否则每次重建都会多一份向量", 2, runBlocking { store.count() })
    }

    @Test
    fun `旧embedding指纹不算已索引_换模型后会重建`() {
        val store = FakeVectorStore()
        store.seed(
            "v_old",
            mapOf(
                "message_id" to "m1",
                "content_type" to "ai_message",
                "embedding_fingerprint" to "old:model:4",
            ),
            injectFingerprint = false,
        )
        val result = runBlocking { builder(store, listOf(message("m1"))).rebuild("u1") }

        assertEquals("旧指纹必须被判为待重建", 1, result.embedded)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `幂等_连跑两次不会重复写入`() {
        val store = FakeVectorStore()
        val messages = listOf(message("m1"), message("m2"), message("m3"))

        val first = runBlocking { builder(store, messages).rebuild("u1") }
        val second = runBlocking { builder(store, messages).rebuild("u1") }

        assertEquals(3, first.embedded)
        assertEquals(0, second.embedded)
        assertEquals(3, second.skipped)
        assertEquals(3, runBlocking { store.count() })
    }

    @Test
    fun `换过 embedding 模型时_旧指纹的向量不算已索引`() {
        val store = FakeVectorStore()
        // 旧指纹覆盖了全部候选：只按 message_id 判断会"全部跳过"，
        // 而检索按新指纹过滤后是空的——用户点了重建，却什么也没发生
        store.seed(
            "v_old",
            mapOf(
                "message_id" to "m1",
                "content_type" to "ai_message",
                "embedding_fingerprint" to "openai:text-embedding-3-small:1536",
            ),
            injectFingerprint = false,
        )
        val messages = listOf(message("m1"), message("m2"))

        val result = runBlocking { builder(store, messages).rebuild("u1") }

        assertEquals("旧指纹必须被判为待重建", 2, result.embedded)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `语义记忆源不可用时直接跳过_不往索引里写伪向量`() {
        val store = FakeVectorStore()
        val messages = listOf(message("m1"), message("m2"))

        val result = runBlocking { builder(store, messages, available = false).rebuild("u1") }

        assertEquals(0, result.total)
        assertEquals(0, runBlocking { store.count() })
    }

    @Test
    fun `单条写入失败不中断整体_失败数如实上报`() {
        val store = FakeVectorStore()
        store.failOnMessageIds += "m2"
        val messages = listOf(message("m1"), message("m2"), message("m3"))

        val result = runBlocking { builder(store, messages).rebuild("u1") }

        assertEquals(2, result.embedded)
        assertEquals(1, result.failed)
        assertEquals(3, result.total)
        assertEquals(2, runBlocking { store.count() })
    }

    @Test
    fun `重建条数受 limit 约束`() {
        val store = FakeVectorStore()
        val messages = (1..5).map { message("m$it") }

        val result = runBlocking { builder(store, messages).rebuild("u1", limit = 2) }

        assertEquals(2, result.total)
        assertEquals(2, runBlocking { store.count() })
    }

    @Test
    fun `content_type 按角色区分_与实时写入路径一致`() {
        val store = FakeVectorStore()
        val messages = listOf(message("m1", role = "user"), message("m2", role = "assistant"))

        runBlocking { builder(store, messages).rebuild("u1") }

        val types = store.entries.values.map { it.metadata["content_type"] }
        assertTrue(types.contains("user_message"))
        assertTrue(types.contains("ai_message"))
    }

    @Test
    fun `逐条上报进度`() {
        val store = FakeVectorStore()
        val messages = listOf(message("m1"), message("m2"), message("m3"))
        val progress = mutableListOf<Pair<Int, Int>>()

        runBlocking {
            builder(store, messages).rebuild("u1") { done, total -> progress.add(done to total) }
        }

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    // ---- 磁盘容量治理：预算已满时不能继续灌（灌进去的会立刻被淘汰，白花 embedding 额度） ----

    private fun capacityAwareBuilder(
        store: FakeVectorStore,
        messages: List<MessageEntity>,
        available: Boolean = true,
        indexFull: () -> Boolean,
    ) = VectorIndexRebuilder.forTest(
        vectorMemoryService = VectorMemoryService(store, StubEmbeddingService()),
        source = { _, limit -> messages.take(limit) },
        isEmbeddingAvailable = { available },
        isIndexFull = indexFull,
    )

    @Test
    fun `索引已满时整个重建直接跳过_不当白烧额度`() {
        val store = FakeVectorStore()
        val messages = listOf(message("m1"), message("m2"))

        val result =
            runBlocking {
                capacityAwareBuilder(store, messages, indexFull = { true }).rebuild("u1")
            }

        assertEquals(0, result.total)
        assertEquals(0, result.embedded)
        assertEquals(0, runBlocking { store.count() })
    }

    @Test
    fun `重建过程中索引写满则提前停手_并在结果里如实上报放弃条数`() {
        val store = FakeVectorStore()
        val messages = (1..5).map { message("m$it") }
        var writes = 0

        val result =
            runBlocking {
                capacityAwareBuilder(store, messages, indexFull = { writes >= 2 })
                    .rebuild("u1") { _, _ -> writes++ }
            }

        assertEquals("写满之后不再继续 embedding", 2, result.embedded)
        assertEquals("放弃的条数必须如实上报，不能悄悄少写", 3, result.declined)
        assertEquals(2, runBlocking { store.count() })
    }

    @Test
    fun `重建写入带替换语义_清空索引留下的删除意图不会把重建全部压住`() {
        val store = FakeVectorStore()
        val messages = listOf(message("m1"), message("m2"))
        // 模拟 clearIndex()：索引里全部 message_id 被登记为已删除（挡住恢复期间的在途写入）
        runBlocking { store.markMessagesDeleted(listOf("m1", "m2")) }

        val result = runBlocking { builder(store, messages).rebuild("u1") }

        assertEquals("重建是替换语义，必须解除此前的删除意图", 2, result.embedded)
        assertEquals(2, runBlocking { store.count() })
    }
}
