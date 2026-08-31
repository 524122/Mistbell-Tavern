package com.mistbell.tavern.android.data.api

import kotlinx.serialization.Serializable

@Serializable
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.8,
    val maxTokens: Int = 1024,
    // S1 采样细项：null = 未设置（请求体中不出现该字段）
    val topP: Double? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
    // S1 请求策略：超时秒数（钳制 15..600）与重试次数（钳制 0..5）
    val timeoutSeconds: Int = 90,
    val retries: Int = 2
)
