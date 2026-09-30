package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书插入位置规划（WorldBookPlacement）纯函数单测。
 * 语义：depth=0 按 insertPosition 锚定提示词区；depth≥1 为 @D 模式（历史倒数第 D 条之前）；
 * disable 条目一律不参与。
 */
class WorldBookPlacementTest {
    private fun entry(
        id: String,
        insertPosition: String = "before_prompt",
        depth: Int = 0,
        depthRole: String = "system",
        disable: Boolean = false,
    ) = WorldBookEntryEntity(
        id = id,
        bookId = "book",
        comment = id,
        keysJson = "[]",
        content = "c$id",
        constant = false,
        disable = disable,
        order = 0,
        insertPosition = insertPosition,
        depth = depth,
        depthRole = depthRole,
    )

    @Test
    fun `depth为0按insertPosition分前后`() {
        val entries =
            listOf(
                entry("a", insertPosition = "before_prompt"),
                entry("b", insertPosition = "after_prompt"),
            )
        val plan = WorldBookPlacement.plan(entries)
        assertEquals(listOf("a"), plan.beforePrompt.map { it.id })
        assertEquals(listOf("b"), plan.afterPrompt.map { it.id })
        assertTrue(plan.byDepth.isEmpty())
    }

    @Test
    fun `depth大于0进byDepth且忽略insertPosition`() {
        val entries =
            listOf(
                entry("deep", insertPosition = "before_prompt", depth = 3),
                entry("anchored", insertPosition = "after_prompt", depth = 0),
            )
        val plan = WorldBookPlacement.plan(entries)
        assertTrue(plan.beforePrompt.isEmpty())
        assertEquals(listOf("anchored"), plan.afterPrompt.map { it.id })
        assertEquals(listOf("deep"), plan.byDepth[3 to "system"]?.map { it.id })
    }

    @Test
    fun `byDepth按深度升序排列`() {
        val entries =
            listOf(
                entry("d5", depth = 5),
                entry("d1", depth = 1),
                entry("d3", depth = 3),
            )
        val plan = WorldBookPlacement.plan(entries)
        assertEquals(
            listOf(1 to "system", 3 to "system", 5 to "system"),
            plan.byDepth.keys.toList(),
        )
    }

    @Test
    fun `同深度多条归为一组`() {
        val entries = listOf(entry("x", depth = 2), entry("y", depth = 2))
        val plan = WorldBookPlacement.plan(entries)
        assertEquals(listOf("x", "y"), plan.byDepth[2 to "system"]?.map { it.id })
    }

    @Test
    fun `同深度不同角色分成两组`() {
        val entries =
            listOf(
                entry("sys", depth = 2, depthRole = "system"),
                entry("usr", depth = 2, depthRole = "user"),
                entry("asst", depth = 2, depthRole = "assistant"),
            )
        val plan = WorldBookPlacement.plan(entries)
        assertEquals(3, plan.byDepth.size)
        assertEquals(listOf("sys"), plan.byDepth[2 to "system"]?.map { it.id })
        assertEquals(listOf("usr"), plan.byDepth[2 to "user"]?.map { it.id })
        assertEquals(listOf("asst"), plan.byDepth[2 to "assistant"]?.map { it.id })
    }

    @Test
    fun `禁用条目一律不参与`() {
        val entries =
            listOf(
                entry("offBefore", insertPosition = "before_prompt", disable = true),
                entry("offAfter", insertPosition = "after_prompt", disable = true),
                entry("offDepth", depth = 4, disable = true),
                entry("on", insertPosition = "before_prompt"),
            )
        val plan = WorldBookPlacement.plan(entries)
        assertEquals(listOf("on"), plan.beforePrompt.map { it.id })
        assertTrue(plan.afterPrompt.isEmpty())
        assertTrue(plan.byDepth.isEmpty())
    }

    @Test
    fun `空列表得到空规划`() {
        val plan = WorldBookPlacement.plan(emptyList())
        assertTrue(plan.beforePrompt.isEmpty())
        assertTrue(plan.afterPrompt.isEmpty())
        assertTrue(plan.emBefore.isEmpty())
        assertTrue(plan.emAfter.isEmpty())
        assertTrue(plan.anBefore.isEmpty())
        assertTrue(plan.anAfter.isEmpty())
        assertTrue(plan.byDepth.isEmpty())
    }

    @Test
    fun `作者注释前后条目各归其组`() {
        val entries =
            listOf(
                entry("anPre", insertPosition = "an_before"),
                entry("anPost", insertPosition = "an_after"),
                entry("charPre", insertPosition = "before_prompt"),
                entry("charPost", insertPosition = "after_prompt"),
            )
        val plan = WorldBookPlacement.plan(entries)
        assertEquals(listOf("anPre"), plan.anBefore.map { it.id })
        assertEquals(listOf("anPost"), plan.anAfter.map { it.id })
        assertEquals(listOf("charPre"), plan.beforePrompt.map { it.id })
        assertEquals(listOf("charPost"), plan.afterPrompt.map { it.id })
    }

    @Test
    fun `示例消息前后条目各归其组`() {
        val entries =
            listOf(
                entry("emPre", insertPosition = "em_before"),
                entry("emPost", insertPosition = "em_after"),
            )
        val plan = WorldBookPlacement.plan(entries)
        assertEquals(listOf("emPre"), plan.emBefore.map { it.id })
        assertEquals(listOf("emPost"), plan.emAfter.map { it.id })
        assertTrue(plan.afterPrompt.isEmpty())
    }
}
