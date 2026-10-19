package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容量淘汰策略：丢掉的向量无法自动重建，所以"丢谁"必须是可回归的。
 *
 * 背景：原实现只按插入顺序丢队首——既不区分内容价值，也把"插入顺序"当成了"新旧"
 * （重复写入同一个 id 不会把它挪到队尾）。
 */
class VectorEvictionTest {
    private fun entry(
        id: String,
        contentType: String = "ai_message",
        createdAt: String? = "2026-01-01T00:00:00Z",
    ) = VectorStore.VectorEntry(
        id = id,
        vector = FloatArray(4) { 1f },
        metadata =
            buildMap {
                put("content_type", contentType)
                createdAt?.let { put("created_at", it) }
            },
    )

    @Test
    fun `未超限时不淘汰任何条目`() {
        val entries = listOf(entry("a"), entry("b"))

        assertEquals(emptyList<String>(), VectorEviction.selectVictims(entries, overflow = 0))
        assertEquals(emptyList<String>(), VectorEviction.selectVictims(entries, overflow = -1))
    }

    @Test
    fun `普通的聊天消息先于结构化记忆被淘汰`() {
        val entries =
            listOf(
                // summary 更旧：若按时间淘汰会先丢它，但它是被蒸馏过的事实，价值更高
                entry("mem", contentType = VectorEviction.CONTENT_TYPE_SUMMARY, createdAt = "2020-01-01T00:00:00Z"),
                entry("msg_old", createdAt = "2024-01-01T00:00:00Z"),
                entry("msg_new", createdAt = "2025-01-01T00:00:00Z"),
            )

        val victims = VectorEviction.selectVictims(entries, overflow = 1)

        assertEquals(listOf("msg_old"), victims)
    }

    @Test
    fun `同一档内丢最旧的而非插入顺序最前的`() {
        val entries =
            listOf(
                // 插入顺序最前，但时间最新——原实现会先丢它，这正是"插入顺序 ≠ 新旧"的坑
                entry("inserted_first_but_new", createdAt = "2025-06-01T00:00:00Z"),
                entry("mid", createdAt = "2024-06-01T00:00:00Z"),
                entry("oldest", createdAt = "2023-06-01T00:00:00Z"),
            )

        val victims = VectorEviction.selectVictims(entries, overflow = 1)

        assertEquals(listOf("oldest"), victims)
    }

    @Test
    fun `多余条目全部淘汰_数量与overflow一致`() {
        val entries = (1..10).map { entry("v$it", createdAt = "2026-01-0${it % 9 + 1}T00:00:00Z") }

        val victims = VectorEviction.selectVictims(entries, overflow = 4)

        assertEquals(4, victims.size)
        assertEquals(victims.size, victims.toSet().size)
        assertTrue(victims.all { id -> entries.any { it.id == id } })
    }

    @Test
    fun `summary 只在普通消息耗尽后才会被动`() {
        val entries =
            listOf(
                entry("mem_a", contentType = VectorEviction.CONTENT_TYPE_SUMMARY, createdAt = "2026-01-01T00:00:00Z"),
                entry("mem_b", contentType = VectorEviction.CONTENT_TYPE_SUMMARY, createdAt = "2026-02-01T00:00:00Z"),
                entry("msg", createdAt = "2026-03-01T00:00:00Z"),
            )

        // 只丢 1 条 → 必须是普通消息
        assertEquals(listOf("msg"), VectorEviction.selectVictims(entries, overflow = 1))
        // 丢 2 条 → 普通消息 + 更旧的 summary
        assertEquals(listOf("msg", "mem_a"), VectorEviction.selectVictims(entries, overflow = 2))
    }

    @Test
    fun `缺少创建时间的历史条目视为最旧_优先淘汰`() {
        val entries =
            listOf(
                entry("legacy", createdAt = null),
                entry("dated", createdAt = "2020-01-01T00:00:00Z"),
            )

        assertEquals(listOf("legacy"), VectorEviction.selectVictims(entries, overflow = 1))
    }

    // ---- 落盘预算：条数上限管不住体积，真正撑爆设备的是字节数 ----

    private fun sized(
        id: String,
        dimension: Int = 4,
        content: String = "",
        contentType: String = "ai_message",
        createdAt: String = "2026-01-01T00:00:00Z",
    ) = VectorStore.VectorEntry(
        id = id,
        vector = FloatArray(dimension) { 1f },
        metadata =
            buildMap {
                put("content_type", contentType)
                put("created_at", createdAt)
                put("content", content)
            },
    )

    @Test
    fun `未超预算时不淘汰任何条目`() {
        val entries = listOf(sized("a"), sized("b"))
        val bytes = entries.sumOf { VectorEviction.serializedSizeOf(it) }

        val plan = VectorEviction.planForBudget(entries, nextBytes = bytes, budgetBytes = bytes + 1)

        assertEquals(emptyList<String>(), plan.victims)
        assertEquals(bytes, plan.bytesAfterEviction)
    }

