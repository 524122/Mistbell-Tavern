package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

enum class ApiRequestFieldState {
    USER,
    DEFAULT,
    PROTECTED,
}

data class ApiRequestPreviewField(
    val path: String,
    val value: String,
    val state: ApiRequestFieldState,
)

data class ApiRequestPreviewGroup(
    val id: String,
    val title: String,
    val fields: List<ApiRequestPreviewField>,
)

data class ApiRequestPreview(
    val generatedFields: List<ApiRequestPreviewField>,
    val parameterGroups: List<ApiRequestPreviewGroup>,
    val ignoredFields: List<ApiRequestPreviewField>,
) {
    val userFieldCount: Int
        get() = parameterGroups.sumOf { group -> group.fields.count { it.state == ApiRequestFieldState.USER } }

    val defaultFieldCount: Int
        get() = parameterGroups.sumOf { group -> group.fields.count { it.state == ApiRequestFieldState.DEFAULT } }

    companion object {
        const val UNSET_VALUE = "未设置"

        /**
         * 预览与真实请求共用 [ApiRequestTemplate.withoutNulls]：
         * null 不计入 USER、不显示为会发送；模板值相等显示 DEFAULT 实际值。
         */
        fun from(document: ApiConfigDocument): ApiRequestPreview {
            val type = normalizeApiType(document.type)
            val templateFields = ApiRequestTemplate.templateFields(type)
            val templateByPath = templateFields.associateBy { it.path }
            val knownPaths = templateByPath.keys
            val request = ApiRequestTemplate.withoutNulls(document.request)

            val protectedFields = ApiConfigJson.protectedFieldsForType(type)
            val merge =
                ApiConfigJson.mergeRequest(
                    base = buildJsonObject { },
                    extension = request,
                    protectedFields = protectedFields,
                )

            val ignoredPaths = merge.ignored.map { it.path }.toSet()
            val specs = groupSpecsForType(type)
            val fieldsByGroup = linkedMapOf<String, LinkedHashMap<String, ApiRequestPreviewField>>()
            specs.forEach { spec ->
                val fields = linkedMapOf<String, ApiRequestPreviewField>()
                spec.paths.forEach { path ->
                    val templateValue = templateByPath.getValue(path).value
                    fields[path] = previewField(path, request.valueAt(path), templateValue)
                }
                fieldsByGroup[spec.id] = fields
            }

            // 非空未知字段作为 USER 归入对应分组；模板路径的后代已由已知字段整体表达，避免重复。
            ApiRequestTemplate
                .flattenLeaves(request)
                .filter { field ->
                    field.path.isNotBlank() &&
                        field.path !in knownPaths &&
                        knownPaths.none { known -> field.path.startsWith("$known.") } &&
                        ignoredPaths.none { ignored ->
                            field.path == ignored ||
                                field.path.startsWith("$ignored.") ||
                                ignored.startsWith("${field.path}.") ||
                                ignored.startsWith("${field.path}[")
                        } &&
                        field.value.isNonEmptyUnknown()
                }.forEach { field ->
                    val groupId = groupIdForPath(type, field.path)
                    val groupFields = fieldsByGroup[groupId] ?: fieldsByGroup.getValue(CUSTOM_GROUP_ID)
                    groupFields[field.path] =
                        ApiRequestPreviewField(
                            path = field.path,
                            value = field.value.toString(),
                            state = ApiRequestFieldState.USER,
                        )
                }

            val groups =
                specs.mapNotNull { spec ->
                    val fields = fieldsByGroup.getValue(spec.id).values.toList()
                    if (spec.id == CUSTOM_GROUP_ID && fields.isEmpty()) {
                        null
                    } else {
                        ApiRequestPreviewGroup(spec.id, spec.title, fields)
                    }
                }

            return ApiRequestPreview(
                generatedFields = generatedFields(document),
                parameterGroups = groups,
                ignoredFields =
                    merge.ignored.map { field ->
                        ApiRequestPreviewField(field.path, field.value, ApiRequestFieldState.PROTECTED)
                    },
            )
        }

        private fun previewField(
            path: String,
            requestValue: JsonElement?,
            templateValue: JsonElement,
        ): ApiRequestPreviewField {
            val state =
                when {
                    requestValue == null -> ApiRequestFieldState.DEFAULT
                    templateValue is JsonNull -> ApiRequestFieldState.USER
                    requestValue == templateValue -> ApiRequestFieldState.DEFAULT
                    else -> ApiRequestFieldState.USER
                }
            val value =
                when {
                    requestValue == null -> UNSET_VALUE
                    else -> requestValue.toString()
                }
            return ApiRequestPreviewField(path, value, state)
        }
    }
}

