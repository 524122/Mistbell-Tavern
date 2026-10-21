package com.mistbell.tavern.android.data.api

import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Runtime options persisted in an API configuration document. */
@Serializable
data class ApiConfigRuntime(
    val timeoutSeconds: Int = 90,
    val retries: Int = 2,
)

/**
 * Versioned, complete API configuration contract.
 *
 * Secrets are deliberately represented by [apiKeyRef]. The actual key remains in SecureStore/
 * the encrypted database field and is never encoded by this model.
 */
@Serializable
data class ApiConfigDocument(
    val schemaVersion: Int = ApiConfigJson.CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val model: String,
    val apiKeyRef: String = "",
    val streamingEnabled: Boolean = true,
    val contextTokenLimit: Int = 4096,
    val runtime: ApiConfigRuntime = ApiConfigRuntime(),
    val request: JsonObject = buildJsonObject { },
)

sealed class ApiConfigJsonResult {
    data class Valid(val document: ApiConfigDocument, val migrated: Boolean = false) : ApiConfigJsonResult()

    data class Invalid(val message: String) : ApiConfigJsonResult()
}

data class ApiConfigJsonDiff(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && modified.isEmpty()
}

/** 合并/预览用的字段条目：递归到叶子后的点分路径 + 紧凑 JSON 值。 */
data class RequestFieldValue(
    val path: String,
    val value: String,
) {
    override fun toString(): String = "$path = $value"
}

data class RequestMergeResult(
    val json: JsonObject,
    val applied: List<RequestFieldValue>,
    val ignored: List<RequestFieldValue>,
) {
    val appliedFields: List<String> get() = applied.map { it.path }
    val ignoredFields: List<String> get() = ignored.map { it.path }
}

