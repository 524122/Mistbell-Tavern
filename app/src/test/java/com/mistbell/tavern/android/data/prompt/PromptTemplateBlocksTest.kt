package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.util.MacroContext
import com.mistbell.tavern.android.util.MacroEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词模板四项扩展（主提示词 / 用户人设 / 历史后指令 / 群聊规范）的纯函数单测。
 *
 * 共同契约：**空值不产生段**——四项全空时 classic 提示词必须与扩展前逐字节一致。
 * 群聊规范的占位符顺序（宏渲染 → {user} 替换）是这里最容易踩的坑，单独覆盖。
 */
class PromptTemplateBlocksTest {
    private val mctx =
        MacroContext(
            char = "蒋珞",
            user = "阿伟",
            description = "学生",
            personality = "毒舌",
            scenario = "教室",
            persona = "我是高三学生，坐在最后一排",
        )

    // ---- 主提示词 ----

    @Test
    fun `主提示词未设置时不产生段`() {
        assertNull(PromptBuilder.mainPromptSegment(null, mctx))
        assertNull(PromptBuilder.mainPromptSegment("", mctx))
        assertNull(PromptBuilder.mainPromptSegment("   \n  ", mctx))
    }

    @Test
    fun `主提示词过宏渲染且标签正确`() {
        val segment = PromptBuilder.mainPromptSegment("你是{{char}}，对面是{{user}}。", mctx)
        assertNotNull(segment)
        assertEquals("主提示词", segment!!.source)
        assertEquals("system", segment.message.role)
        assertEquals("你是蒋珞，对面是阿伟。", segment.message.content)
    }

    // ---- 用户人设 ----

    @Test
    fun `用户人设未设置时不产生段`() {
        assertNull(PromptBuilder.personaSegment(null, mctx))
        assertNull(PromptBuilder.personaSegment("  ", mctx))
    }

    @Test
    fun `用户人设过宏渲染且标签正确`() {
        val segment = PromptBuilder.personaSegment("{{persona}}（对手是{{char}}）", mctx)
        assertNotNull(segment)
        assertEquals("用户人设", segment!!.source)
        assertEquals("我是高三学生，坐在最后一排（对手是蒋珞）", segment.message.content)
    }

    // ---- 历史后指令（角色卡 post_history_instructions） ----

    private fun dataJson(phi: String) = """{"post_history_instructions":${'"'}$phi${'"'}}"""

    @Test
    fun `历史后指令_字段缺失或空白时不产生段`() {
        assertNull(PromptBuilder.postHistorySegment("", "蒋珞", mctx))
        assertNull(PromptBuilder.postHistorySegment(dataJson(""), "蒋珞", mctx))
        assertNull(PromptBuilder.postHistorySegment("""{"system_prompt":"x"}""", "蒋珞", mctx))
    }

    @Test
    fun `历史后指令_有值时渲染宏且标签正确`() {
        val segment = PromptBuilder.postHistorySegment(dataJson("记住：{{user}} 是 {{char}} 的哥哥。"), "蒋珞", mctx)
        assertNotNull(segment)
        assertEquals("历史后指令", segment!!.source)
        assertEquals("system", segment.message.role)
        assertEquals("记住：阿伟 是 蒋珞 的哥哥。", segment.message.content)
    }

    @Test
    fun `历史后指令_坏 JSON 静默返回 null 不抛异常`() {
        assertNull(PromptBuilder.postHistorySegment("{ 不是合法 JSON", "蒋珞", mctx))
    }

    @Test
    fun `历史后指令_宏上下文缺角色名时用实体名兜底`() {
        val ctxWithoutChar = mctx.copy(char = "")
        val segment = PromptBuilder.postHistorySegment(dataJson("你是{{char}}"), "蒋珞", ctxWithoutChar)
        assertEquals("你是蒋珞", segment!!.message.content)
    }

    // ---- 群聊规范模板（可配置 + 占位符顺序） ----

    @Test
    fun `群聊规范_未自定义回落内置默认`() {
        assertEquals(ChatSettingsResolver.DEFAULT_GROUP_CHAT_RULES, ChatSettingsResolver.groupChatRules(null))
        assertEquals(ChatSettingsResolver.DEFAULT_GROUP_CHAT_RULES, ChatSettingsResolver.groupChatRules("   "))
    }

    @Test
    fun `群聊规范_自定义非空时优先`() {
        assertEquals("自定义规范", ChatSettingsResolver.groupChatRules("自定义规范"))
    }

    @Test
    fun `群聊规范_单花括号占位符被替换为用户名`() {
        val out = ChatSettingsResolver.applyUserNamePlaceholder("不要替 {user} 说话", "阿伟")
        assertEquals("不要替 阿伟 说话", out)
    }

    @Test
    fun `群聊规范_先宏渲染再替换占位符_双花括号写法不被啃坏`() {
        // 关键回归：{user} 是 {{user}} 的子串。若先做占位符替换，{{user}} 会变成 {阿伟}
        val template = "不许替 {{user}} 说话，也不许替 {user} 旁白"
        val rendered = MacroEngine.render(template, mctx)
        val out = ChatSettingsResolver.applyUserNamePlaceholder(rendered, mctx.user)
        assertEquals("不许替 阿伟 说话，也不许替 阿伟 旁白", out)
    }

    @Test
    fun `群聊规范_默认模板经宏渲染后逐字符不变`() {
        // 默认模板不含 {{}} 宏，渲染必须是恒等变换（否则 classic/群聊既有输出会漂移）
        val rendered = MacroEngine.render(ChatSettingsResolver.DEFAULT_GROUP_CHAT_RULES, mctx)
        assertEquals(ChatSettingsResolver.DEFAULT_GROUP_CHAT_RULES, rendered)
        // 且默认模板里的 {user} 仍能被替换
        assertTrue(
            ChatSettingsResolver
                .applyUserNamePlaceholder(rendered, "阿伟")
                .contains("不要替用户（阿伟）"),
        )
    }

    // ---- 键解析 ----

    @Test
    fun `用户名_空白与缺省回落 User`() {
        assertEquals("User", ChatSettingsResolver.userName(null))
        assertEquals("User", ChatSettingsResolver.userName(""))
        assertEquals("User", ChatSettingsResolver.userName("   "))
        assertEquals("阿伟", ChatSettingsResolver.userName("  阿伟 "))
    }

    @Test
    fun `人设与主提示词_未设置为空串`() {
        assertEquals("", ChatSettingsResolver.userPersona(null))
        assertEquals("", ChatSettingsResolver.mainPrompt(null))
        assertEquals("内容", ChatSettingsResolver.userPersona("内容"))
    }
}
