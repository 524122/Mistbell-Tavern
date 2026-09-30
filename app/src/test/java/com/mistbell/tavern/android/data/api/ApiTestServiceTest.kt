package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ApiTestService 纯函数单测：baseUrl 规范化 / 模型列表端点拼装 / 响应解析。
 * 网络请求本体走 OkHttp 不在 JVM 覆盖范围。
 */
class ApiTestServiceTest {
    // ---- normalizeApiBaseUrl ----

    @Test
    fun `normalize 去掉 chat completions 路径与末尾斜杠`() {
        assertEquals("https://api.openai.com/v1", normalizeApiBaseUrl("https://api.openai.com/v1/chat/completions"))
        assertEquals("https://api.openai.com/v1", normalizeApiBaseUrl("https://api.openai.com/v1/"))
        assertEquals("https://api.openai.com/v1", normalizeApiBaseUrl("https://api.openai.com/v1"))
        assertEquals("https://proxy.example.com", normalizeApiBaseUrl("https://proxy.example.com/"))
    }

    // ---- resolveModelsUrl ----

    @Test
    fun `openai 与 custom 走 base models 端点`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            resolveModelsUrl("https://api.openai.com/v1", ApiType.OPENAI),
        )
        assertEquals(
            "https://gateway.example.com/models",
            resolveModelsUrl("https://gateway.example.com/", ApiType.CUSTOM),
        )
    }

    @Test
    fun `anthropic 走 v1 models 且规范化尾部`() {
        assertEquals(
            "https://api.anthropic.com/v1/models",
            resolveModelsUrl("https://api.anthropic.com", ApiType.ANTHROPIC),
        )
    }

    @Test
    fun `gemini 走 v1beta models`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            resolveModelsUrl("https://generativelanguage.googleapis.com", ApiType.GEMINI),
        )
    }

    // ---- parseModelsJson ----

    @Test
    fun `openai 格式解析 data 数组`() {
        val body = """{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}"""
        assertEquals(
            listOf("gpt-4o", "gpt-4o-mini"),
            ApiTestService.parseModelsJson(body, ApiType.OPENAI),
        )
    }

    @Test
    fun `gemini 格式解析 models 数组并去掉前缀`() {
        val body = """{"models":[{"name":"models/gemini-2.0-flash"},{"name":"models/gemini-1.5-pro"}]}"""
        assertEquals(
            listOf("gemini-2.0-flash", "gemini-1.5-pro"),
            ApiTestService.parseModelsJson(body, ApiType.GEMINI),
        )
    }

    @Test
    fun `兼容网关直接返回数组也可解析`() {
        val body = """[{"id":"model-a"},{"id":"model-b"}]"""
        assertEquals(
            listOf("model-a", "model-b"),
            ApiTestService.parseModelsJson(body, ApiType.CUSTOM),
        )
    }

    @Test
    fun `坏 JSON 与空数组容错为空列表`() {
        assertTrue(ApiTestService.parseModelsJson("{bad", ApiType.OPENAI).isEmpty())
        assertTrue(ApiTestService.parseModelsJson("""{"data":[]}""", ApiType.OPENAI).isEmpty())
    }

    // ---- defaultBaseUrlForType ----

    @Test
    fun `类型预设地址`() {
        assertEquals("https://api.openai.com/v1", defaultBaseUrlForType(ApiType.OPENAI))
        assertEquals("https://api.anthropic.com", defaultBaseUrlForType(ApiType.ANTHROPIC))
        assertEquals("https://generativelanguage.googleapis.com", defaultBaseUrlForType(ApiType.GEMINI))
        assertEquals("", defaultBaseUrlForType(ApiType.CUSTOM))
    }
}
