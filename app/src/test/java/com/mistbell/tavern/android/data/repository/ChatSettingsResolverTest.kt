package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.entity.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级设置三态解析的优先级回归测试：
 * 会话显式值 > 全局当前值 > 内置兜底（SETTINGS.md 分层原则）。
 * 防止回退为"建会话时快照全局默认"的旧语义。
 */
class ChatSettingsResolverTest {
    private fun session(
        ltm: Boolean? = null,
        contextTokens: Int? = null,
    ) = SessionEntity(
        id = "s1",
        ownerId = "local-user",
        characterId = "c1",
        title = "t",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        messageCount = 0,
        providerId = "",
        modelId = "",
        worldBookId = "",
        summaryJson = "",
        enableLongTermMemory = ltm,
        contextTokenLimit = contextTokens,
    )

    // ---- 上下文长度 ----

    @Test
    fun `会话显式上下文长度压过全局默认`() {
        assertEquals(
            8192,
            ChatSettingsResolver.contextTokenLimit(session(contextTokens = 8192), 4096),
        )
    }

    @Test
    fun `会话未设置时跟随全局默认`() {
        assertEquals(
            16384,
            ChatSettingsResolver.contextTokenLimit(session(contextTokens = null), 16384),
        )
    }

    @Test
    fun `会话实体为空时回落全局默认`() {
        assertEquals(
            4096,
            ChatSettingsResolver.contextTokenLimit(null, 4096),
        )
    }

    @Test
    fun `会话显式值越界时钳制到合法区间`() {
        assertEquals(
            ChatSettingsResolver.CONTEXT_TOKEN_LIMIT_MAX,
            ChatSettingsResolver.contextTokenLimit(session(contextTokens = 9_999_999), 4096),
        )
        assertEquals(
            ChatSettingsResolver.CONTEXT_TOKEN_LIMIT_MIN,
            ChatSettingsResolver.contextTokenLimit(session(contextTokens = 1), 4096),
        )
    }

    @Test
    fun `全局默认越界时同样钳制`() {
        assertEquals(
            ChatSettingsResolver.CONTEXT_TOKEN_LIMIT_MAX,
            ChatSettingsResolver.contextTokenLimit(session(contextTokens = null), 9_999_999),
        )
    }

    @Test
    fun `全局默认原始值非法或缺省时回退内置兜底`() {
        assertEquals(4096, ChatSettingsResolver.globalDefaultContextTokens(null))
        assertEquals(4096, ChatSettingsResolver.globalDefaultContextTokens(""))
        assertEquals(4096, ChatSettingsResolver.globalDefaultContextTokens("abc"))
        assertEquals(8192, ChatSettingsResolver.globalDefaultContextTokens("8192"))
        assertEquals(
            ChatSettingsResolver.CONTEXT_TOKEN_LIMIT_MIN,
            ChatSettingsResolver.globalDefaultContextTokens("1"),
        )
    }

    // ---- 长期记忆 ----

    @Test
    fun `会话显式长期记忆开或关均压过全局默认`() {
        assertTrue(ChatSettingsResolver.longTermMemoryEnabled(session(ltm = true), false))
        assertFalse(ChatSettingsResolver.longTermMemoryEnabled(session(ltm = false), true))
    }

    @Test
    fun `会话未设置时跟随全局默认开或关`() {
        assertTrue(ChatSettingsResolver.longTermMemoryEnabled(session(ltm = null), true))
        assertFalse(ChatSettingsResolver.longTermMemoryEnabled(session(ltm = null), false))
    }

    @Test
    fun `长期记忆会话实体为空时回落全局默认`() {
        assertTrue(ChatSettingsResolver.longTermMemoryEnabled(null, true))
    }

    // ---- S2 向量记忆召回设置 ----

    @Test
    fun `召回条数非法缺省回退五且越界钳制`() {
        assertEquals(5, ChatSettingsResolver.memoryRecallTopK(null))
        assertEquals(5, ChatSettingsResolver.memoryRecallTopK("abc"))
        assertEquals(8, ChatSettingsResolver.memoryRecallTopK("8"))
        assertEquals(1, ChatSettingsResolver.memoryRecallTopK("0"))
        assertEquals(20, ChatSettingsResolver.memoryRecallTopK("99"))
    }

