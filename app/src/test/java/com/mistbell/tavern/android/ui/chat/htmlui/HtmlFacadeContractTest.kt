package com.mistbell.tavern.android.ui.chat.htmlui

import com.mistbell.tavern.android.extension.TavernEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HTML 门面契约测试：酒馆事件名映射（五类聊天事件 + 会话过滤 + 载荷形态）、
 * __tavernEmit 语句构造、generate 响应 JSON、substituteParams 输入上限。
 */
class HtmlFacadeContractTest {
    // ---- toHtmlChatEvent：事件名映射与载荷 ----

    @Test
    fun `message_sent 映射事件名与载荷含 sessionId 与 messageId`() {
        val event =
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.MessageSent(ownerId = "o", characterId = "c", sessionId = "s1", messageId = "m1"),
                activeSessionId = "s1",
                version = 7,
            )!!
        assertEquals(HtmlFacadeContract.EVENT_MESSAGE_SENT, event.name)
        assertEquals("""{"sessionId":"s1","messageId":"m1"}""", event.payloadJson)
        assertEquals(7, event.version)
    }

    @Test
    fun `message_received 映射事件名与载荷`() {
        val event =
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.MessageReceived(ownerId = "o", characterId = "c", sessionId = "s1", messageId = "m2"),
                activeSessionId = "s1",
                version = 1,
            )!!
        assertEquals(HtmlFacadeContract.EVENT_MESSAGE_RECEIVED, event.name)
        assertEquals("""{"sessionId":"s1","messageId":"m2"}""", event.payloadJson)
    }

    @Test
    fun `generation_started 与 generation_ended 映射且载荷不含 messageId`() {
        val started =
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.GenerationStarted(sessionId = "s1"),
                activeSessionId = "s1",
                version = 2,
            )!!
        assertEquals(HtmlFacadeContract.EVENT_GENERATION_STARTED, started.name)
        assertEquals("""{"sessionId":"s1"}""", started.payloadJson)
        val ended =
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.GenerationEnded(sessionId = "s1"),
                activeSessionId = "s1",
                version = 3,
            )!!
        assertEquals(HtmlFacadeContract.EVENT_GENERATION_ENDED, ended.name)
        assertEquals("""{"sessionId":"s1"}""", ended.payloadJson)
    }

    @Test
    fun `chat_changed 映射且载荷只含 sessionId`() {
        val event =
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.ChatChanged(ownerId = "o", characterId = "c", sessionId = "s2"),
                activeSessionId = "s2",
                version = 4,
            )!!
        assertEquals(HtmlFacadeContract.EVENT_CHAT_CHANGED, event.name)
        assertEquals("""{"sessionId":"s2"}""", event.payloadJson)
    }

    @Test
    fun `非当前会话事件过滤为 null`() {
        assertNull(
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.MessageSent(ownerId = "o", characterId = "c", sessionId = "other", messageId = "m"),
                activeSessionId = "s1",
                version = 1,
            ),
        )
        assertNull(
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.GenerationStarted(sessionId = "other"),
                activeSessionId = "s1",
                version = 1,
            ),
        )
    }

    @Test
    fun `variables_updated 不映射为聊天事件（走 MVU 专属推送）`() {
        assertNull(
            HtmlFacadeContract.toHtmlChatEvent(
                TavernEvent.VariablesUpdated(sessionId = "s1", version = 9),
                activeSessionId = "s1",
                version = 1,
            ),
        )
    }

    @Test
    fun `事件名白名单为酒馆助手下划线风格`() {
        assertEquals("message_sent", HtmlFacadeContract.EVENT_MESSAGE_SENT)
        assertEquals("message_received", HtmlFacadeContract.EVENT_MESSAGE_RECEIVED)
        assertEquals("generation_started", HtmlFacadeContract.EVENT_GENERATION_STARTED)
        assertEquals("generation_ended", HtmlFacadeContract.EVENT_GENERATION_ENDED)
        assertEquals("chat_changed", HtmlFacadeContract.EVENT_CHAT_CHANGED)
    }

    // ---- emitJs：__tavernEmit 语句 ----

    @Test
    fun `emitJs 语句形如 __tavernEmit 调用且载荷为字符串字面量`() {
        val event =
            HtmlFacadeContract.HtmlChatEvent(version = 1, name = "message_received", payloadJson = """{"a":1}""")
        val expected =
            "window.__tavernEmit && window.__tavernEmit(" +
                "'message_received', \"{\\\"a\\\":1}\")"
        assertEquals(expected, HtmlFacadeContract.emitJs(event))
    }

    // ---- okStarted ----

    @Test
    fun `okStarted 精确 JSON 串`() {
        assertEquals("""{"started":true}""", HtmlFacadeContract.okStarted())
    }

    // ---- substituteParamsLimited：超限原样返回 ----

    @Test
    fun `substituteParams 输入在上限内才执行宏替换`() {
        val captured = mutableListOf<String>()
        val out =
            HtmlFacadeContract.substituteParamsLimited("{{user}}") { input ->
                captured += input
                "User"
            }
        assertEquals("User", out)
        assertEquals(listOf("{{user}}"), captured)
    }

    @Test
    fun `substituteParams 输入超上限原样返回且不触发替换`() {
        var renderCalled = false
        val longInput = "x".repeat(HtmlFacadeContract.MAX_SUBSTITUTE_INPUT_LENGTH + 1)
        val out =
            HtmlFacadeContract.substituteParamsLimited(longInput) {
                renderCalled = true
                "不应被调用"
            }
        assertEquals(longInput, out)
        assertFalse(renderCalled)
    }

    @Test
    fun `substituteParams 上限边界 20000 字符仍替换`() {
        val boundary = "x".repeat(HtmlFacadeContract.MAX_SUBSTITUTE_INPUT_LENGTH)
        val out = HtmlFacadeContract.substituteParamsLimited(boundary) { "rendered" }
        assertEquals("rendered", out)
    }

    @Test
    fun `substituteParams 上限常量为 20000`() {
        assertEquals(20_000, HtmlFacadeContract.MAX_SUBSTITUTE_INPUT_LENGTH)
    }
}
