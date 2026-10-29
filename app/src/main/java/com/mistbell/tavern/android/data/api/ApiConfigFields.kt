package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** 通用配置字段映射入参；模板仅在调用方显式提供时写入，不后台物化。 */
data class ApiConfigFields(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
    val streamingEnabled: Boolean,
    val contextTokenLimit: Int,
    val runtime: ApiConfigRuntime = ApiConfigRuntime(),
    val request: JsonObject = buildJsonObject { },
)
