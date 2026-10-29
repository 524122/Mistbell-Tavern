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
}
