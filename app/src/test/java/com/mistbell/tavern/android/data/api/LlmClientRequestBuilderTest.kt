package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求体构造：思考模式开关按需出现（不带时零字段差异，保证对任意网关的默认行为不变） */
class LlmClientRequestBuilderTest {
    private val config =
        LlmConfig(
            baseUrl = "https://api.deepseek.com",
            apiKey = "test-key",
            model = "deepseek-flash",
        )

    private fun bodyOf(config: LlmConfig): String =
        LlmClient.buildChatRequest(config, listOf(ChatMessage(role = "user", content = "hi")), stream = false)
            .body
            ?.let { okio.Buffer().also { buf -> it.writeTo(buf) }.readUtf8() }
            .orEmpty()

    @Test
    fun `未关闭思考模式时请求体不含 thinking 字段`() {
        val body = bodyOf(config)
        assertFalse(body.contains("thinking"))
        assertTrue(body.contains("\"model\":\"deepseek-flash\""))
    }

    @Test
    fun `关闭思考模式时请求体带 thinking disabled`() {
        val body = bodyOf(config.copy(disableThinking = true))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
    }

    @Test
    fun `关闭思考模式时流式请求同样带上开关`() {
        val body =
            LlmClient.buildChatRequest(
                config.copy(disableThinking = true),
                listOf(ChatMessage(role = "user", content = "hi")),
                stream = true,
            ).body
                ?.let { okio.Buffer().also { buf -> it.writeTo(buf) }.readUtf8() }
                .orEmpty()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
    }
}
