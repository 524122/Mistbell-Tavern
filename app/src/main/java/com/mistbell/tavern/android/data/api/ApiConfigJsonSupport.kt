package com.mistbell.tavern.android.data.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private const val MIN_TIMEOUT_SECONDS = 15
private const val MAX_TIMEOUT_SECONDS = 600
private const val MIN_RETRIES = 0
private const val MAX_RETRIES = 5

@OptIn(ExperimentalSerializationApi::class)
internal object ApiConfigDocumentParser {
    private val readJson =
        Json {
            ignoreUnknownKeys = false
            isLenient = true
            explicitNulls = false
        }

    fun parse(text: String): ApiConfigJsonResult =
        if (text.isBlank()) {
            ApiConfigJsonResult.Invalid("JSON 不能为空")
        } else {
            parseRoot(text)
        }

    private fun parseRoot(text: String): ApiConfigJsonResult {
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return ApiConfigJsonResult.Invalid("JSON 格式错误: ${e.message.orEmpty()}")
            }
        return if (root is JsonObject) {
            validateRoot(root)
        } else {
            ApiConfigJsonResult.Invalid("配置 JSON 顶层必须是 object")
        }
    }

    private fun validateRoot(root: JsonObject): ApiConfigJsonResult {
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        val sensitive = findSensitiveFields(root)
        return when {
            version > ApiConfigJson.CURRENT_SCHEMA_VERSION ->
                ApiConfigJsonResult.Invalid("不支持的 schemaVersion: $version")
            sensitive.isNotEmpty() ->
                ApiConfigJsonResult.Invalid("配置包含禁止导入的敏感字段: ${sensitive.joinToString()}")
            else -> decodeRoot(root, version)
        }
    }

    private fun decodeRoot(
        root: JsonObject,
        version: Int,
    ): ApiConfigJsonResult {
        val migratedRoot = ApiConfigJson.migrateRoot(root, version)
        return try {
            val decoded = readJson.decodeFromJsonElement(ApiConfigDocument.serializer(), migratedRoot)
            val sanitizedBaseUrl = ApiUrl.sanitizeBaseUrl(decoded.baseUrl)
            if (sanitizedBaseUrl == null) {
                ApiConfigJsonResult.Invalid("baseUrl 无法安全清理，请修正后再导入")
            } else {
                val document =
                    decoded.copy(
                        type = normalizeApiType(decoded.type),
                        baseUrl = sanitizedBaseUrl,
                    )
                ApiConfigJson.validate(document)?.let { ApiConfigJsonResult.Invalid(it) }
                    ?: ApiConfigJsonResult.Valid(
                        document,
                        migrated = version == 0,
                    )
            }
        } catch (e: SerializationException) {
            ApiConfigJsonResult.Invalid("配置字段错误: ${e.message.orEmpty()}")
        } catch (e: IllegalArgumentException) {
            ApiConfigJsonResult.Invalid("配置字段错误: ${e.message.orEmpty()}")
        }
    }
}

internal object ApiConfigDocumentValidator {
    fun validate(document: ApiConfigDocument): String? =
        validateRequiredFields(document)
            ?: validateLimits(document)
            ?: validateKeyReference(document.apiKeyRef)

    private fun validateRequiredFields(document: ApiConfigDocument): String? =
        when {
            document.schemaVersion != ApiConfigJson.CURRENT_SCHEMA_VERSION ->
                "不支持的 schemaVersion: ${document.schemaVersion}"
            document.id.isBlank() -> "id 不能为空"
            document.name.isBlank() -> "name 不能为空"
            document.type.isBlank() -> "type 不能为空"
            document.baseUrl.isBlank() -> "baseUrl 不能为空"
            else -> validateUrlAndModel(document)
        }

    private fun validateUrlAndModel(document: ApiConfigDocument): String? {
        val sanitizedBaseUrl = ApiUrl.sanitizeBaseUrl(document.baseUrl)
        return when {
            sanitizedBaseUrl == null -> "baseUrl 包含无法安全清理的凭据或格式错误"
            sanitizedBaseUrl != document.baseUrl -> "baseUrl 包含未清理的凭据"
            document.model.isBlank() -> "model 不能为空"
            else -> null
        }
    }

    private fun validateLimits(document: ApiConfigDocument): String? =
        when {
            document.contextTokenLimit !in
                ApiConfigJson.MIN_CONTEXT_TOKEN_LIMIT..ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT ->
                "contextTokenLimit 必须在 ${ApiConfigJson.MIN_CONTEXT_TOKEN_LIMIT}.." +
                    "${ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT} 之间"
            document.runtime.timeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS ->
                "runtime.timeoutSeconds 必须在 $MIN_TIMEOUT_SECONDS..$MAX_TIMEOUT_SECONDS 之间"
            document.runtime.retries !in MIN_RETRIES..MAX_RETRIES ->
                "runtime.retries 必须在 $MIN_RETRIES..$MAX_RETRIES 之间"
            else -> null
        }

    private fun validateKeyReference(keyRef: String): String? =
        if (keyRef.isNotBlank() && keyRef != "[已配置]" && !keyRef.startsWith("secure:")) {
            "apiKeyRef 只能使用 secure: 引用或 [已配置]"
        } else {
            null
        }
}

internal object ApiConfigRequestMerger {
    fun merge(
        base: JsonObject,
        extension: JsonObject,
        protectedFields: Set<String>,
    ): RequestMergeResult {
        val state = RequestMergeState(protectedFields)
        val merged = mergeObject(base, extension, "", state)
        return RequestMergeResult(merged, state.applied, state.ignored)
    }

    fun flatten(request: JsonObject): List<RequestFieldValue> {
        return request.flatMap { (key, value) -> flattenLeaves(value, key) }
    }

