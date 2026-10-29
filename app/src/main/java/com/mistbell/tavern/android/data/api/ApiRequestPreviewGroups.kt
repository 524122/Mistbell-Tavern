package com.mistbell.tavern.android.data.api

internal const val CUSTOM_GROUP_ID = "custom"

internal data class RequestGroupSpec(
    val id: String,
    val title: String,
    val paths: List<String> = emptyList(),
)

internal fun groupSpecsForType(type: String): List<RequestGroupSpec> =
    when (normalizeApiType(type)) {
        ApiType.GEMINI -> geminiGroupSpecs()
        ApiType.ANTHROPIC -> anthropicGroupSpecs()
        else -> openAiGroupSpecs()
    }

private fun geminiGroupSpecs(): List<RequestGroupSpec> =
    listOf(
        RequestGroupSpec(
            id = "generationConfig",
            title = "生成配置 · generationConfig",
            paths =
                listOf(
                    "generationConfig.maxOutputTokens",
                    "generationConfig.temperature",
                    "generationConfig.topP",
                    "generationConfig.topK",
                    "generationConfig.responseMimeType",
                    "generationConfig.responseSchema",
                ),
        ),
        RequestGroupSpec(
            id = "thinkingConfig",
            title = "思考配置 · thinkingConfig",
            paths = listOf("generationConfig.thinkingConfig"),
        ),
        RequestGroupSpec(
            id = "safetySettings",
            title = "安全设置 · safetySettings",
            paths = listOf("safetySettings"),
        ),
        RequestGroupSpec(CUSTOM_GROUP_ID, "自定义字段"),
    )

private fun anthropicGroupSpecs(): List<RequestGroupSpec> =
    listOf(
        RequestGroupSpec(
            id = "sampling",
            title = "采样参数 · sampling",
            paths =
                listOf(
                    "temperature",
                    "max_tokens",
                    "top_p",
                    "top_k",
                ),
        ),
        RequestGroupSpec(
            id = "thinking",
            title = "思考参数 · thinking",
            paths = listOf("thinking"),
        ),
        RequestGroupSpec(
            id = "metadata",
            title = "元数据 · metadata",
            paths = listOf("metadata"),
        ),
        RequestGroupSpec(CUSTOM_GROUP_ID, "自定义字段"),
    )

private fun openAiGroupSpecs(): List<RequestGroupSpec> =
    listOf(
        RequestGroupSpec(
            id = "sampling",
            title = "采样参数 · sampling",
            paths =
                listOf(
                    "temperature",
                    "max_tokens",
                    "top_p",
                    "top_k",
                    "frequency_penalty",
                ),
        ),
        RequestGroupSpec(
            id = "thinking",
            title = "思考参数 · thinking / reasoning",
            paths = listOf("thinking", "reasoning_effort"),
        ),
        RequestGroupSpec(
            id = "response_format",
            title = "响应格式 · response_format",
            paths = listOf("response_format"),
        ),
        RequestGroupSpec(CUSTOM_GROUP_ID, "自定义字段"),
    )

internal fun groupIdForPath(
    type: String,
    path: String,
): String =
    when (normalizeApiType(type)) {
        ApiType.GEMINI -> geminiGroupId(path)
        ApiType.ANTHROPIC -> anthropicGroupId(path)
        else -> openAiGroupId(path)
    }

private fun geminiGroupId(path: String): String =
    when {
        path.hasJsonPathPrefix("generationConfig.thinkingConfig") -> "thinkingConfig"
        path.hasJsonPathPrefix("generationConfig") -> "generationConfig"
        path.hasJsonPathPrefix("safetySettings") -> "safetySettings"
        else -> CUSTOM_GROUP_ID
    }

private fun anthropicGroupId(path: String): String =
    when {
        path.hasJsonPathPrefix("thinking") -> "thinking"
        path.hasJsonPathPrefix("metadata") -> "metadata"
        else -> CUSTOM_GROUP_ID
    }

private fun openAiGroupId(path: String): String =
    when {
        path.hasJsonPathPrefix("thinking") ||
            path.hasJsonPathPrefix("reasoning") ||
            path == "reasoning_effort" -> "thinking"
        path.hasJsonPathPrefix("response_format") -> "response_format"
        else -> CUSTOM_GROUP_ID
    }

private fun String.hasJsonPathPrefix(prefix: String): Boolean = this == prefix || startsWith("$prefix.")