    @Test
    fun `预算为零表示不限制_不淘汰`() {
        val entries = listOf(sized("a"), sized("b"))
        val bytes = entries.sumOf { VectorEviction.serializedSizeOf(it) }

        assertEquals(
            emptyList<String>(),
            VectorEviction.planForBudget(entries, bytes, budgetBytes = 0).victims,
        )
    }

    @Test
    fun `超预算时按最旧优先淘汰_直到落回预算内`() {
        val old = sized("old", createdAt = "2023-01-01T00:00:00Z")
        val mid = sized("mid", createdAt = "2024-01-01T00:00:00Z")
        val recent = sized("recent", createdAt = "2025-01-01T00:00:00Z")
        val entries = listOf(recent, old, mid) // 插入顺序刻意打乱：淘汰不能按插入顺序
        val total = entries.sumOf { VectorEviction.serializedSizeOf(it) }
        val budget = total - VectorEviction.serializedSizeOf(old) - 1

        val plan = VectorEviction.planForBudget(entries, nextBytes = total, budgetBytes = budget)

        assertTrue("必须真的淘汰了条目", plan.victims.isNotEmpty())
        assertTrue("淘汰后必须落回预算内，否则整文件重写仍会失败", plan.bytesAfterEviction <= budget)
        assertEquals(
            "最旧的必须最先被淘汰",
            "old",
            plan.victims.first(),
        )
        // 剩下的条目按可丢弃顺序排：被淘汰的必须都是"更该丢的"
        val survivors = entries.filter { it.id !in plan.victims }
        assertTrue("最新的条目不该比旧的先走", survivors.any { it.id == "recent" })
    }

    @Test
    fun `预算淘汰也遵守价值优先_summary 不为普通消息让路`() {
        // 三条同体量：summary 最旧，两条普通消息一新一旧。预算只留得下两条。
        val summary =
            sized("summary", contentType = VectorEviction.CONTENT_TYPE_SUMMARY, createdAt = "2020-01-01T00:00:00Z")
        val messageOld = sized("message_old", createdAt = "2021-01-01T00:00:00Z")
        val messageNew = sized("message_new", createdAt = "2026-01-01T00:00:00Z")
        val entries = listOf(messageNew, summary, messageOld)
        val total = entries.sumOf { VectorEviction.serializedSizeOf(it) }
        val budget = total - VectorEviction.serializedSizeOf(summary) - 1

        val plan = VectorEviction.planForBudget(entries, nextBytes = total, budgetBytes = budget)

        assertTrue("被淘汰的必须是普通消息", plan.victims.all { it.startsWith("message_") })
        assertTrue("summary 是蒸馏过的事实，不该为普通消息让路", "summary" !in plan.victims)
    }

    @Test
    fun `必超预算时也保留最后一条_不清空索引`() {
        val entries =
            listOf(
                sized("newest", createdAt = "2026-01-01T00:00:00Z"),
                sized("middle", createdAt = "2024-01-01T00:00:00Z"),
                sized("oldest", createdAt = "2022-01-01T00:00:00Z"),
            )
        val total = entries.sumOf { VectorEviction.serializedSizeOf(it) }

        val plan = VectorEviction.planForBudget(entries, nextBytes = total, budgetBytes = 1)

        assertEquals("永不清空索引", entries.size - 1, plan.victims.size)
        assertEquals(
            "留下的必须是最近的那条（时间越旧越该被淘汰）",
            listOf("newest"),
            entries.map { it.id }.filter { it !in plan.victims },
        )
    }

    @Test
    fun `单条就超预算时保留它_而不是留下空索引`() {
        val only = sized("only", dimension = 1536)
        val bytes = VectorEviction.serializedSizeOf(only)

        val plan = VectorEviction.planForBudget(listOf(only), nextBytes = bytes, budgetBytes = 64)

        assertEquals("只剩这一条时再删就什么都不剩了", emptyList<String>(), plan.victims)
        assertEquals(bytes, plan.bytesAfterEviction)
    }

    @Test
    fun `体量估算随维度与中文元数据增长`() {
        val small = VectorEviction.serializedSizeOf(sized("a", dimension = 4))
        val large = VectorEviction.serializedSizeOf(sized("a", dimension = 1536))
        val withChinese = VectorEviction.serializedSizeOf(sized("a", content = "很长的一段中文记忆正文，用来验证按 UTF-8 字节而不是字符数计量"))

        assertTrue("1536 维必须显著大于 4 维", large > small * 10)
        assertTrue("中文按 UTF-8 计量，必须比空正文大出可观测的量", withChinese > small + 50)
    }
}
