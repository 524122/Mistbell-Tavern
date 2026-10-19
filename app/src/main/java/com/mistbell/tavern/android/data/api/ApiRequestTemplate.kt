package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 模板叶子：点分路径 + 原始 JSON 值（null 也是显式模板值）。 */
data class ApiRequestTemplateField(
    val path: String,
    val value: JsonElement,
)

/**
 * API request 的唯一模板/物化来源。
 *
 * 模板展示“完整可编辑参数文档”：缺省参数以模板默认采样值和
 * [LlmConfig.DEFAULT_MAX_TOKENS] 物化；未设置项显式写成 null。
 * 实际协议合并前统一用 [withoutNulls] 去掉 null，因此模板里的 null 只用于 UI 表达“未设置”。
 */
object ApiRequestTemplate {
    // 模板默认采样值（原 BALANCED 预设内联）：temperature 0.8 / top_p 0.9 / top_k 未设置 / frequency_penalty 0.0
    private const val TEMPLATE_TEMPERATURE = 0.8
    private const val TEMPLATE_TOP_P = 0.9
    private const val TEMPLATE_FREQUENCY_PENALTY = 0.0

    fun templateFor(type: String): JsonObject =
        when (normalizeApiType(type)) {
            ApiType.ANTHROPIC ->
                buildJsonObject {
                    putNumber("temperature", TEMPLATE_TEMPERATURE)
                    putNumber("max_tokens", LlmConfig.DEFAULT_MAX_TOKENS)
                    putNumberOrNull("top_p", TEMPLATE_TOP_P)
                    putNumberOrNull("top_k", null)
                    put("thinking", JsonNull)
                    put("metadata", JsonNull)
                }
            ApiType.GEMINI ->
                buildJsonObject {
                    put(
                        "generationConfig",
                        buildJsonObject {
                            putNumber("maxOutputTokens", LlmConfig.DEFAULT_MAX_TOKENS)
                            putNumber("temperature", TEMPLATE_TEMPERATURE)
                            putNumberOrNull("topP", TEMPLATE_TOP_P)
                            putNumberOrNull("topK", null)
                            put("responseMimeType", JsonNull)
                            put("responseSchema", JsonNull)
                            put("thinkingConfig", JsonNull)
                        },
                    )
                    put("safetySettings", JsonNull)
                }
            else ->
                buildJsonObject {
                    putNumber("temperature", TEMPLATE_TEMPERATURE)
                    putNumber("max_tokens", LlmConfig.DEFAULT_MAX_TOKENS)
                    putNumberOrNull("top_p", TEMPLATE_TOP_P)
                    putNumberOrNull("top_k", null)
                    putNumberOrNull("frequency_penalty", TEMPLATE_FREQUENCY_PENALTY)
                    put("thinking", JsonNull)
                    put("reasoning_effort", JsonNull)
                    put("response_format", JsonNull)
                }
        }

    fun templateFields(type: String): List<ApiRequestTemplateField> = flattenLeaves(templateFor(type))

    /** 递归补齐模板缺失字段，同时保留已有显式值、显式 null、空对象和所有未知字段。 */
    fun materialize(
        request: JsonObject,
        type: String,
    ): JsonObject = mergeTemplate(request, templateFor(type))

    /** request 是否仍是该协议的原始模板（用于协议切换时判断能否安全替换）。 */
    fun isUnmodifiedTemplate(
        request: JsonObject,
        type: String,
    ): Boolean = request == templateFor(type)

    /** 递归移除 null 键与数组中的 null 元素；false/0/空对象/空数组必须原样保留。 */
    fun withoutNulls(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                buildJsonObject {
                    element.forEach { (key, value) ->
                        val cleaned = withoutNulls(value)
                        if (cleaned !is JsonNull) put(key, cleaned)
                    }
                }
            is JsonArray ->
                buildJsonArray {
                    element.forEach { value ->
                        val cleaned = withoutNulls(value)
                        if (cleaned !is JsonNull) add(cleaned)
                    }
                }
            else -> element
        }

    fun withoutNulls(request: JsonObject): JsonObject = withoutNulls(request as JsonElement) as JsonObject

    /** 展开成叶子路径；空对象/数组/null 都作为单个叶子保留。 */
    fun flattenLeaves(
        element: JsonElement,
        prefix: String = "",
    ): List<ApiRequestTemplateField> =
        when (element) {
            is JsonObject ->
                if (element.isEmpty()) {
                    listOf(ApiRequestTemplateField(prefix, element))
                } else {
                    element.flatMap { (key, value) ->
                        flattenLeaves(value, if (prefix.isBlank()) key else "$prefix.$key")
                    }
                }
            else -> listOf(ApiRequestTemplateField(prefix, element))
        }

    private fun mergeTemplate(
        existing: JsonObject,
        template: JsonObject,
    ): JsonObject {
        val result = LinkedHashMap(existing)
        template.forEach { (key, templateValue) ->
            val current = result[key]
            result[key] =
                when {
                    current == null -> templateValue
                    current is JsonObject && templateValue is JsonObject -> mergeTemplate(current, templateValue)
                    else -> current
                }
        }
        return JsonObject(result)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNumber(
        key: String,
        value: Number,
    ) {
        put(key, JsonPrimitive(value))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNumberOrNull(
        key: String,
        value: Number?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }
}
