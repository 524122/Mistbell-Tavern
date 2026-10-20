package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息级孤儿对账的纯函数契约：源 id 分流（[collectVectorSourceIds]）与孤儿判定
 *（[orphanedVectorMessageIds]）。Room 胶水层由启动序列调用，不在 JVM 测试范围。
 */
class VectorOrphanReconcileTest {
    private fun entry(
        messageId: String?,
        contentType: String = "user_message",
    ) = VectorStore.VectorEntry(
        id = "vec_$messageId",
        vector = floatArrayOf(0.1f),
        metadata =
            buildMap {
                if (messageId != null) put("message_id", messageId)
                put("content_type", contentType)
            },
    )

    private fun summaryEntry(messageId: String) = entry(messageId, VectorStore.SUMMARY_CONTENT_TYPE)

    // ---- collectVectorSourceIds：两个命名空间的分流 ----

    @Test
    fun `普通消息与summary分别进入消息与记忆命名空间`() {
        val entries =
            listOf(
                entry("m1"),
                entry("m2"),
                summaryEntry("structured_memory_7"),
                summaryEntry("structured_memory_9"),
            )

        val ids = collectVectorSourceIds(entries)

        assertEquals(setOf("m1", "m2"), ids.messageIds)
        assertEquals(setOf(7L, 9L), ids.memoryIds)
    }

    @Test
    fun `缺message_id与畸形summary_id的条目不收集`() {
        val entries =
            listOf(
                entry(null),
                summaryEntry("structured_memory_abc"),
                summaryEntry("structured_memory_"),
                summaryEntry("structured_memory_1x"),
            )

        val ids = collectVectorSourceIds(entries)

        assertTrue(ids.messageIds.isEmpty())
        assertTrue(ids.memoryIds.isEmpty())
    }

    // ---- orphanedVectorMessageIds：存在性判定 ----

    @Test
    fun `源消息不存在的向量是孤儿_存在的保留`() {
        val entries = listOf(entry("alive"), entry("gone"))

        val orphans = orphanedVectorMessageIds(entries, existingMessageIds = setOf("alive"), existingMemoryIds = emptySet())

        assertEquals(setOf("gone"), orphans)
    }

    @Test
    fun `summary的记忆被删后是孤儿_仍存在则保留`() {
        val entries = listOf(summaryEntry("structured_memory_7"), summaryEntry("structured_memory_8"))

        val orphans = orphanedVectorMessageIds(entries, existingMessageIds = emptySet(), existingMemoryIds = setOf(7L))

        assertEquals(setOf("structured_memory_8"), orphans)
    }

    @Test
    fun `畸形summary_id与缺message_id的条目一律保留`() {
        val entries =
            listOf(
                summaryEntry("structured_memory_abc"),
                summaryEntry("structured_memory_"),
                entry(null),
            )

        val orphans = orphanedVectorMessageIds(entries, existingMessageIds = emptySet(), existingMemoryIds = emptySet())

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `消息命名空间被跳过时消息条目一律保留`() {
        val entries = listOf(entry("gone"))

        val orphans = orphanedVectorMessageIds(entries, existingMessageIds = null, existingMemoryIds = emptySet())

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `记忆命名空间被跳过时summary条目一律保留`() {
        val entries = listOf(summaryEntry("structured_memory_7"))

        val orphans = orphanedVectorMessageIds(entries, existingMessageIds = emptySet(), existingMemoryIds = null)

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `孤儿集合按message_id去重`() {
        val entries = listOf(entry("gone"), entry("gone"))

        val orphans = orphanedVectorMessageIds(entries, existingMessageIds = emptySet(), existingMemoryIds = emptySet())

        assertEquals(setOf("gone"), orphans)
    }
}