/** Pure JSON operations shared by persistence, the editor, protocols and tests. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object ApiConfigJson {
    const val CURRENT_SCHEMA_VERSION = 1
    const val DEFAULT_CONTEXT_TOKEN_LIMIT = 4096
    const val MIN_CONTEXT_TOKEN_LIMIT = 1024
    const val MAX_CONTEXT_TOKEN_LIMIT = 1_000_000

    private val prettyJson =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
        }

    private val readJson =
        Json {
            ignoreUnknownKeys = false
            isLenient = true
            explicitNulls = false
        }

    /** Parse a document, migrating the unversioned v0 shape to v1. */
    fun parse(text: String): ApiConfigJsonResult {
        if (text.isBlank()) return ApiConfigJsonResult.Invalid("JSON 不能为空")
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                return ApiConfigJsonResult.Invalid("JSON 格式错误: ${e.message.orEmpty()}")
            }
        if (root !is JsonObject) return ApiConfigJsonResult.Invalid("配置 JSON 顶层必须是 object")
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version > CURRENT_SCHEMA_VERSION) {
            return ApiConfigJsonResult.Invalid("不支持的 schemaVersion: $version")
        }
        val sensitive = findSensitiveFields(root)
        if (sensitive.isNotEmpty()) {
            return ApiConfigJsonResult.Invalid("配置包含禁止导入的敏感字段: ${sensitive.joinToString()}")
        }
        val migratedRoot = migrateRoot(root, version)
        return try {
            val decoded = readJson.decodeFromJsonElement(ApiConfigDocument.serializer(), migratedRoot)
            // 类型统一规范化（Gemini/Anthropic 大小写/空白不能改变协议分支）；
            // 导入的 URL userinfo/query 凭据直接移除，绝不随文档落库。
            val sanitizedBaseUrl =
                ApiUrl.sanitizeBaseUrl(decoded.baseUrl)
                    ?: return ApiConfigJsonResult.Invalid("baseUrl 无法安全清理，请修正后再导入")
            val document =
                decoded.copy(
                    type = normalizeApiType(decoded.type),
                    baseUrl = sanitizedBaseUrl,
                )
            validate(document)?.let { ApiConfigJsonResult.Invalid(it) }
                ?: ApiConfigJsonResult.Valid(document, migrated = version == 0)
        } catch (e: Exception) {
            ApiConfigJsonResult.Invalid("配置字段错误: ${e.message.orEmpty()}")
        }
    }

    fun format(
        document: ApiConfigDocument,
        requestFirst: Boolean = false,
    ): String {
        val root = prettyJson.encodeToJsonElement(ApiConfigDocument.serializer(), document).jsonObject
        val orderedRoot =
            if (requestFirst && root["request"] != null) {
                val ordered = linkedMapOf<String, JsonElement>()
                ordered["request"] = root.getValue("request")
                root.forEach { (key, value) ->
                    if (key != "request") ordered[key] = value
                }
                JsonObject(ordered)
            } else {
                root
            }
        return prettyJson.encodeToString(JsonObject.serializer(), orderedRoot)
    }

    /** Pure Kotlin schema migration hook; future versions extend this chain without Room changes. */
    fun migrateRoot(
        root: JsonObject,
        version: Int,
    ): JsonObject =
        when (version) {
            0 -> JsonObject(root + ("schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION)))
            else -> root
        }

    fun validate(document: ApiConfigDocument): String? {
        if (document.schemaVersion != CURRENT_SCHEMA_VERSION) return "不支持的 schemaVersion: ${document.schemaVersion}"
        if (document.id.isBlank()) return "id 不能为空"
        if (document.name.isBlank()) return "name 不能为空"
        if (document.type.isBlank()) return "type 不能为空"
        if (document.baseUrl.isBlank()) return "baseUrl 不能为空"
        val sanitizedBaseUrl =
            ApiUrl.sanitizeBaseUrl(document.baseUrl)
                ?: return "baseUrl 包含无法安全清理的凭据或格式错误"
        if (sanitizedBaseUrl != document.baseUrl) return "baseUrl 包含未清理的凭据"
        if (document.model.isBlank()) return "model 不能为空"
        if (document.contextTokenLimit !in MIN_CONTEXT_TOKEN_LIMIT..MAX_CONTEXT_TOKEN_LIMIT) {
            return "contextTokenLimit 必须在 $MIN_CONTEXT_TOKEN_LIMIT..$MAX_CONTEXT_TOKEN_LIMIT 之间"
        }
        if (document.runtime.timeoutSeconds !in 15..600) return "runtime.timeoutSeconds 必须在 15..600 之间"
        if (document.runtime.retries !in 0..5) return "runtime.retries 必须在 0..5 之间"
        val keyRef = document.apiKeyRef
        if (keyRef.isNotBlank() && keyRef != "[已配置]" && !keyRef.startsWith("secure:")) {
            return "apiKeyRef 只能使用 secure: 引用或 [已配置]"
        }
        return null
    }

    fun fromEntity(entity: ApiConfigEntity): ApiConfigDocument =
        fromEntityForBackfill(entity)
            ?: fromFields(
                id = entity.id,
                name = entity.name,
                type = entity.type,
                baseUrl = entity.apiUrl,
                model = entity.model,
                hasApiKey = entity.apiKey.isNotBlank(),
                streamingEnabled = entity.streamingEnabled,
                contextTokenLimit = if (entity.context1m) MAX_CONTEXT_TOKEN_LIMIT else DEFAULT_CONTEXT_TOKEN_LIMIT,
            )

    /**
     * 自动回填/规范化前使用：非空且不可解析（含未来 schemaVersion）的 configJson 返回 null，
     * 调用方必须保留原文，避免静默丢掉未知 request 字段。
     */
    fun fromEntityForBackfill(entity: ApiConfigEntity): ApiConfigDocument? {
        val stored = entity.configJson
        if (stored.isNotBlank()) {
            return when (val parsed = parse(stored)) {
                is ApiConfigJsonResult.Valid ->
                    parsed.document.copy(
                        id = entity.id,
                        baseUrl = stripUrlCredentials(parsed.document.baseUrl),
                        apiKeyRef = if (entity.apiKey.isBlank()) "" else "secure:${entity.id}",
                    )
                is ApiConfigJsonResult.Invalid -> null
            }
        }
        val sanitizedBaseUrl =
            ApiUrl.sanitizeBaseUrl(entity.apiUrl)
                ?: return null
        return fromFields(
            id = entity.id,
            name = entity.name,
            type = entity.type,
            baseUrl = sanitizedBaseUrl,
            model = entity.model,
            hasApiKey = entity.apiKey.isNotBlank(),
            streamingEnabled = entity.streamingEnabled,
            contextTokenLimit = if (entity.context1m) MAX_CONTEXT_TOKEN_LIMIT else DEFAULT_CONTEXT_TOKEN_LIMIT,
        )
    }

    fun fromFields(
        id: String,
        name: String,
        type: String,
        baseUrl: String,
        model: String,
        hasApiKey: Boolean,
        streamingEnabled: Boolean,
        contextTokenLimit: Int,
        runtime: ApiConfigRuntime = ApiConfigRuntime(),
        request: JsonObject = buildJsonObject { },
    ): ApiConfigDocument =
        ApiConfigDocument(
            id = id,
            name = name,
            type = normalizeApiType(type),
            // 畸形地址保留为空串，由 validate/保存入口拒绝；绝不把猜测结果或原始凭据写入文档。
            baseUrl = ApiUrl.sanitizeBaseUrl(baseUrl).orEmpty(),
            model = model,
            apiKeyRef = if (hasApiKey) "[已配置]" else "",
            streamingEnabled = streamingEnabled,
            contextTokenLimit = contextTokenLimit.coerceIn(MIN_CONTEXT_TOKEN_LIMIT, MAX_CONTEXT_TOKEN_LIMIT),
            runtime = runtime,
            // fromFields 是通用字段映射：不在后台强制物化模板；编辑器/新配置创建负责显式传入模板。
            request = request,
        )

    /** Remove a secret marker from exported JSON while preserving its presence. */
    fun redacted(
        document: ApiConfigDocument,
        hasApiKey: Boolean,
    ): ApiConfigDocument =
        document.copy(
            apiKeyRef = if (hasApiKey) "[已配置]" else "",
            baseUrl = redactUrl(document.baseUrl),
        )

    fun diff(
        before: ApiConfigDocument,
        after: ApiConfigDocument,
    ): ApiConfigJsonDiff {
        val old = Json.parseToJsonElement(format(before)).jsonObject
        val new = Json.parseToJsonElement(format(after)).jsonObject
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val modified = mutableListOf<String>()
        diffElements(old, new, "", added, removed, modified)
        return ApiConfigJsonDiff(added, removed, modified)
    }

    /** Recursively merge extension fields; arrays/scalars replace, objects merge. */
    fun mergeRequest(
        base: JsonObject,
        extension: JsonObject,
        protectedFields: Set<String> = DEFAULT_PROTECTED_FIELDS,
    ): RequestMergeResult {
        val applied = mutableListOf<RequestFieldValue>()
        val ignored = mutableListOf<RequestFieldValue>()
        val merged = mergeObject(base, extension, "", protectedFields, applied, ignored)
        return RequestMergeResult(merged, applied, ignored)
    }

    /**
     * 各接口协议受保护字段（用户 request JSON 不得覆盖）：
     * 与协议适配器共用同一份定义，预览/诊断与实际请求看到的一致。
     */
    fun protectedFieldsForType(type: String): Set<String> = chatProtocolFor(type).protectedRequestFields

    /** 所有协议共用的 request 合并：保护字段与敏感凭据字段都不允许从扩展 JSON 旁路进入请求体。 */
    fun mergeRequestForType(
        base: JsonObject,
        extension: JsonObject,
        type: String,
    ): RequestMergeResult = mergeRequest(base, extension, protectedFieldsForType(type))

    /** 递归展开 request 扩展字段为叶子条目（预览用：thinking.type、reasoning_effort 等）。 */
    fun flattenRequest(request: JsonObject): List<RequestFieldValue> = request.flatMap { (key, value) -> flattenLeaves(value, key) }

    private fun flattenLeaves(
        element: JsonElement,
        path: String,
    ): List<RequestFieldValue> =
        if (element is JsonObject && element.isNotEmpty()) {
            element.flatMap { (key, value) -> flattenLeaves(value, "$path.$key") }
        } else {
            listOf(RequestFieldValue(path, element.toString()))
        }

    private fun mergeObject(
        base: JsonObject,
        extension: JsonObject,
        path: String,
        protectedFields: Set<String>,
        applied: MutableList<RequestFieldValue>,
        ignored: MutableList<RequestFieldValue>,
        schemaContext: Boolean = false,
    ): JsonObject {
        val result = base.toMutableMap()
        val normalizedProtected = protectedFields.map(ApiSecrets::normalizeFieldName).toSet()
        extension.forEach { (key, value) ->
            val fieldPath = if (path.isBlank()) key else "$path.$key"
            val childSchema = schemaContext || isSchemaContainerKey(key, path)
            // 保护字段只在协议请求体根层生效；JSON Schema 子树里的 model/password 是属性定义，不是实际字段。
            val protectedAtRoot = path.isBlank() && ApiSecrets.normalizeFieldName(key) in normalizedProtected
            val sensitive = !schemaContext && ApiSecrets.isSensitiveFieldName(key)
            if (protectedAtRoot || sensitive) {
                ignored += flattenLeaves(value, fieldPath)
            } else {
                val old = result[key]
                result[key] =
                    when {
                        value is JsonObject && value.isNotEmpty() -> {
                            val oldObject = old as? JsonObject ?: buildJsonObject { }
                            mergeObject(
                                oldObject,
                                value,
                                fieldPath,
                                protectedFields,
                                applied,
                                ignored,
                                schemaContext = childSchema,
                            )
                        }
                        value is JsonArray -> {
                            val sanitized =
                                sanitizeArray(
                                    array = value,
                                    path = fieldPath,
                                    schemaContext = schemaContext,
                                    protectedFields = protectedFields,
                                    ignored = ignored,
                                )
                            applied += flattenLeaves(sanitized, fieldPath)
                            sanitized
                        }
                        else -> {
                            applied += flattenLeaves(value, fieldPath)
                            value
                        }
                    }
            }
        }
        return JsonObject(result)
    }

    /**
     * 数组内容按同一上下文过滤；已进入 schema 上下文时原样保留，
     * 避免把 tools[].function.parameters.properties.password 这类属性定义误删。
     */
    private fun sanitizeArray(
        array: JsonArray,
        path: String,
        schemaContext: Boolean,
        protectedFields: Set<String>,
        ignored: MutableList<RequestFieldValue>,
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
                                protectedFields = protectedFields,
                                // 数组整体作为 applied 一项记录，元素内部不重复登记
                                applied = mutableListOf(),
                                ignored = ignored,
                                schemaContext = false,
                            )
                        }
                    is JsonArray ->
                        sanitizeArray(
                            array = element,
                            path = elementPath,
                            schemaContext = false,
                            protectedFields = protectedFields,
                            ignored = ignored,
                        )
                    else -> element
                }
            },
        )
    }

    /** JSON Schema 定义容器：其中的 key 是用户数据模型，不是真实请求字段。 */
    private fun isSchemaContainerKey(
        key: String,
        parentPath: String,
    ): Boolean {
        val normalizedKey = ApiSecrets.normalizeFieldName(key)
        if (normalizedKey in SCHEMA_CONTAINER_KEYS) return true
        val normalizedPath = ApiSecrets.normalizeFieldName(parentPath)
        return when (normalizedKey) {
            "parameters", "parameter" ->
                "tools" in normalizedPath || "functions" in normalizedPath || "functiondeclarations" in normalizedPath
            "schema" -> "jsonschema" in normalizedPath
            else -> false
        }
    }

    private fun diffElements(
        old: JsonObject,
        new: JsonObject,
        path: String,
        added: MutableList<String>,
        removed: MutableList<String>,
        modified: MutableList<String>,
    ) {
        old.keys.minus(new.keys).forEach { key -> removed += joinPath(path, key) }
        new.keys.minus(old.keys).forEach { key -> added += joinPath(path, key) }
        old.keys.intersect(new.keys).forEach { key ->
            val childPath = joinPath(path, key)
            val oldValue = old.getValue(key)
            val newValue = new.getValue(key)
            if (oldValue is JsonObject && newValue is JsonObject) {
                diffElements(oldValue, newValue, childPath, added, removed, modified)
            } else if (oldValue != newValue) {
                modified += childPath
            }
        }
    }

    private fun joinPath(
        path: String,
        key: String,
    ): String = if (path.isBlank()) key else "$path.$key"

    private fun findSensitiveFields(
        element: JsonElement,
        path: String = "",
        schemaContext: Boolean = false,
    ): List<String> {
        return when (element) {
            is JsonObject ->
                element.flatMap { (key, value) ->
                    val current = if (path.isBlank()) key else "$path.$key"
                    when {
                        schemaContext -> findSensitiveFields(value, current, schemaContext = true)
                        isSchemaContainerKey(key, path) -> findSensitiveFields(value, current, schemaContext = true)
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
    }

    private val SCHEMA_CONTAINER_KEYS =
        setOf(
            "jsonschema",
            "inputschema",
            "responseschema",
            "responsejsonschema",
        )

    /**
     * 所有协议通用的受保护字段：messages/stream 由调用方装配，model 由表单字段权威管理，
     * authorization/api_key 永不进请求体。各协议在此基础上追加（见 [protectedFieldsForType]）。
     * 敏感字段名判定统一走 [ApiSecrets]，这里仅保留协议装配字段。
     */
    val DEFAULT_PROTECTED_FIELDS = setOf("messages", "stream", "model", "authorization", "api_key", "apikey")

    /** Hide URL userinfo/query credentials in previews, connection errors and diagnostics. */
    fun redactUrl(url: String): String = ApiUrl.redactCredentials(url)

    /** Keep credentials out of the persisted API URL; the real key belongs in SecureStore. */
    fun stripUrlCredentials(url: String): String = ApiUrl.stripCredentials(url)

    /** Persistence boundary: null means the malformed URL must be rejected, not guessed/sanitized. */
    fun sanitizeBaseUrl(url: String): String? = ApiUrl.sanitizeBaseUrl(url)
}
