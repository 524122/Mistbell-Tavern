package com.mistbell.tavern.android.data.api

import kotlinx.serialization.Serializable

@Serializable
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.8,
    val maxTokens: Int = 1024
)
