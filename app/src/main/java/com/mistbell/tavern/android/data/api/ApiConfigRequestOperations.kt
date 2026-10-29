package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.JsonObject

/** Request-oriented operations exposed through [ApiConfigJson]. */
abstract class ApiConfigRequestOperations internal constructor() {
    fun diff(
        before: ApiConfigDocument,
        after: ApiConfigDocument,
    ): ApiConfigJsonDiff =
        ApiConfigJsonDiffer.diff(
            old = ApiConfigJson.format(before),
            new = ApiConfigJson.format(after),
        )

    /** Recursively merge extension fields; arrays/scalars replace, objects merge. */
    fun mergeRequest(
        base: JsonObject,
        extension: JsonObject,
        protectedFields: Set<String> = ApiConfigJson.DEFAULT_PROTECTED_FIELDS,
    ): RequestMergeResult = ApiConfigRequestMerger.merge(base, extension, protectedFields)

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
    fun flattenRequest(request: JsonObject): List<RequestFieldValue> = ApiConfigRequestMerger.flatten(request)

    /** Hide URL userinfo/query credentials in previews, connection errors and diagnostics. */
    fun redactUrl(url: String): String = ApiUrl.redactCredentials(url)

    /** Keep credentials out of the persisted API URL; the real key belongs in SecureStore. */
    fun stripUrlCredentials(url: String): String = ApiUrl.stripCredentials(url)

    /** Persistence boundary: null means the malformed URL must be rejected, not guessed/sanitized. */
    fun sanitizeBaseUrl(url: String): String? = ApiUrl.sanitizeBaseUrl(url)
}
