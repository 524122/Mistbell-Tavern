package com.mistbell.tavern.android.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = DEFAULT_TEMPERATURE,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    // S1 采样细项：null = 未设置（请求体中不出现该字段）
    val topP: Double? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
    // S1 请求策略：超时秒数（钳制 15..600）与重试次数（钳制 0..5）
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val retries: Int = DEFAULT_RETRIES,
    // 关闭思考模式（DeepSeek OpenAI 格式 {"thinking":{"type":"disabled"}}）。
    // 思维链占输出 token 大头（实测记忆抽取单轮 4K 输出里约 3.5K 是思考），
    // 纯结构化任务（抽取 JSON）关掉可大幅省输出；网关不认该参数时由 LlmClient 自动回退
    val disableThinking: Boolean = false,
    // 接口类型（ApiType：openai/anthropic/gemini/custom），决定 LlmClient 走哪个协议适配
    val type: String = "openai",
    // SSE 流式传输开关：由当前 API 配置决定
    val streamingEnabled: Boolean = true,
    // 完整 API 配置文档中的 request 扩展字段；未知字段原样保留并交给协议适配器。
    val request: JsonObject = buildJsonObject { },
    // 调用级显式覆盖（例如记忆抽取的 maxTokens/temperature）；协议合并顺序：base → request → 本字段。
    val requestOverrides: JsonObject = buildJsonObject { },
    // 应用本地 prompt 输入预算；不等同于 maxTokens（模型输出预算）。
    val contextTokenLimit: Int = ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
) {
    companion object {
        const val DEFAULT_TEMPERATURE = 0.8
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TIMEOUT_SECONDS = 90
        const val DEFAULT_RETRIES = 2
    }
}
