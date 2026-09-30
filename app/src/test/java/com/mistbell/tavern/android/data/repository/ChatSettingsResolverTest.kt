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

    @Test
    fun `全局默认长期记忆仅显式开才为真`() {
        assertTrue(ChatSettingsResolver.globalDefaultLtmEnabled("1"))
        assertFalse(ChatSettingsResolver.globalDefaultLtmEnabled("0"))
        assertFalse(ChatSettingsResolver.globalDefaultLtmEnabled(null))
        assertFalse(ChatSettingsResolver.globalDefaultLtmEnabled("true"))
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
}