    @Test
    fun `相似度阈值非法缺省回退默认且越界钳制`() {
        assertEquals(0.35f, ChatSettingsResolver.memorySimilarityThreshold(null), 0.001f)
        assertEquals(0.35f, ChatSettingsResolver.memorySimilarityThreshold("x"), 0.001f)
        assertEquals(0.6f, ChatSettingsResolver.memorySimilarityThreshold("0.6"), 0.001f)
        assertEquals(0.05f, ChatSettingsResolver.memorySimilarityThreshold("0"), 0.001f)
        assertEquals(0.95f, ChatSettingsResolver.memorySimilarityThreshold("2"), 0.001f)
    }

    // ---- 世界书全局设置（对齐酒馆 world_info_depth=2 / world_info_budget=25） ----

    @Test
    fun `世界书扫描深度非法缺省回退二且越界钳制`() {
        assertEquals(2, ChatSettingsResolver.worldInfoScanDepth(null))
        assertEquals(2, ChatSettingsResolver.worldInfoScanDepth("abc"))
        assertEquals(6, ChatSettingsResolver.worldInfoScanDepth("6"))
        assertEquals(1, ChatSettingsResolver.worldInfoScanDepth("0"))
        assertEquals(50, ChatSettingsResolver.worldInfoScanDepth("999"))
    }

    @Test
    fun `世界书预算百分比非法缺省回退二十五且越界钳制_零为关闭`() {
        assertEquals(25, ChatSettingsResolver.worldInfoBudgetPercent(null))
        assertEquals(25, ChatSettingsResolver.worldInfoBudgetPercent("x"))
        assertEquals(10, ChatSettingsResolver.worldInfoBudgetPercent("10"))
        assertEquals(0, ChatSettingsResolver.worldInfoBudgetPercent("0"))
        assertEquals(50, ChatSettingsResolver.worldInfoBudgetPercent("99"))
    }

    @Test
    fun `世界书深度插入开关缺省为开_仅0为关`() {
        assertTrue(ChatSettingsResolver.worldInfoInHistory(null))
        assertTrue(ChatSettingsResolver.worldInfoInHistory("1"))
        assertTrue(ChatSettingsResolver.worldInfoInHistory("x"))
        assertFalse(ChatSettingsResolver.worldInfoInHistory("0"))
    }

    @Test
    fun `世界书递归扫描缺省为关_仅1为开`() {
        assertFalse(ChatSettingsResolver.worldInfoRecursive(null))
        assertFalse(ChatSettingsResolver.worldInfoRecursive("0"))
        assertFalse(ChatSettingsResolver.worldInfoRecursive("x"))
        assertTrue(ChatSettingsResolver.worldInfoRecursive("1"))
    }

    @Test
    fun `世界书预算上限缺省不封顶`() {
        assertEquals(0, ChatSettingsResolver.worldInfoBudgetCap(null))
        assertEquals(0, ChatSettingsResolver.worldInfoBudgetCap("x"))
        assertEquals(0, ChatSettingsResolver.worldInfoBudgetCap("-5"))
        assertEquals(500, ChatSettingsResolver.worldInfoBudgetCap("500"))
        assertEquals(1_000_000, ChatSettingsResolver.worldInfoBudgetCap("99999999"))
    }

    @Test
    fun `世界书最小激活数与加深上限缺省关闭`() {
        assertEquals(0, ChatSettingsResolver.worldInfoMinActivations(null))
        assertEquals(0, ChatSettingsResolver.worldInfoMinActivations("x"))
        assertEquals(3, ChatSettingsResolver.worldInfoMinActivations("3"))
        assertEquals(0, ChatSettingsResolver.worldInfoMinActivationsDepthMax(null))
        assertEquals(9, ChatSettingsResolver.worldInfoMinActivationsDepthMax("9"))
    }

    @Test
    fun `世界书扫描带名字前缀缺省为开_仅0为关`() {
        assertTrue(ChatSettingsResolver.worldInfoIncludeNames(null))
        assertTrue(ChatSettingsResolver.worldInfoIncludeNames("1"))
        assertFalse(ChatSettingsResolver.worldInfoIncludeNames("0"))
    }
}