private const val CUSTOM_GROUP_ID = "custom"

private data class RequestGroupSpec(
    val id: String,
    val title: String,
    val paths: List<String> = emptyList(),
)

private fun groupSpecsForType(type: String): List<RequestGroupSpec> =
    when (normalizeApiType(type)) {
        ApiType.GEMINI ->
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
        ApiType.ANTHROPIC ->
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
        else ->
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
    }

private fun groupIdForPath(
    type: String,
    path: String,
): String =
    when (normalizeApiType(type)) {
        ApiType.GEMINI ->
            when {
                path.hasJsonPathPrefix("generationConfig.thinkingConfig") -> "thinkingConfig"
                path.hasJsonPathPrefix("generationConfig") -> "generationConfig"
                path.hasJsonPathPrefix("safetySettings") -> "safetySettings"
                else -> CUSTOM_GROUP_ID
            }
        ApiType.ANTHROPIC ->
            when {
                path.hasJsonPathPrefix("thinking") -> "thinking"
                path.hasJsonPathPrefix("metadata") -> "metadata"
                else -> CUSTOM_GROUP_ID
            }
        else ->
            when {
                path.hasJsonPathPrefix("thinking") ||
                    path.hasJsonPathPrefix("reasoning") ||
                    path == "reasoning_effort" -> "thinking"
                path.hasJsonPathPrefix("response_format") -> "response_format"
                else -> CUSTOM_GROUP_ID
            }
    }

private fun String.hasJsonPathPrefix(prefix: String): Boolean = this == prefix || startsWith("$prefix.")

private fun JsonObject.valueAt(path: String): JsonElement? {
    var current: JsonElement = this
    path.split('.').forEach { segment ->
        val objectValue = current as? JsonObject ?: return null
        current = objectValue[segment] ?: return null
    }
    return current
}

private fun JsonElement.isNonEmptyUnknown(): Boolean =
    when (this) {
        is JsonObject -> isNotEmpty()
        is JsonArray -> isNotEmpty()
        is JsonNull -> false
        else -> true
    }

private fun generatedFields(document: ApiConfigDocument): List<ApiRequestPreviewField> =
    buildList {
        add(ApiRequestPreviewField("model", document.model, ApiRequestFieldState.PROTECTED))
        when (normalizeApiType(document.type)) {
            ApiType.GEMINI -> {
                add(ApiRequestPreviewField("contents", "由当前会话消息生成", ApiRequestFieldState.PROTECTED))
                add(ApiRequestPreviewField("systemInstruction", "由 system 消息生成", ApiRequestFieldState.PROTECTED))
            }
            ApiType.ANTHROPIC -> {
                add(ApiRequestPreviewField("messages", "由当前会话消息生成", ApiRequestFieldState.PROTECTED))
                add(ApiRequestPreviewField("system", "由 system 消息生成", ApiRequestFieldState.PROTECTED))
            }
            else -> {
                add(ApiRequestPreviewField("messages", "由当前会话消息生成", ApiRequestFieldState.PROTECTED))
            }
        }
        add(ApiRequestPreviewField("stream", document.streamingEnabled.toString(), ApiRequestFieldState.PROTECTED))
    }
