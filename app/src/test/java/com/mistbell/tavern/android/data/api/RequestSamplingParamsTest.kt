package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestSamplingParamsTest {
    private fun JsonObject.value(path: String): String =
        path.split('.').fold<String, kotlinx.serialization.json.JsonElement>(this) { current, key ->
            (current as JsonObject).getValue(key)
        }.toString()

    @Test
    fun `openai 读取顶层采样键`() {
        val request =
            buildJsonObject {
                put("temperature", 0.7)
                put("max_tokens", 2048)
                put("top_p", 0.9)
                put("top_k", 40)
                put("frequency_penalty", 0.1)
            }
        val params = RequestSamplingParams.read(request, ApiType.OPENAI)

        assertEquals(0.7, params.temperature!!, 1e-9)
        assertEquals(2048, params.maxTokens)
        assertEquals(0.9, params.topP!!, 1e-9)
        assertEquals(40, params.topK)
        assertEquals(0.1, params.frequencyPenalty!!, 1e-9)
    }

    @Test
    fun `gemini 优先 generationConfig 并回退顶层键`() {
        val request =
            buildJsonObject {
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("temperature", 0.3)
                        put("maxOutputTokens", 4096)
                    },
                )
                // 旧文档的顶层键（snake_case 约定）作为兼容兜底
                put("top_p", 0.5)
                put("top_k", 12)
            }
        val params = RequestSamplingParams.read(request, ApiType.GEMINI)

        assertEquals(0.3, params.temperature!!, 1e-9)
        assertEquals(4096, params.maxTokens)
        assertEquals(0.5, params.topP!!, 1e-9)
        assertEquals(12, params.topK)
        assertEquals(null, params.frequencyPenalty)
    }

    @Test
    fun `openai 写回含显式 null 且保留未知字段`() {
        val request =
            buildJsonObject {
                put("temperature", 1.2)
                put("vendor_extension", "keep")
            }
        val written =
            RequestSamplingParams.write(
                request,
                ApiType.OPENAI,
                RequestSamplingParams(temperature = 0.5, maxTokens = 1024),
            )

        assertEquals("0.5", written.value("temperature"))
        assertEquals("1024", written.value("max_tokens"))
        assertEquals("null", written.value("top_p"))
        assertEquals("null", written.value("top_k"))
        assertEquals("null", written.value("frequency_penalty"))
        assertEquals("\"keep\"", written.value("vendor_extension"))
    }

    @Test
    fun `gemini 写回 generationConfig 且不写 frequency_penalty`() {
        val request =
            buildJsonObject {
                put(
                    "generationConfig",
                    buildJsonObject { put("thinkingConfig", JsonNull) },
                )
            }
        val written =
            RequestSamplingParams.write(
                request,
                ApiType.GEMINI,
                RequestSamplingParams(temperature = 0.6, maxTokens = 8192, topP = 0.8, topK = 20, frequencyPenalty = 0.4),
            )

        assertEquals("0.6", written.value("generationConfig.temperature"))
        assertEquals("8192", written.value("generationConfig.maxOutputTokens"))
        assertEquals("0.8", written.value("generationConfig.topP"))
        assertEquals("20", written.value("generationConfig.topK"))
        assertEquals("null", written.value("generationConfig.thinkingConfig"))
        assertFalse(written.containsKey("frequency_penalty"))
        assertFalse((written.getValue("generationConfig") as JsonObject).containsKey("frequency_penalty"))
    }

    @Test
    fun `anthropic 写回不触碰 frequency_penalty`() {
        val written =
            RequestSamplingParams.write(
                buildJsonObject { },
                ApiType.ANTHROPIC,
                RequestSamplingParams(temperature = 0.4, maxTokens = 2048, frequencyPenalty = 0.9),
            )

        assertEquals("0.4", written.value("temperature"))
        assertEquals("2048", written.value("max_tokens"))
        assertFalse(written.containsKey("frequency_penalty"))
    }

    @Test
    fun `写回后读取同源（round trip）`() {
        val params =
            RequestSamplingParams(temperature = 1.1, maxTokens = 512, topP = 0.95, topK = 40, frequencyPenalty = 0.0)
        val written = RequestSamplingParams.write(buildJsonObject { }, ApiType.CUSTOM, params)
        assertEquals(params, RequestSamplingParams.read(written, ApiType.CUSTOM))
    }
}
