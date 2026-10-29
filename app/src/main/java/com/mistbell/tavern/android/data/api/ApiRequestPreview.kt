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
            val templateByPath = ApiRequestTemplate.templateFields(type).associateBy { it.path }
            val request = ApiRequestTemplate.withoutNulls(document.request)
            val merge =
                ApiConfigJson.mergeRequest(
                    base = buildJsonObject { },
                    extension = request,
                    protectedFields = ApiConfigJson.protectedFieldsForType(type),
                )
            val groups =
                parameterGroups(
                    type = type,
                    specs = groupSpecsForType(type),
                    request = request,
                    templateByPath = templateByPath,
                    ignoredPaths = merge.ignored.map { it.path }.toSet(),
                )
            return ApiRequestPreview(
                generatedFields = generatedFields(document),
                parameterGroups = groups,
                ignoredFields =
                    merge.ignored.map { field ->
                        ApiRequestPreviewField(field.path, field.value, ApiRequestFieldState.PROTECTED)
                    },
            )
        }

        private fun parameterGroups(
            type: String,
            specs: List<RequestGroupSpec>,
            request: JsonObject,
            templateByPath: Map<String, ApiRequestTemplateField>,
            ignoredPaths: Set<String>,
        ): List<ApiRequestPreviewGroup> {
            val fieldsByGroup = initialFieldsByGroup(specs, request, templateByPath)
            addUnknownFields(type, request, templateByPath.keys, ignoredPaths, fieldsByGroup)
            return specs.mapNotNull { spec ->
                val fields = fieldsByGroup.getValue(spec.id).values.toList()
                if (spec.id == CUSTOM_GROUP_ID && fields.isEmpty()) {
                    null
                } else {
                    ApiRequestPreviewGroup(spec.id, spec.title, fields)
                }
            }
        }

        private fun initialFieldsByGroup(
            specs: List<RequestGroupSpec>,
            request: JsonObject,
            templateByPath: Map<String, ApiRequestTemplateField>,
        ): PreviewFieldsByGroup {
            val fieldsByGroup = linkedMapOf<String, LinkedHashMap<String, ApiRequestPreviewField>>()
            specs.forEach { spec ->
                val fields = linkedMapOf<String, ApiRequestPreviewField>()
                spec.paths.forEach { path ->
                    val templateValue = templateByPath.getValue(path).value
                    fields[path] = previewField(path, request.valueAt(path), templateValue)
                }
                fieldsByGroup[spec.id] = fields
            }
            return fieldsByGroup
        }

        private fun addUnknownFields(
            type: String,
            request: JsonObject,
            knownPaths: Set<String>,
            ignoredPaths: Set<String>,
            fieldsByGroup: PreviewFieldsByGroup,
        ) {
            ApiRequestTemplate
                .flattenLeaves(request)
                .filter { field -> shouldIncludeUnknown(field, knownPaths, ignoredPaths) }
                .forEach { field ->
                    val groupId = groupIdForPath(type, field.path)
                    val groupFields = fieldsByGroup[groupId] ?: fieldsByGroup.getValue(CUSTOM_GROUP_ID)
                    groupFields[field.path] =
                        ApiRequestPreviewField(
                            path = field.path,
                            value = field.value.toString(),
                            state = ApiRequestFieldState.USER,
                        )
                }
        }

        private fun shouldIncludeUnknown(
            field: ApiRequestTemplateField,
            knownPaths: Set<String>,
            ignoredPaths: Set<String>,
        ): Boolean =
            field.path.isNotBlank() &&
                field.path !in knownPaths &&
                knownPaths.none { known -> field.path.startsWith("$known.") } &&
                ignoredPaths.none { ignored -> field.path.overlaps(ignored) } &&
                field.value.isNonEmptyUnknown()

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

private typealias PreviewFieldsByGroup =
    LinkedHashMap<String, LinkedHashMap<String, ApiRequestPreviewField>>

private fun String.overlaps(other: String): Boolean =
    this == other ||
        startsWith("$other.") ||
        other.startsWith("$this.") ||
        other.startsWith("$this[")

private fun JsonObject.valueAt(path: String): JsonElement? =
    path.split('.').fold<String, JsonElement?>(this) { current, segment ->
        (current as? JsonObject)?.get(segment)
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
