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
 * 待抽取缓冲的落盘行为。
 *
 * 背景：缓冲此前只活在进程内存里，未攒满一批的轮次会随进程被杀一起消失。这些用例锁住
 * "换个实例（≈ 进程重启）还能接着攒"这一条——它正是修复的全部价值所在。
 */
class PendingExtractionStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pending-extraction-test").toFile()
        file = File(dir, PendingExtractionStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 每次 new 都代表"另一个实例"；指向同一文件即模拟进程重启 */
    private fun store() = PendingExtractionStore(file)

    private fun turn(
        n: Int,
        userId: String = "m$n-user",
        assistantId: String = "m$n-ai",
    ) = PendingExtractionTurn("用户第${n}轮", "角色第${n}轮", listOf(userId, assistantId))

    private fun enqueue(
        store: PendingExtractionStore,
        n: Int,
        sessionId: String = "s1",
    ) = store.enqueue(
        ownerId = "u1",
        characterId = "c1",
        sessionId = sessionId,
        providerId = "p1",
        turn = turn(n),
    )

    @Test
    fun `攒满一批即出快照并清空该会话`() {
        val store = store()

        assertNull(enqueue(store, 1))
        assertNull(enqueue(store, 2))
        val batch = enqueue(store, 3)

        assertNotNull(batch)
        assertEquals(3, batch!!.turns.size)
        assertEquals("s1", batch.sessionId)
        assertEquals(0, store.bufferedSessionCount())
    }

    @Test
    fun `未攒满的轮次在进程重启后仍在`() {
        enqueue(store(), 1)
        enqueue(store(), 2)

        // 换实例 = 进程重启；此前这些轮次会凭空消失
        val batches = store().drainAll()

        assertEquals(1, batches.size)
        assertEquals(2, batches.single().turns.size)
        assertEquals(listOf("用户第1轮", "用户第2轮"), batches.single().turns.map { it.userMessage })
    }

    @Test
    fun `排空后落盘状态同步清空`() {
        val active = store()
        enqueue(active, 1)

        val batch = active.drainAll().single()
        // 出队后任务仍处于 inflight，只有成功确认才从持久化状态删除。
        active.ack(batch.batchId)
        assertTrue("确认成功后重启不应又冒出旧轮次", store().drainAll().isEmpty())
    }

    @Test
    fun `修剪剔除引用了已删消息的整轮`() {
        val store = store()
        enqueue(store, 1)
        enqueue(store, 2)

        store.prune("s1", remainingMessageIds = setOf("m1-user", "m1-ai"))

        val turns = store().drainAll().single().turns
        assertEquals(listOf("用户第1轮"), turns.map { it.userMessage })
    }

    @Test
    fun `丢弃会话后落盘里也不再有它`() {
        val store = store()
        enqueue(store, 1)
        enqueue(store, 1, sessionId = "s2")

        store.discard("s1")

        val batches = store().drainAll()
        assertEquals(listOf("s2"), batches.map { it.sessionId })
    }

    @Test
    fun `多会话各自攒批_互不影响`() {
        val store = store()
        assertNull(enqueue(store, 1, sessionId = "s1"))
        assertNull(enqueue(store, 1, sessionId = "s2"))

        val batches = store().drainAll().associateBy { it.sessionId }

        assertEquals(setOf("s1", "s2"), batches.keys)
        assertEquals("用户第1轮", batches.getValue("s1").turns.single().userMessage)
    }

    @Test
    fun `坏文件不致命_从空缓冲重新开始`() {
        file.writeText("{ 这不是合法 JSON")

        val store = store()

        assertEquals(0, store.bufferedSessionCount())
        assertNull(enqueue(store, 1))
        assertEquals(1, store().drainAll().size)
    }

    @Test
    fun `失败批次退回待处理_重启后可再次排空`() {
        enqueue(store(), 1)
        val batch = store().drainAll().single()

        store().nack(batch.batchId)

        val retried = store().drainAll().single()
        assertEquals(batch.turns, retried.turns)
        assertTrue(retried.batchId != batch.batchId)
    }
}
