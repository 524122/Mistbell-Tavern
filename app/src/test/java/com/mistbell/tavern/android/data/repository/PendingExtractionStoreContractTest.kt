package com.mistbell.tavern.android.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 契约：PendingExtractionStore 的会话级丢弃与签名兼容——
 * 1) discard 必须**同时**清掉 pending 缓冲和 inflight 批次（旧实现 `||` 短路会在
 *    pending 存在时漏删 inflight，重启后已丢弃的会话又冒出抽取批次）；
 * 2) 带 messageSignatures 的轮次持久化后重启往返不丢；
 * 3) 旧格式（无 messageSignatures 字段）加载降级为 emptyList，不炸、不丢轮次。
 */
class PendingExtractionStoreContractTest {
    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pending-extraction-contract").toFile()
        file = File(dir, PendingExtractionStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = PendingExtractionStore(file)

    private fun signedTurn(
        n: Int,
        userId: String = "m$n-user",
        assistantId: String = "m$n-ai",
    ) = PendingExtractionTurn(
        userMessage = "用户第${n}轮",
        assistantMessage = "角色第${n}轮",
        messageIds = listOf(userId, assistantId),
        messageSignatures = listOf("sig-$userId", "sig-$assistantId"),
    )

    private fun enqueue(
        store: PendingExtractionStore,
        turn: PendingExtractionTurn,
        sessionId: String = "s1",
    ) = store.enqueue(
        ownerId = "u1",
        characterId = "c1",
        sessionId = sessionId,
        providerId = "p1",
        turn = turn,
    )

    @Test
    fun `pending 和 inflight 同时存在时 discard 全清`() {
        val store = store()
        // inflight：攒批出队（drainAll）后未 ack
        enqueue(store, PendingExtractionTurn("用户1", "角色1", listOf("m1-user", "m1-ai")))
        val batch = store.drainAll().single()
        // pending：又一批还没攒满
        enqueue(store, signedTurn(2))
        // 丢弃：pending + inflight 都必须消失
        store.discard("s1")

        assertTrue("pending 必须清空", store().drainAll().isEmpty())
        // inflight 若残留，ack 会成功（返回前有东西可删）——这里用 nack 验证：
        // nack 会把批次退回 pending，若 discard 漏删 inflight，下面就能排空出一批
        store().nack(batch.batchId)
        assertTrue(
            "inflight 批次必须一并清掉（旧 || 短路 bug：pending 存在时漏删 inflight）",
            store().drainAll().isEmpty(),
        )
    }

    @Test
    fun `带签名的批次持久化往返不丢`() {
        val store = store()
        enqueue(store, signedTurn(1))
        enqueue(store, signedTurn(2))

        // 换实例 = 进程重启
        val batches = store().drainAll()
        assertEquals(1, batches.size)
        assertEquals(
            listOf(listOf("sig-m1-user", "sig-m1-ai"), listOf("sig-m2-user", "sig-m2-ai")),
            batches.single().turns.map { it.messageSignatures },
        )
    }

    @Test
    fun `攒满出队的批次签名原样保留`() {
        val store = PendingExtractionStore(file, batchTurns = 2)
        assertNull(enqueue(store, signedTurn(1)))
        val batch = enqueue(store, signedTurn(2))
        assertNotNull(batch)
        assertEquals(
            listOf("sig-m1-user", "sig-m1-ai"),
            batch!!.turns.first().messageSignatures,
        )
    }

    @Test
    fun `旧格式无签名的持久化文件加载降级为空签名`() {
        // 手写一个"旧版本"持久化文件：turns 里没有 messageSignatures 字段
        file.writeText(
            """{"sessions":[{"sessionId":"s1","ownerId":"u1","characterId":"c1",""" +
                """"providerId":"p1","turns":[""" +
                """{"userMessage":"旧用户轮","assistantMessage":"旧角色轮",""" +
                """"messageIds":["old-1","old-2"]}]}],"inflight":[]}""",
        )

        val batches = store().drainAll()

        assertEquals(1, batches.size)
        val turn = batches.single().turns.single()
        assertEquals("旧用户轮", turn.userMessage)
        assertEquals("消息 id 必须原样读回", listOf("old-1", "old-2"), turn.messageIds)
        assertTrue("缺失的签名字段必须默认 emptyList", turn.messageSignatures.isEmpty())
    }
}
