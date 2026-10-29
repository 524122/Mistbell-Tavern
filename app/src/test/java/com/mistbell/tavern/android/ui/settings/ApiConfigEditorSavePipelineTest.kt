package com.mistbell.tavern.android.ui.settings

import com.mistbell.tavern.android.data.api.ApiConfigFields
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult
import com.mistbell.tavern.android.data.api.ApiRequestTemplate
import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.defaultBaseUrlForType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现「无法保存其他类型 API 接口」：按对话框新增保存的真实管线逐类型走一遍
 * fromFields → materialize → formatForEditor → parse → finalDocument → format(redacted) → parse。
 */
class ApiConfigEditorSavePipelineTest {
    private fun savePipeline(type: String) {
        val baseUrl = defaultBaseUrlForType(type).ifBlank { "https://gateway.example.com/v1" }
        // 对话框打开时的初始草稿（apiConfig == null 路径）
        val base =
            ApiConfigJson.fromFields(
                fields =
                    ApiConfigFields(
                        id = "new-id",
                        name = "测试 $type",
                        type = type,
                        baseUrl = baseUrl,
                        model = "test-model",
                        hasApiKey = true,
                        streamingEnabled = true,
                        contextTokenLimit = ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
                    ),
            )
        val materialized = base.copy(request = ApiRequestTemplate.materialize(base.request, base.type))
        val editorText = ApiConfigEditorLogic.formatForEditor(materialized, hasApiKey = true)

        // 保存按钮：先 parse 当前 JSON 草稿
        val parsed = ApiConfigJson.parse(editorText)
        assertTrue(
            "$type 编辑器草稿应通过 parse: ${(parsed as? ApiConfigJsonResult.Invalid)?.message}",
            parsed is ApiConfigJsonResult.Valid,
        )
        val document = (parsed as ApiConfigJsonResult.Valid).document

        // finalDocument 合并表单字段（对话框 onConfirm 路径）
        val final =
            ApiConfigEditorLogic.finalDocument(
                parsed = document,
                fields =
                    ApiConfigEditorFields(
                        id = document.id,
                        name = "测试 $type",
                        type = type,
                        apiUrl = baseUrl,
                        model = "test-model",
                        streamingEnabled = true,
                        contextTokenLimit = ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
                    ),
            )

        // onConfirm 交给 ViewModel 前的最后一次序列化 + addApiConfig 里的再 parse
        val saved = ApiConfigJson.format(ApiConfigJson.redacted(final, hasApiKey = true))
        val reparsed = ApiConfigJson.parse(saved)
        assertTrue(
            "$type 保存产物应能再次 parse: ${(reparsed as? ApiConfigJsonResult.Invalid)?.message}",
            reparsed is ApiConfigJsonResult.Valid,
        )
    }

    @Test
    fun `openai 新增保存管线`() = savePipeline(ApiType.OPENAI)

    @Test
    fun `anthropic 新增保存管线`() = savePipeline(ApiType.ANTHROPIC)

    @Test
    fun `gemini 新增保存管线`() = savePipeline(ApiType.GEMINI)

    @Test
    fun `custom 新增保存管线`() = savePipeline(ApiType.CUSTOM)
}
