package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRequestTemplateTest {
    private fun JsonObject.value(path: String): String =
        path.split('.').fold<String, kotlinx.serialization.json.JsonElement>(this) { current, key ->
            (current as JsonObject).getValue(key)
        }.toString()

    @Test
    fun `openai 模板默认值与默认输出预算`() {
        val template = ApiRequestTemplate.templateFor(ApiType.OPENAI)

        assertEquals("0.8", template.value("temperature"))
        assertEquals(LlmConfig.DEFAULT_MAX_TOKENS.toString(), template.value("max_tokens"))
        assertEquals("0.9", template.value("top_p"))
        assertEquals("null", template.value("top_k"))
        assertEquals("0.0", template.value("frequency_penalty"))
        assertEquals("null", template.value("thinking"))
        assertEquals("null", template.value("reasoning_effort"))
        assertEquals("null", template.value("response_format"))
        listOf("messages", "model", "stream", "apiKey", "api_key", "contextTokenLimit").forEach {
            assertFalse("$it 不能进 request", template.containsKey(it))
        }
        // custom 与 openai 同协议
        assertEquals(template, ApiRequestTemplate.templateFor(ApiType.CUSTOM))
    }

    @Test
    fun `fromFields 默认保留空 request 不后台物化`() {
        val document =
            ApiConfigJson.fromFields(
                fields =
                    ApiConfigFields(
                        id = "x",
                        name = "x",
                        type = ApiType.GEMINI,
                        baseUrl = "https://example.test/v1",
                        model = "m",
                        hasApiKey = false,
                        streamingEnabled = true,
                        contextTokenLimit = 4096,
                    ),
            )
        assertTrue(document.request.isEmpty())
    }

    @Test
    fun `明确传入模板的新配置路径保留完整模板`() {
        val template = ApiRequestTemplate.templateFor(ApiType.GEMINI)
        val document =
            ApiConfigJson.fromFields(
                fields =
                    ApiConfigFields(
                        id = "x",
                        name = "x",
                        type = ApiType.GEMINI,
                        baseUrl = "https://example.test/v1",
                        model = "m",
                        hasApiKey = false,
                        streamingEnabled = true,
                        contextTokenLimit = 4096,
                        request = template,
                    ),
            )
        assertEquals(template, document.request)
    }

    @Test
    fun `anthropic 模板默认值`() {
        val template = ApiRequestTemplate.templateFor(ApiType.ANTHROPIC)

        assertEquals("0.8", template.value("temperature"))
        assertEquals("1024", template.value("max_tokens"))
        assertEquals("0.9", template.value("top_p"))
        assertEquals("null", template.value("top_k"))
        assertEquals("null", template.value("thinking"))
        assertEquals("null", template.value("metadata"))
        assertFalse(template.containsKey("frequency_penalty"))
        assertFalse(template.containsKey("response_format"))
    }

    @Test
    fun `gemini 模板默认值`() {
        val template = ApiRequestTemplate.templateFor(ApiType.GEMINI)

        assertEquals("1024", template.value("generationConfig.maxOutputTokens"))
        assertEquals("0.8", template.value("generationConfig.temperature"))
        assertEquals("0.9", template.value("generationConfig.topP"))
        assertEquals("null", template.value("generationConfig.topK"))
        assertEquals("null", template.value("generationConfig.responseMimeType"))
        assertEquals("null", template.value("generationConfig.responseSchema"))
        assertEquals("null", template.value("generationConfig.thinkingConfig"))
        assertEquals("null", template.value("safetySettings"))
        listOf("contents", "systemInstruction", "model", "stream").forEach {
            assertFalse("$it 不能进 request", template.containsKey(it))
        }
    }

    @Test
    fun `物化补齐缺失字段并保留显式值 null 空对象与未知字段`() {
        val existing =
            buildJsonObject {
                put("temperature", 0.2)
                put("top_k", 12)
                put("thinking", JsonNull)
                put("vendor_extension", "keep")
                put("empty_object", buildJsonObject { })
            }
        val materialized = ApiRequestTemplate.materialize(existing, ApiType.OPENAI)

        assertEquals("0.2", materialized.value("temperature"))
        assertEquals("12", materialized.value("top_k"))
        assertEquals("null", materialized.value("thinking"))
        assertEquals("\"keep\"", materialized.value("vendor_extension"))
        assertEquals("{}", materialized.value("empty_object"))
        // 缺失字段补齐为模板值
        assertEquals("1024", materialized.value("max_tokens"))
        assertEquals("0.9", materialized.value("top_p"))
        assertEquals("0.0", materialized.value("frequency_penalty"))
        assertEquals("null", materialized.value("reasoning_effort"))
        assertEquals("null", materialized.value("response_format"))
    }

    @Test
    fun `嵌套模板物化保留已有 generationConfig 值`() {
        val existing =
            buildJsonObject {
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("temperature", 0.3)
                        put("topK", 5)
                        put("vendorFlag", true)
                    },
                )
            }
        val materialized = ApiRequestTemplate.materialize(existing, ApiType.GEMINI)

        assertEquals("0.3", materialized.value("generationConfig.temperature"))
        assertEquals("5", materialized.value("generationConfig.topK"))
        assertEquals("true", materialized.value("generationConfig.vendorFlag"))
        assertEquals("1024", materialized.value("generationConfig.maxOutputTokens"))
        assertEquals("null", materialized.value("generationConfig.responseMimeType"))
    }

    @Test
    fun `null 清理不误删 false 零 空对象与空数组`() {
        val request =
            buildJsonObject {
                put("null_field", JsonNull)
                put("flag", false)
                put("zero", 0)
                put("empty_object", buildJsonObject { })
                put("empty_array", buildJsonArray { })
                put(
                    "nested",
                    buildJsonObject {
                        put("null_field", JsonNull)
                        put("flag", false)
                    },
                )
                put(
                    "array",
                    buildJsonArray {
                        add(0)
                        add(false)
                        add(JsonNull)
                    },
                )
            }
        val cleaned = ApiRequestTemplate.withoutNulls(request)

        assertFalse(cleaned.containsKey("null_field"))
        assertEquals(JsonPrimitive(false), cleaned["flag"])
        assertEquals(JsonPrimitive(0), cleaned["zero"])
        assertEquals(buildJsonObject { }, cleaned["empty_object"])
        assertEquals(buildJsonArray { }, cleaned["empty_array"])
        assertEquals("false", ((cleaned["nested"] as JsonObject)["flag"] as JsonPrimitive).content)
        assertFalse((cleaned["nested"] as JsonObject).containsKey("null_field"))
        assertEquals(2, (cleaned["array"] as JsonArray).size)
    }

    @Test
    fun `模板 JSON 序列化保留 null 占位并可 round trip`() {
        val document =
            ApiConfigDocument(
                id = "x",
                name = "x",
                type = ApiType.OPENAI,
                baseUrl = "https://example.test/v1",
                model = "m",
                request = ApiRequestTemplate.templateFor(ApiType.OPENAI),
            )
        val text = ApiConfigJson.format(document)
        assertTrue(text.contains("\"top_k\": null"))
        assertTrue(text.contains("\"thinking\": null"))
        val parsed = ApiConfigJson.parse(text)
        assertTrue(parsed is ApiConfigJsonResult.Valid)
        assertEquals(document.request, (parsed as ApiConfigJsonResult.Valid).document.request)
    }
}
