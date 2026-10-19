package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.util.Locale

/**
 * 对话协议适配：把统一的 [ChatMessage] 列表编码为目标协议的请求，
 * 并把目标协议的响应 / SSE 流解码回统一形态。
 *
 * 与协议无关的管道（重试、取消双保险、超时、用量日志）留在 LlmClient 共享；
 * 每个协议只实现四处差异：请求体/认证、响应解析、SSE 增量解析。
 */
internal interface ChatProtocol {
    /**
     * 本协议受保护的请求字段（用户 request 扩展 JSON 不得覆盖）。
     * 预览/诊断经 ApiConfigJson.protectedFieldsForType 读取同一份定义。
     */
    val protectedRequestFields: Set<String>
        get() = ApiConfigJson.DEFAULT_PROTECTED_FIELDS

    fun buildRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request

    /** 解析非流式响应体为统一正文 + 用量 + 结束原因（截断诊断用） */
    fun parseContent(body: String): ProtocolContent

    /** 解析一条 SSE data 负载：正文增量 / 用量更新 / 忽略 */
    fun parseStreamData(data: String): StreamParseResult
}

/** 非流式响应的统一形态 */
data class ProtocolContent(
    val text: String,
    val usage: Usage?,
    val finishReason: String?,
    /** 模型通过独立 reasoning/thinking 字段返回的思维链。 */
    val thinking: String? = null,
)

sealed class StreamParseResult {
    /** 一帧可以同时带正文和思维链（部分网关会这样返回）。 */
    data class Delta(val text: String, val thinking: String? = null) : StreamParseResult()

    data class UsageUpdate(val usage: Usage) : StreamParseResult()

    object Ignore : StreamParseResult()
}

/**
 * 接口类型唯一规范化规则：大小写、首尾空白差异不能改变协议分支。
 * 预览、诊断、连接测试、模型列表与真实请求必须全部经过这里，避免“预览 Gemini、运行 OpenAI”。
 */
fun normalizeApiType(type: String): String = type.trim().lowercase(Locale.ROOT)

/** 按接口类型选协议适配（custom 与 openai 同协议） */
internal fun chatProtocolFor(type: String): ChatProtocol =
    when (normalizeApiType(type)) {
        ApiType.ANTHROPIC -> AnthropicChatProtocol
        ApiType.GEMINI -> GeminiChatProtocol
        else -> OpenAiChatProtocol
    }

/**
 * 协议请求体合并的唯一顺序：
 * 1. 协议按 [LlmConfig] 装配的 base；
 * 2. 持久化文档里的 request 扩展字段；
 * 3. 调用级显式覆盖 [LlmConfig.requestOverrides]（最高优先级，最后合并）。
 *
 * 不再根据“值是否等于 0.8/1024”猜测本次调用是否覆盖；需要本次覆盖的调用方
 * 必须通过 [withCallOverrides] 写入 requestOverrides。
 * 敏感字段和协议保护字段在每一层都由 [ApiConfigJson.mergeRequest] 过滤；
 * 模板里的 null 在进入协议前由 [ApiRequestTemplate.withoutNulls] 递归删除。
 */
internal fun mergeProtocolRequestBody(
    base: JsonObject,
    config: LlmConfig,
    protectedFields: Set<String>,
): JsonObject {
    val request = ApiRequestTemplate.withoutNulls(config.request)
    val overrides = ApiRequestTemplate.withoutNulls(config.requestOverrides)
    val persisted = ApiConfigJson.mergeRequest(base, request, protectedFields).json
    return ApiConfigJson.mergeRequest(persisted, overrides, protectedFields).json
}

/**
 * 生成协议相关的调用级覆盖（OpenAI/Anthropic 用顶层键，Gemini 用 generationConfig）。
 * 只有显式传入的非 null 参数才进入 requestOverrides；因此把值覆盖回 0.8/1024 这类默认值同样有效。
 */
@Suppress("LongParameterList")
internal fun LlmConfig.withCallOverrides(
    temperature: Double? = null,
    maxTokens: Int? = null,
    topP: Double? = null,
    topK: Int? = null,
    frequencyPenalty: Double? = null,
    disableThinking: Boolean? = null,
): LlmConfig {
    val normalizedType = normalizeApiType(type)
    val effectiveDisableThinking = disableThinking ?: this.disableThinking
    val overrides =
        buildJsonObject {
            when (normalizedType) {
                ApiType.GEMINI -> {
                    val generation =
                        buildJsonObject {
                            temperature?.let { put("temperature", it) }
                            maxTokens?.let { put("maxOutputTokens", it) }
                            topP?.let { put("topP", it) }
                            topK?.let { put("topK", it) }
                        }
                    if (generation.isNotEmpty()) put("generationConfig", generation)
                }
                else -> {
                    temperature?.let { put("temperature", it) }
                    maxTokens?.let { put("max_tokens", it) }
                    topP?.let { put("top_p", it) }
                    topK?.let { put("top_k", it) }
                    frequencyPenalty?.let { put("frequency_penalty", it) }
                }
            }
            // DeepSeek OpenAI 格式专有；Anthropic/Gemini 不能收到这个字段。
            if (effectiveDisableThinking && normalizedType != ApiType.ANTHROPIC && normalizedType != ApiType.GEMINI) {
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
        }
    val mergedOverrides =
        if (requestOverrides.isEmpty()) {
            overrides
        } else {
            ApiConfigJson.mergeRequest(requestOverrides, overrides, emptySet()).json
        }
    return copy(
        temperature = temperature ?: this.temperature,
        maxTokens = maxTokens ?: this.maxTokens,
        topP = topP ?: this.topP,
        topK = topK ?: this.topK,
        frequencyPenalty = frequencyPenalty ?: this.frequencyPenalty,
        disableThinking = effectiveDisableThinking,
        requestOverrides = mergedOverrides,
    )
}
