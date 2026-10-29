package com.mistbell.tavern.android.data.api

import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
object ApiConfigJson : ApiConfigRequestOperations() {
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

    /** Parse a document, migrating the unversioned v0 shape to v1. */
    fun parse(text: String): ApiConfigJsonResult = ApiConfigDocumentParser.parse(text)

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

    fun validate(document: ApiConfigDocument): String? = ApiConfigDocumentValidator.validate(document)

    fun fromEntity(entity: ApiConfigEntity): ApiConfigDocument =
        fromEntityForBackfill(entity)
            ?: fromFields(
                fields =
                    ApiConfigFields(
                        id = entity.id,
                        name = entity.name,
                        type = entity.type,
                        baseUrl = entity.apiUrl,
                        model = entity.model,
                        hasApiKey = entity.apiKey.isNotBlank(),
                        streamingEnabled = entity.streamingEnabled,
                        contextTokenLimit =
                            if (entity.context1m) MAX_CONTEXT_TOKEN_LIMIT else DEFAULT_CONTEXT_TOKEN_LIMIT,
                    ),
            )

    /**
     * 自动回填/规范化前使用：非空且不可解析（含未来 schemaVersion）的 configJson 返回 null，
     * 调用方必须保留原文，避免静默丢掉未知 request 字段。
     */
    fun fromEntityForBackfill(entity: ApiConfigEntity): ApiConfigDocument? =
        if (entity.configJson.isNotBlank()) {
            when (val parsed = parse(entity.configJson)) {
                is ApiConfigJsonResult.Valid ->
                    parsed.document.copy(
                        id = entity.id,
                        baseUrl = stripUrlCredentials(parsed.document.baseUrl),
                        apiKeyRef = if (entity.apiKey.isBlank()) "" else "secure:${entity.id}",
                    )
                is ApiConfigJsonResult.Invalid -> null
            }
        } else {
            fromLegacyEntity(entity)
        }

    private fun fromLegacyEntity(entity: ApiConfigEntity): ApiConfigDocument? =
        ApiUrl.sanitizeBaseUrl(entity.apiUrl)?.let { sanitizedBaseUrl ->
            fromFields(
                fields =
                    ApiConfigFields(
                        id = entity.id,
                        name = entity.name,
                        type = entity.type,
                        baseUrl = sanitizedBaseUrl,
                        model = entity.model,
                        hasApiKey = entity.apiKey.isNotBlank(),
                        streamingEnabled = entity.streamingEnabled,
                        contextTokenLimit =
                            if (entity.context1m) MAX_CONTEXT_TOKEN_LIMIT else DEFAULT_CONTEXT_TOKEN_LIMIT,
                    ),
            )
        }

    fun fromFields(fields: ApiConfigFields): ApiConfigDocument =
        with(fields) {
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
        }

    /** Remove a secret marker from exported JSON while preserving its presence. */
    fun redacted(
        document: ApiConfigDocument,
        hasApiKey: Boolean,
    ): ApiConfigDocument =
        document.copy(
            apiKeyRef = if (hasApiKey) "[已配置]" else "",
            baseUrl = redactUrl(document.baseUrl),
        )

    /**
     * 所有协议通用的受保护字段：messages/stream 由调用方装配，model 由表单字段权威管理，
     * authorization/api_key 永不进请求体。各协议在此基础上追加（见 [protectedFieldsForType]）。
     * 敏感字段名判定统一走 [ApiSecrets]，这里仅保留协议装配字段。
     */
    val DEFAULT_PROTECTED_FIELDS =
        setOf("messages", "stream", "model", "authorization", "api_key", "apikey")
}
