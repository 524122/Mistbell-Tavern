package com.mistbell.tavern.android.extension

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.extension.builtin.CustomPromptExtension
import com.mistbell.tavern.android.util.MacroContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CustomPromptExtension 纯函数核心（applyCustomPrompts）的 JVM 单测。
 *
 * 关键契约：无启用条目时 segments 零改动；各 position 落点与酒馆越狱/前后缀语义一致。
 */
class CustomPromptExtensionTest {
    private val mctx = MacroContext(char = "蒋珞", user = "阿伟")

    private fun segment(
        role: String,
        content: String,
    ) = PromptBuilder.Segment(ChatMessage(role = role, content = content), "测试")

    private fun baseSegments() =
        mutableListOf(
            segment("system", "角色卡"),
            segment("user", "历史问题"),
            segment("assistant", "历史回答"),
            segment("user", "当前消息"),
        )

    private fun prompt(
        name: String,
        content: String,
        position: String,
        enabled: Boolean = true,
        priority: Int = 0,
    ) = CustomPromptEntity(
        id = name,
        name = name,
        content = content,
        type = "CUSTOM",
        position = position,
        isEnabled = enabled,
        priority = priority,
    )

    @Test
    fun `空列表与全部禁用时 segments 零改动`() {
        val empty = baseSegments()
        CustomPromptExtension.applyCustomPrompts(empty, emptyList(), mctx)
        assertEquals(baseSegments().map { it.message }, empty.map { it.message })

        val disabled = baseSegments()
        CustomPromptExtension.applyCustomPrompts(
            disabled,
            listOf(prompt("p", "内容", "SYSTEM_END", enabled = false)),
            mctx,
        )
        assertEquals(baseSegments().map { it.message }, disabled.map { it.message })
    }

    @Test
    fun `SYSTEM_START 按顺序插到最前`() {
        val segments = baseSegments()
        CustomPromptExtension.applyCustomPrompts(
            segments,
            listOf(
                prompt("a", "第一", "SYSTEM_START"),
                prompt("b", "第二", "SYSTEM_START"),
            ),
            mctx,
        )
        assertEquals("第一", segments[0].message.content)
        assertEquals("第二", segments[1].message.content)
        assertEquals("角色卡", segments[2].message.content)
        assertEquals("自定义提示词·a", segments[0].source)
    }

    @Test
    fun `SYSTEM_END 插到最终用户消息之前`() {
        val segments = baseSegments()
        CustomPromptExtension.applyCustomPrompts(
            segments,
            listOf(prompt("jb", "越狱指令", "SYSTEM_END")),
            mctx,
        )
        // 原 4 段 → 5 段；越狱段在「当前消息」之前
        assertEquals(5, segments.size)
        assertEquals("越狱指令", segments[3].message.content)
        assertEquals("system", segments[3].message.role)
        assertEquals("当前消息", segments[4].message.content)
    }

    @Test
    fun `SYSTEM_END 无用户消息时追加到末尾`() {
        val segments = mutableListOf(segment("system", "角色卡"))
        CustomPromptExtension.applyCustomPrompts(
            segments,
            listOf(prompt("jb", "越狱指令", "SYSTEM_END")),
            mctx,
        )
        assertEquals("越狱指令", segments.last().message.content)
    }

    @Test
    fun `USER_PREFIX 与 USER_SUFFIX 包裹最终用户消息`() {
        val segments = baseSegments()
        CustomPromptExtension.applyCustomPrompts(
            segments,
            listOf(
                prompt("pre", "前缀", "USER_PREFIX"),
                prompt("suf", "后缀", "USER_SUFFIX"),
            ),
            mctx,
        )
        assertEquals("前缀\n当前消息\n后缀", segments.last().message.content)
        assertEquals("user", segments.last().message.role)
    }

    @Test
    fun `ASSISTANT_PREFIX 末尾追加 assistant 段（prefill）`() {
        val segments = baseSegments()
        CustomPromptExtension.applyCustomPrompts(
            segments,
            listOf(prompt("pre", "好的，", "ASSISTANT_PREFIX")),
            mctx,
        )
        assertEquals("assistant", segments.last().message.role)
        assertEquals("好的，", segments.last().message.content)
    }

    @Test
    fun `内容过宏渲染`() {
        val segments = baseSegments()
        CustomPromptExtension.applyCustomPrompts(
            segments,
            listOf(prompt("m", "{{user}}对面是{{char}}", "SYSTEM_END")),
            mctx,
        )
        assertEquals("阿伟对面是蒋珞", segments[3].message.content)
    }

    @Test
    fun `空白内容不产生段`() {
        val segments = baseSegments()
        CustomPromptExtension.applyCustomPrompts(
            segments,
            listOf(prompt("blank", "   ", "SYSTEM_END")),
            mctx,
        )
        assertEquals(4, segments.size)
    }
}
