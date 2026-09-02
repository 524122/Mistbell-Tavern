package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 群聊历史组装测试（JVM）：annotateGroupHistory 为每条历史消息加说话方前缀。
 *
 * 覆盖：AI 消息按 character_id 加名字前缀、用户消息加 {{user}} 值前缀、
 * 未知 character_id 的兜底（id 前 4 位）、既无映射又超短 id 的通用兜底、think 块剥离。
 */
class PromptBuilderGroupHistoryTest {
    private fun message(
        id: String,
        role: String,
        content: String,
        characterId: String = "char-1",
    ) = MessageEntity(
        id = id,
        sessionId = "sess-1",
        ownerId = "local-user",
        characterId = characterId,
        role = role,
        content = content,
        thinking = null,
        createdAt = "2026-01-01T00:00:00Z",
        memoryIdsJson = "",
        swipesJson = "",
        swipeIndex = 0,
        thinkingSwipesJson = "",
    )

    private val speakerNames = mapOf("npc-1" to "艾莉丝", "npc-2" to "Bob")

    @Test
    fun `AI 消息按 character_id 加说话方名字前缀`() {
        val result =
            PromptBuilder.annotateGroupHistory(
                history = listOf(message("m1", "assistant", "大家好。", characterId = "npc-2")),
                speakerNames = speakerNames,
                userName = "玩家",
            )
        assertEquals(1, result.size)
        assertEquals("assistant", result[0].role)
        assertEquals("Bob: 大家好。", result[0].content)
    }

    @Test
    fun `用户消息加 user 名前缀`() {
        val result =
            PromptBuilder.annotateGroupHistory(
                history = listOf(message("m1", "user", "你们好")),
                speakerNames = speakerNames,
                userName = "玩家",
            )
        assertEquals("user", result[0].role)
        assertEquals("玩家: 你们好", result[0].content)
    }

    @Test
    fun `未知 character_id 用 id 前 4 位兜底`() {
        val result =
            PromptBuilder.annotateGroupHistory(
                history = listOf(message("m1", "assistant", "我是谁？", characterId = "npc-9-extra")),
                speakerNames = speakerNames,
                userName = "玩家",
            )
        // npc-9 不在 speakerNames 里，取 id 前 4 位 "npc-"
        assertEquals("npc-: 我是谁？", result[0].content)
    }

    @Test
    fun `既无映射又过短的 character_id 用通用名兜底`() {
        val result =
            PromptBuilder.annotateGroupHistory(
                history = listOf(message("m1", "assistant", "内容", characterId = "x")),
                speakerNames = speakerNames,
                userName = "玩家",
            )
        assertEquals("角色: 内容", result[0].content)
    }

    @Test
    fun `历史中的 think 块被剥离后再加前缀`() {
        val result =
            PromptBuilder.annotateGroupHistory(
                history = listOf(message("m1", "assistant", "<think>推理过程</think>正式发言。", characterId = "npc-1")),
                speakerNames = speakerNames,
                userName = "玩家",
            )
        assertEquals("艾莉丝: 正式发言。", result[0].content)
    }

    @Test
    fun `多条消息保持原有顺序与角色`() {
        val result =
            PromptBuilder.annotateGroupHistory(
                history =
                    listOf(
                        message("m1", "user", "开场"),
                        message("m2", "assistant", "回应", characterId = "npc-1"),
                        message("m3", "assistant", "接力", characterId = "npc-2"),
                    ),
                speakerNames = speakerNames,
                userName = "玩家",
            )
        assertEquals(
            listOf(
                "玩家: 开场",
                "艾莉丝: 回应",
                "Bob: 接力",
            ),
            result.map { it.content },
        )
        assertEquals(listOf("user", "assistant", "assistant"), result.map { it.role })
    }

    @Test
    fun `群聊当前用户消息与历史格式一致加 user 名前缀`() {
        // 跨代理契约 4：群聊模式【当前用户消息】加 "{user}: " 前缀，与历史行格式对齐
        assertEquals(
            "玩家: 今天天气如何？",
            PromptBuilder.groupCurrentUserMessage(userName = "玩家", renderedUserMessage = "今天天气如何？"),
        )
    }

    @Test
    fun `群聊当前用户消息前缀保留渲染后的正文原样`() {
        // 正文（已过宏渲染）不做二次加工，仅拼接前缀
        assertEquals(
            "User: {{char}}已渲染为正文",
            PromptBuilder.groupCurrentUserMessage(userName = "User", renderedUserMessage = "{{char}}已渲染为正文"),
        )
    }
}
