package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * request JSON 里五个核心采样参数的统一读写映射。
 *
 * 设置页「模型参数」区块与 SettingsRepository.getLlmConfig 共用这一份协议键位约定，
 * 保证滑条、JSON 面板与实际请求体三方同源。null = 未设置（请求体不发送该字段，
 * 模板/文档中以显式 null 占位，见 [ApiRequestTemplate.withoutNulls]）。
 */
data class RequestSamplingParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
) {
    companion object {
        /**
         * 按协议读取持久化 request 里的采样字段：
         * - Gemini：generationConfig.maxOutputTokens / temperature / topP / topK
         * - 其余：max_tokens / temperature / top_p / top_k / frequency_penalty
         * 同时保留顶层键作为兼容兜底，避免旧文档在不同网关下失效。
         */
        fun read(
            request: JsonObject?,
            type: String,
        ): RequestSamplingParams {
            if (request == null) return RequestSamplingParams()
            val generationConfig = request["generationConfig"] as? JsonObject
            val isGemini = normalizeApiType(type) == ApiType.GEMINI
            return RequestSamplingParams(
                temperature =
                    (if (isGemini) generationConfig?.getDouble("temperature") else null)
                        ?: request.getDouble("temperature"),
                maxTokens =
                    (if (isGemini) generationConfig?.getInt("maxOutputTokens") else null)
                        ?: request.getInt("max_tokens"),
                topP = (if (isGemini) generationConfig?.getDouble("topP") else null) ?: request.getDouble("top_p"),
                topK = (if (isGemini) generationConfig?.getInt("topK") else null) ?: request.getInt("top_k"),
                frequencyPenalty = if (isGemini) null else request.getDouble("frequency_penalty"),
            )
        }

        /**
         * 按协议写回采样字段：null 写成显式 JsonNull（未设置不发送，JSON 面板可见）。
         * 只触碰本协议支持的键（Anthropic/Gemini 无 frequency_penalty），用户手写的其他字段原样保留。
         */
        fun write(
            request: JsonObject,
            type: String,
            params: RequestSamplingParams,
        ): JsonObject =
            when (normalizeApiType(type)) {
                ApiType.GEMINI -> {
                    val generationConfig =
                        LinkedHashMap((request["generationConfig"] as? JsonObject) ?: emptyMap())
                    generationConfig["temperature"] = params.temperature.toJson()
                    generationConfig["maxOutputTokens"] = params.maxTokens.toJson()
                    generationConfig["topP"] = params.topP.toJson()
                    generationConfig["topK"] = params.topK.toJson()
                    JsonObject(LinkedHashMap(request).apply { put("generationConfig", JsonObject(generationConfig)) })
                }
                ApiType.ANTHROPIC ->
                    JsonObject(
                        LinkedHashMap(request).apply {
                            put("temperature", params.temperature.toJson())
                            put("max_tokens", params.maxTokens.toJson())
                            put("top_p", params.topP.toJson())
                            put("top_k", params.topK.toJson())
                        },
                    )
                else ->
                    JsonObject(
                        LinkedHashMap(request).apply {
                            put("temperature", params.temperature.toJson())
                            put("max_tokens", params.maxTokens.toJson())
                            put("top_p", params.topP.toJson())
                            put("top_k", params.topK.toJson())
                            put("frequency_penalty", params.frequencyPenalty.toJson())
                        },
                    )
            }

        private fun Double?.toJson() = this?.let(::JsonPrimitive) ?: JsonNull

        private fun Int?.toJson() = this?.let(::JsonPrimitive) ?: JsonNull

        private fun JsonObject.getDouble(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

        private fun JsonObject.getInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    }
}
