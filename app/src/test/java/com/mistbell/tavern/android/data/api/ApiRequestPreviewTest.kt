package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRequestPreviewTest {
    private fun document(
        type: String,
        request: JsonObject = ApiRequestTemplate.templateFor(type),
    ) = ApiConfigDocument(
        id = "preview-test",
        name = "Preview test",
        type = type,
        baseUrl = "https://example.test",
        model = "real-model",
        streamingEnabled = false,
        request = request,
    )

    private fun ApiRequestPreview.group(id: String): ApiRequestPreviewGroup = parameterGroups.single { it.id == id }

    private fun ApiRequestPreviewGroup.field(path: String): ApiRequestPreviewField = fields.single { it.path == path }

    private fun ApiRequestPreview.generated(path: String): ApiRequestPreviewField = generatedFields.single { it.path == path }

    @Test
    fun `openai 模板默认值显示 DEFAULT 且 null 显示未设置`() {
        val preview = ApiRequestPreview.from(document(ApiType.OPENAI))

        assertEquals(
            ApiRequestPreviewField("temperature", "0.8", ApiRequestFieldState.DEFAULT),
            preview.group("sampling").field("temperature"),
        )
        assertEquals(
            ApiRequestPreviewField("max_tokens", "1024", ApiRequestFieldState.DEFAULT),
            preview.group("sampling").field("max_tokens"),
        )
        assertEquals(
            ApiRequestPreviewField("top_p", "0.9", ApiRequestFieldState.DEFAULT),
            preview.group("sampling").field("top_p"),
        )
        assertEquals(
            ApiRequestPreviewField("top_k", ApiRequestPreview.UNSET_VALUE, ApiRequestFieldState.DEFAULT),
            preview.group("sampling").field("top_k"),
        )
        assertEquals(
            ApiRequestPreviewField("frequency_penalty", "0.0", ApiRequestFieldState.DEFAULT),
            preview.group("sampling").field("frequency_penalty"),
        )
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("thinking").field("thinking").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("thinking").field("reasoning_effort").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("response_format").field("response_format").value)
        assertEquals("采样参数 · sampling", preview.group("sampling").title)
        assertEquals(0, preview.userFieldCount)
        assertEquals(ApiRequestPreviewField("model", "real-model", ApiRequestFieldState.PROTECTED), preview.generated("model"))
        assertEquals(ApiRequestPreviewField("stream", "false", ApiRequestFieldState.PROTECTED), preview.generated("stream"))
        assertTrue(preview.ignoredFields.isEmpty())
    }

    @Test
    fun `已知字段偏离模板与非空未知字段显示 USER 且不重复`() {
        val request =
            ApiRequestTemplate
                .materialize(
                    buildJsonObject {
                        put("temperature", 0.2)
                        put("reasoning_effort", "high")
                        put("response_format", buildJsonObject { put("type", "json_object") })
                        put("vendor_extension", "keep")
                        put("model", "user-model")
                        put("stream", true)
                    },
                    ApiType.OPENAI,
                )
        val preview = ApiRequestPreview.from(document(ApiType.OPENAI, request))

        assertEquals(ApiRequestFieldState.USER, preview.group("sampling").field("temperature").state)
        assertEquals("0.2", preview.group("sampling").field("temperature").value)
        assertEquals(ApiRequestFieldState.USER, preview.group("thinking").field("reasoning_effort").state)
        assertEquals(ApiRequestFieldState.USER, preview.group("response_format").field("response_format").state)
        assertEquals(ApiRequestFieldState.USER, preview.group("custom").field("vendor_extension").state)
        assertEquals(4, preview.userFieldCount)

        val allPaths = preview.parameterGroups.flatMap { group -> group.fields.map { it.path } }
        assertEquals(allPaths.size, allPaths.toSet().size)
        assertFalse(allPaths.contains("response_format.type"))
        assertFalse(allPaths.contains("thinking.type"))

        assertEquals(setOf("model", "stream"), preview.ignoredFields.map { it.path }.toSet())
        assertTrue(preview.ignoredFields.all { it.state == ApiRequestFieldState.PROTECTED })
    }

    @Test
    fun `显式 null 与缺失字段不计为 USER`() {
        val request =
            buildJsonObject {
                put("temperature", JsonNull)
                put("top_p", 0.9)
                put("vendor_null", JsonNull)
            }
        val preview = ApiRequestPreview.from(document(ApiType.OPENAI, request))

        assertEquals(
            ApiRequestPreviewField("temperature", ApiRequestPreview.UNSET_VALUE, ApiRequestFieldState.DEFAULT),
            preview.group("sampling").field("temperature"),
        )
        assertEquals(ApiRequestFieldState.DEFAULT, preview.group("sampling").field("top_p").state)
        assertEquals(0, preview.userFieldCount)
        assertTrue(preview.parameterGroups.none { it.id == "custom" })
    }

    @Test
    fun `anthropic 模板默认值与 null 状态`() {
        val preview = ApiRequestPreview.from(document(ApiType.ANTHROPIC))

        assertEquals(ApiRequestFieldState.DEFAULT, preview.group("sampling").field("temperature").state)
        assertEquals("1024", preview.group("sampling").field("max_tokens").value)
        assertEquals("0.9", preview.group("sampling").field("top_p").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("sampling").field("top_k").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("thinking").field("thinking").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("metadata").field("metadata").value)
        assertEquals(0, preview.userFieldCount)
        assertEquals(ApiRequestFieldState.PROTECTED, preview.generated("system").state)
    }

    @Test
    fun `gemini 模板默认值与 null 状态`() {
        val preview = ApiRequestPreview.from(document(ApiType.GEMINI))

        assertEquals(ApiRequestFieldState.DEFAULT, preview.group("generationConfig").field("generationConfig.maxOutputTokens").state)
        assertEquals("1024", preview.group("generationConfig").field("generationConfig.maxOutputTokens").value)
        assertEquals("0.8", preview.group("generationConfig").field("generationConfig.temperature").value)
        assertEquals("0.9", preview.group("generationConfig").field("generationConfig.topP").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("generationConfig").field("generationConfig.topK").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("generationConfig").field("generationConfig.responseMimeType").value)
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("generationConfig").field("generationConfig.responseSchema").value)
        assertEquals(
            ApiRequestPreview.UNSET_VALUE,
            preview.group("thinkingConfig").field("generationConfig.thinkingConfig").value,
        )
        assertEquals(ApiRequestPreview.UNSET_VALUE, preview.group("safetySettings").field("safetySettings").value)
        assertEquals("生成配置 · generationConfig", preview.group("generationConfig").title)
        assertEquals(0, preview.userFieldCount)
    }
}
