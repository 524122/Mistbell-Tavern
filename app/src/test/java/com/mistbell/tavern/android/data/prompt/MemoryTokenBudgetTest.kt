package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.api.model.MAX_MEMORY_CONTENT_CHARS
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.api.model.truncateMemoryContent
import com.mistbell.tavern.android.data.vector.VectorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆注入的 token 预算：条数上限挡不住长正文，预算才是硬约束。
 *
 * 背景：注入条数有上限（选择器最多 10 条）却没有任何长度约束，10 条长记忆足以顶掉整段历史；
 * 而历史选择器的预算有 `coerceAtLeast(256)` 兜底，历史被压到最低也救不回超长提示词。
 */
class MemoryTokenBudgetTest {
    private fun memory(
        id: Long,
        content: String,
        type: String = "fact",
        importance: Int = 5,
    ) = StructuredMemory(
        id = id,
        ownerId = "u1",
        characterId = "c1",
        sessionId = "s1",
        memoryType = type,
        title = null,
        content = content,
        structuredData = null,
        importance = importance,
        tags = emptyList(),
        keywords = emptyList(),
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        lastAccessedAt = null,
        accessCount = 0,
        relatedMessageIds = emptyList(),
        sourceType = "auto_extract",
    )

    @Test
    fun `超长正文按上限截断并留下省略号`() {
        val long = "字".repeat(MAX_MEMORY_CONTENT_CHARS + 50)

        val cut = long.truncateMemoryContent()

        assertEquals(MAX_MEMORY_CONTENT_CHARS + 1, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun `未超长的正文原样保留`() {
        val text = "很短的一条记忆"

        assertEquals(text, text.truncateMemoryContent())
    }

    @Test
    fun `记忆块超预算时按顺序裁掉尾部`() {
        val memories = (1..10).map { memory(id = it.toLong(), content = "第${it}条记忆内容".repeat(10)) }

        val (text, injected) = PromptBuilder.formatStructuredMemoryContext(memories, budgetTokens = 100)

        assertTrue("必须真的裁掉了内容", injected.size < memories.size)
        // 选择器已排好序，先到先得 = 保留列表前缀（最不重要的尾巴先被裁掉）
        assertEquals(memories.take(injected.size).map { it.id }, injected.map { it.id })
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `跨类型裁剪时高重要度优先_不被低重要度类型挤掉`() {
        // 低重要度的 preference 排在"用户信息"组（渲染顺序里最靠前），
        // 高重要度的 event 排在"重要事件"组。若按分组顺序扣预算，前者会先占满，
        // 后者整组被挡在门外——这正是"先分组后扣预算"的错
        val memories =
            listOf(
                memory(id = 1, content = "低重要度偏好".repeat(20), type = "preference", importance = 2),
                memory(id = 2, content = "高重要度事件", type = "event", importance = 10),
            )

        val (_, injected) = PromptBuilder.formatStructuredMemoryContext(memories, budgetTokens = 60)

        assertEquals(listOf(2L), injected.map { it.id })
    }

    @Test
    fun `预算小到放不下任何一条时返回空块`() {
        val memories = listOf(memory(id = 1, content = "很长的记忆".repeat(50)))

        val (text, injected) = PromptBuilder.formatStructuredMemoryContext(memories, budgetTokens = 1)

        assertEquals(0, injected.size)
        assertEquals("", text)
    }

    @Test
    fun `预算随上下文缩放并钳制在上下限内`() {
        assertEquals(120, PromptBuilder.memoryBlockBudget(1))
        assertEquals(1200, PromptBuilder.memoryBlockBudget(1_000_000))
        assertEquals(1024, PromptBuilder.memoryBlockBudget(4096))
    }

    @Test
    fun `向量召回行按长度上限截断`() {
        val long = "很长的历史消息".repeat(200)
        val result = VectorStore.SearchResult(id = "v1", score = 0.9f, content = long, metadata = emptyMap())

        val text = PromptBuilder.buildVectorMemoryContextForPrompt(listOf(result), emptyList())

        assertTrue("召回行必须被截断", text.length < long.length)
        assertTrue(text.contains("…"))
    }
}
