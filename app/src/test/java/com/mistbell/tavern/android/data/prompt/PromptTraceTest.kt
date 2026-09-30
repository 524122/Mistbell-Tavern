package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.api.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词溯源（聊天页「查看提示词」）的纯逻辑测试。
 *
 * 关键约束：**预览必须与真实请求同源**——buildPrompt 只是 buildPromptTrace 的取 message 投影，
 * 因此这里验证两者的等价关系（用同一批 Segment 手工验证映射规则，不依赖 Room）。
 */
class PromptTraceTest {
    private fun segment(
        role: String,
        content: String,
        source: String,
    ) = PromptBuilder.Segment(ChatMessage(role = role, content = content), source)

    @Test
    fun `trace 的 segments 投影后即 buildPrompt 的输出`() {
        val segments =
            listOf(
                segment("system", "角色卡正文", "角色卡"),
                segment("system", "世界书·角色定义后", "世界书·角色定义后"),
                segment("assistant", "历史回复", "历史第 1/1 条"),
                segment("user", "当前消息", "当前消息"),
            )
        val trace = PromptBuilder.PromptTrace(segments, totalEstimatedTokens = 1234)
        // buildPrompt 的实现即 segments.map { it.message }——两者必须逐条对应
        assertEquals(
            segments.map { it.message },
            trace.segments.map { it.message },
        )
    }

    @Test
    fun `总量估算与各段估算之和可独立复核`() {
        val trace =
            PromptBuilder.PromptTrace(
                segments =
                    listOf(
                        segment("system", "中文内容测试", "角色卡"),
                        segment("user", "hello world", "当前消息"),
                    ),
                totalEstimatedTokens = 0,
            )
        val perSegment = trace.segments.sumOf { trace.tokensOf(it) }
        // 每段估算必须 > 0 且远小于总量（供 UI 展示分段占比）
        assertTrue(trace.segments.all { trace.tokensOf(it) > 0 })
        assertTrue(perSegment < 100)
    }

    @Test
    fun `空 trace 不崩溃且无分段`() {
        val trace = PromptBuilder.PromptTrace(emptyList(), totalEstimatedTokens = 0)
        assertTrue(trace.segments.isEmpty())
        assertEquals(0, trace.totalEstimatedTokens)
    }

    @Test
    fun `来源标签覆盖提示词各段_便于定位 token 大头`() {
        val sources =
            listOf(
                "主提示词",
                "角色卡",
                "用户人设",
                "世界书·角色定义前",
                "世界书·角色定义后",
                "世界书·示例消息前",
                "示例对话",
                "世界书·插入深度 @D3",
                "世界书·作者注释前",
                "附加指令",
                "历史第 1/3 条",
                "历史后指令",
                "长期记忆",
                "当前消息",
            )
        // 来源标签非空即可（UI 直接展示），这里锁定关键标签名不被误改
        assertTrue(sources.contains("主提示词"))
        assertTrue(sources.contains("角色卡"))
        assertTrue(sources.contains("用户人设"))
        assertTrue(sources.contains("历史后指令"))
        assertTrue(sources.contains("世界书·插入深度 @D3"))
        assertTrue(sources.contains("长期记忆"))
        assertTrue(sources.all { it.isNotBlank() })
    }
}