    private fun mergeObject(
        base: JsonObject,
        extension: JsonObject,
        path: String,
        state: RequestMergeState,
        schemaContext: Boolean = false,
    ): JsonObject {
        val result = base.toMutableMap()
        extension.forEach { (key, value) ->
            val fieldPath = if (path.isBlank()) key else "$path.$key"
            val childSchema = schemaContext || isSchemaContainerKey(key, path)
            val protectedAtRoot =
                path.isBlank() && ApiSecrets.normalizeFieldName(key) in state.normalizedProtected
            val sensitive = !schemaContext && ApiSecrets.isSensitiveFieldName(key)
            if (protectedAtRoot || sensitive) {
                state.ignored += flattenLeaves(value, fieldPath)
            } else {
                val old = result[key]
                result[key] =
                    when {
                        value is JsonObject && value.isNotEmpty() -> {
                            val oldObject = old as? JsonObject ?: buildJsonObject { }
                            mergeObject(oldObject, value, fieldPath, state, childSchema)
                        }
                        value is JsonArray -> {
                            val sanitized = sanitizeArray(value, fieldPath, schemaContext, state)
                            state.applied += flattenLeaves(sanitized, fieldPath)
                            sanitized
                        }
                        else -> {
                            state.applied += flattenLeaves(value, fieldPath)
                            value
                        }
                    }
            }
        }
        return JsonObject(result)
    }

    private fun sanitizeArray(
        array: JsonArray,
        path: String,
        schemaContext: Boolean,
        state: RequestMergeState,
    ): JsonArray {
        if (schemaContext) return array
        return JsonArray(
            array.mapIndexed { index, element ->
                val elementPath = "$path[$index]"
                when (element) {
                    is JsonObject ->
                        if (element.isEmpty()) {
                            element
                        } else {
                            mergeObject(
                                base = buildJsonObject { },
                                extension = element,
                                path = elementPath,
                                state = state.copyForArrayElement(),
                            )
                        }
                    is JsonArray -> sanitizeArray(element, elementPath, false, state)
                    else -> element
                }
            },
        )
    }

    private fun flattenLeaves(
        element: JsonElement,
        path: String,
    ): List<RequestFieldValue> =
        if (element is JsonObject && element.isNotEmpty()) {
            element.flatMap { (key, value) -> flattenLeaves(value, "$path.$key") }
        } else {
            listOf(RequestFieldValue(path, element.toString()))
        }
}

private class RequestMergeState(
    protectedFields: Set<String>,
    val applied: MutableList<RequestFieldValue> = mutableListOf(),
    val ignored: MutableList<RequestFieldValue> = mutableListOf(),
) {
    val normalizedProtected = protectedFields.map(ApiSecrets::normalizeFieldName).toSet()

    fun copyForArrayElement(): RequestMergeState =
        RequestMergeState(
            protectedFields = normalizedProtected,
            ignored = ignored,
        )
}

internal object ApiConfigJsonDiffer {
    fun diff(
        old: String,
        new: String,
    ): ApiConfigJsonDiff {
        val changes = JsonChanges()
        changes.compare(
            Json.parseToJsonElement(old).jsonObject,
            Json.parseToJsonElement(new).jsonObject,
        )
        return ApiConfigJsonDiff(changes.added, changes.removed, changes.modified)
    }
}

private class JsonChanges {
    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()
    val modified = mutableListOf<String>()

    fun compare(
        old: JsonObject,
        new: JsonObject,
        path: String = "",
    ) {
        old.keys.minus(new.keys).forEach { key -> removed += joinPath(path, key) }
        new.keys.minus(old.keys).forEach { key -> added += joinPath(path, key) }
        old.keys.intersect(new.keys).forEach { key ->
            val childPath = joinPath(path, key)
            val oldValue = old.getValue(key)
            val newValue = new.getValue(key)
            if (oldValue is JsonObject && newValue is JsonObject) {
                compare(oldValue, newValue, childPath)
            } else if (oldValue != newValue) {
                modified += childPath
            }
        }
    }

    private fun joinPath(
        path: String,
        key: String,
    ): String = if (path.isBlank()) key else "$path.$key"
}

private fun findSensitiveFields(
    element: JsonElement,
    path: String = "",
    schemaContext: Boolean = false,
): List<String> =
    when (element) {
        is JsonObject ->
            element.flatMap { (key, value) ->
                val current = if (path.isBlank()) key else "$path.$key"
                when {
                    schemaContext -> findSensitiveFields(value, current, schemaContext = true)
                    isSchemaContainerKey(key, path) ->
                        findSensitiveFields(value, current, schemaContext = true)
                    ApiSecrets.isSensitiveFieldName(key) -> listOf(current)
                    else -> findSensitiveFields(value, current)
                }
            }
        is JsonArray ->
            element.flatMapIndexed { index, child ->
                findSensitiveFields(child, "$path[$index]", schemaContext)
            }
        else -> emptyList()
    }

private fun isSchemaContainerKey(
    key: String,
    parentPath: String,
): Boolean {
    val normalizedKey = ApiSecrets.normalizeFieldName(key)
    if (normalizedKey in SCHEMA_CONTAINER_KEYS) return true
    val normalizedPath = ApiSecrets.normalizeFieldName(parentPath)
    return when (normalizedKey) {
        "parameters", "parameter" ->
            "tools" in normalizedPath ||
                "functions" in normalizedPath ||
                "functiondeclarations" in normalizedPath
        "schema" -> "jsonschema" in normalizedPath
        else -> false
    }
}

private val SCHEMA_CONTAINER_KEYS =
    setOf(
        "jsonschema",
        "inputschema",
        "responseschema",
        "responsejsonschema",
    )
