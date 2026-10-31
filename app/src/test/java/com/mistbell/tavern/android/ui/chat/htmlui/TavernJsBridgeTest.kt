package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernJsBridgeTest {
    private val toasts = mutableListOf<String>()
    private val host =
        object : HtmlBridgeHost {
            override fun getVar(key: String): String = ""

            override fun setVar(
                key: String,
                value: String,
            ) = Unit

            override fun getChatMessagesJson(limit: Int): String = "[]"

            override fun sendMessage(text: String) = Unit

            override fun showToast(message: String) {
                toasts += message
            }

            override fun emitEvent(
                type: String,
                payload: String,
            ) = Unit

            override fun getMvuVariablesJson(): String = "{}"

            override fun insertMvuVariablesJson(json: String) = Unit

            override fun triggerSlash(command: String) = Unit
        }
    private val bridge = TavernJsBridge(host, onHeightReport = {})

    @Test
    fun `showToast 忽略空白并限制消息长度`() {
        bridge.showToast(" ")
        assertTrue(toasts.isEmpty())
        bridge.showToast("x".repeat(300))
        assertEquals("x".repeat(240), toasts.single())
    }

    @Test
    fun `getTavernRegexes 未覆写宿主时安全降级为空数组`() {
        assertEquals("[]", bridge.getTavernRegexes())
    }

    // ---- 消息写 API 默认降级：接口默认实现一律拒绝/空操作（无会话宿主不落库） ----

    @Test
    fun `setChatMessages 未覆写宿主时降级为错误 JSON`() {
        val response = bridge.setChatMessages("""[{"id":"m1","content":"x"}]""")
        assertTrue(response.contains("error"))
    }

    @Test
    fun `createChatMessage 未覆写宿主时降级为错误 JSON`() {
        val response = bridge.createChatMessage("""{"role":"user","content":"x"}""")
        assertTrue(response.contains("error"))
    }

    @Test
    fun `deleteChatMessages 未覆写宿主时降级为错误 JSON`() {
        val response = bridge.deleteChatMessages("""["m1"]""")
        assertTrue(response.contains("error"))
    }

    @Test
    fun `getLastMessageId 未覆写宿主时降级为空串`() {
        assertEquals("", bridge.getLastMessageId())
    }

    // ---- 生成控制与宏替换默认降级：接口默认实现一律拒绝/空操作/原样返回 ----

    @Test
    fun `triggerGenerate 未覆写宿主时降级为错误 JSON`() {
        val response = bridge.triggerGenerate()
        assertTrue(response.contains("error"))
        assertEquals("""{"error":"当前无会话"}""", response)
    }

    @Test
    fun `stopGeneration 未覆写宿主时为空操作且不抛异常`() {
        bridge.stopGeneration()
    }

    @Test
    fun `substituteParams 未覆写宿主时原样返回`() {
        val input = "{{char}}对{{user}}说 <USER> 旧式占位"
        assertEquals(input, bridge.substituteParams(input))
    }
}
