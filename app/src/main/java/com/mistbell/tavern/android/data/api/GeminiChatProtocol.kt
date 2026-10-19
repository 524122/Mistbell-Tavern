package com.mistbell.tavern.android.data.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini generateContent 适配（generativelanguage.googleapis.com）。
 *
 * 与 OpenAI 格式的差异：
 * - key 拼在 URL query（?key=...），流式再加 &alt=sse
 * - 请求体 contents[]：assistant 角色映射为 "model"，system 提取为 systemInstruction
 * - 采样参数在 generationConfig（maxOutputTokens/temperature/topP/topK），无 frequency_penalty
 * - 响应 candidates[].content.parts[] 拼接；用量在 usageMetadata
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object GeminiChatProtocol : ChatProtocol {
    // contents/systemInstruction 由协议从消息装配；key 走 URL query，均不允许用户 JSON 覆盖
    override val protectedRequestFields: Set<String> =
        ApiConfigJson.DEFAULT_PROTECTED_FIELDS + setOf("contents", "systeminstruction", "key")

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            // maxOutputTokens=1024 / temperature=0.8 是有效值，不能按“等于默认值”省略
            encodeDefaults = true
        }

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null,
        @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig? = null,
    )

    @Serializable
    private data class GeminiContent(
        val role: String? = null,
        val parts: List<GeminiPart>,
    ) {
        @Serializable
        data class GeminiPart(
            val text: String = "",
            // 响应里才有的标记；请求 parts 不带 thought=false 噪音
            @EncodeDefault(EncodeDefault.Mode.NEVER) val thought: Boolean = false,
        )
    }

    @Serializable
    private data class GeminiGenerationConfig(
        @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
        val temperature: Double? = null,
        @SerialName("topP") val topP: Double? = null,
        @SerialName("topK") val topK: Int? = null,
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
        @SerialName("usageMetadata") val usageMetadata: GeminiUsage? = null,
    ) {
        @Serializable
        data class GeminiCandidate(
            val content: GeminiContent? = null,
            @SerialName("finishReason") val finishReason: String? = null,
        )

        @Serializable
        data class GeminiUsage(
            @SerialName("promptTokenCount") val promptTokens: Int = 0,
            @SerialName("candidatesTokenCount") val candidatesTokens: Int = 0,
            @SerialName("totalTokenCount") val totalTokens: Int = 0,
        )
    }

    override fun buildRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request {
        val systemText = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.ifBlank { null }
        val contents =
            messages
                .filter { it.role != "system" }
                .map {
                    // Gemini 角色只有 user/model：assistant → model
                    GeminiContent(
                        role = if (it.role == "assistant") "model" else "user",
                        parts = listOf(GeminiContent.GeminiPart(text = it.content)),
                    )
                }

        val requestBody =
            GeminiRequest(
                contents = contents,
                systemInstruction =
                    systemText?.let {
                        GeminiContent(parts = listOf(GeminiContent.GeminiPart(text = it)))
                    },
                generationConfig =
                    GeminiGenerationConfig(
                        maxOutputTokens = config.maxTokens,
                        temperature = config.temperature,
                        topP = config.topP,
                        topK = config.topK,
                    ),
            )

        val baseJson = json.encodeToJsonElement(GeminiRequest.serializer(), requestBody).jsonObject
        val bodyJson =
            mergeProtocolRequestBody(
                base = baseJson,
                config = config,
                protectedFields = protectedRequestFields,
            ).toString()
        val base = config.baseUrl.trimEnd('/')
        val modelPath =
            if (ApiUrl.hasPathSuffix(base, "v1beta")) {
                "models/${config.model}:generateContent"
            } else {
                "v1beta/models/${config.model}:generateContent"
            }
        // 先追加路径（位于 query 之前），再用结构化 builder 合并 key/alt=sse；
        // baseUrl 里已有的非凭据 query 参数与分隔符保持不变。
        val endpoint = ApiUrl.appendPath(base, modelPath)
        val query =
            buildMap {
                put("key", config.apiKey)
                if (stream) put("alt", "sse")
            }
        // alt 是协议控制的开关：旧 baseUrl 里的 alt 不允许与前缀重复/冲突
        val url = ApiUrl.withQueryParameters(endpoint, query, replacedParameters = setOf("alt"))

        return Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
    }

    override fun parseContent(body: String): ProtocolContent {
        val response = json.decodeFromString(GeminiResponse.serializer(), body)
        val text =
            response.candidates
                .mapNotNull { it.content }
                .flatMap { it.parts.filterNot(GeminiContent.GeminiPart::thought) }
                .joinToString("") { it.text }
        val thinking =
            response.candidates
                .mapNotNull { it.content }
                .flatMap { it.parts.filter(GeminiContent.GeminiPart::thought) }
                .joinToString("") { it.text }
                .ifBlank { null }
        val usage =
            response.usageMetadata?.let {
                Usage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.candidatesTokens,
                    totalTokens = it.totalTokens,
                )
            }
        return ProtocolContent(
            text = text,
            usage = usage,
            finishReason = response.candidates.firstOrNull()?.finishReason,
            thinking = thinking,
        )
    }

    override fun parseStreamData(data: String): StreamParseResult {
        val response = parseResponse(data) ?: return StreamParseResult.Ignore
        val parts =
            response.candidates
                .mapNotNull { it.content }
                .flatMap { it.parts }
        val deltaText =
            parts
                .filterNot(GeminiContent.GeminiPart::thought)
                .joinToString("") { it.text }
                .takeIf { it.isNotBlank() }
        val deltaThinking =
            parts
                .filter(GeminiContent.GeminiPart::thought)
                .joinToString("") { it.text }
                .takeIf { it.isNotBlank() }
        val usageUpdate =
            response.usageMetadata?.let {
                StreamParseResult.UsageUpdate(
                    Usage(
                        promptTokens = it.promptTokens,
                        completionTokens = it.candidatesTokens,
                        totalTokens = it.totalTokens,
                    ),
                )
            }
        return if (deltaText != null || deltaThinking != null) {
            StreamParseResult.Delta(deltaText.orEmpty(), deltaThinking)
        } else {
            usageUpdate ?: StreamParseResult.Ignore
        }
    }

    /** 解析 SSE data 负载为响应对象；[DONE] 与坏 JSON 返回 null */
    private fun parseResponse(data: String): GeminiResponse? {
        val trimmed = data.trim()
        if (trimmed == "[DONE]") return null
        return try {
            json.decodeFromString(GeminiResponse.serializer(), trimmed)
        } catch (_: Exception) {
            null
        }
    }
}
