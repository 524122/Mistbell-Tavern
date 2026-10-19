package com.mistbell.tavern.android.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 敏感字段名 / URL 凭据的统一判定。
 *
 * 规范化规则：去掉所有非字母数字字符并转小写，因此 `api_key`、`API-KEY`、`x-api-key`
 * 都归一到 `apikey` / `xapikey`。字段名先与预算类白名单比对，避免把
 * `max_tokens`、`maxOutputTokens`、`thinkingBudgetTokens` 误判成凭据。
 */
object ApiSecrets {
    const val REDACTED = "•••"

    /** 完整匹配的敏感名（已规范化）。 */
    private val SENSITIVE_NAMES =
        setOf(
            "apikey",
            "xapikey",
            "authorization",
            "proxyauthorization",
            "auth",
            "accesstoken",
            "authtoken",
            "idtoken",
            "refreshtoken",
            "sessiontoken",
            "bearertoken",
            "token",
            "secret",
            "clientsecret",
            "secretkey",
            "password",
            "passwd",
            "credential",
            "credentials",
            "privatekey",
            "subscriptionkey",
            "cookie",
            "setcookie",
            "signature",
            "sig",
        )

    /** 供应商前缀形式，例如 `openai_api_key`、`x-goog-api-key`、`github_token`。 */
    private val SENSITIVE_SUFFIXES =
        listOf(
            "apikey",
            "authorization",
            "accesstoken",
            "authtoken",
            "refreshtoken",
            "sessiontoken",
            // 供应商前缀 + token/secret（openai_token、github_secret 等）；
            // 预算类 maxtoken(s)/maxOutputTokens/thinkingBudgetTokens 已由白名单放行。
            "token",
            "secret",
            "clientsecret",
            "secretkey",
            "password",
            "passwd",
            "credential",
            "privatekey",
        )

    /**
     * 合法采样/用量字段：即使名字里带 token 也不是凭据。
     * 必须覆盖 max_tokens、maxOutputTokens、thinkingBudgetTokens 等常见字段。
     */
    private val NON_SECRET_BUDGET_FIELDS =
        setOf(
            "tokens",
            "maxtoken",
            "maxtokens",
            "maxoutputtoken",
            "maxoutputtokens",
            "maxcompletiontoken",
            "maxcompletiontokens",
            "maxcontexttoken",
            "maxcontexttokens",
            "budgettoken",
            "budgettokens",
            "thinkingbudget",
            "thinkingbudgettokens",
            "contexttoken",
            "contexttokens",
            "prompttoken",
            "prompttokens",
            "completiontoken",
            "completiontokens",
            "inputtoken",
            "inputtokens",
            "outputtoken",
            "outputtokens",
            "totaltoken",
            "totaltokens",
            "cachedtoken",
            "cachedtokens",
            "cachehittoken",
            "cachehittokens",
            "cachemisstoken",
            "cachemisstokens",
            "reasoningtoken",
            "reasoningtokens",
            "candidatetoken",
            "candidatetokens",
        )

    /** 去掉大小写/下划线/连字符差异，便于共享判定。 */
    fun normalizeFieldName(raw: String): String =
        raw
            .filter { it.isLetterOrDigit() }
            .lowercase(Locale.ROOT)

    fun isSensitiveFieldName(raw: String): Boolean {
        val normalized = normalizeFieldName(raw)
        if (normalized.isBlank()) return false
        if (normalized in NON_SECRET_BUDGET_FIELDS) return false
        if (normalized in SENSITIVE_NAMES) return true
        return SENSITIVE_SUFFIXES.any { normalized.endsWith(it) }
    }

    /** Gemini 的 `key` 只在 URL query 里代表凭据，不能据此把请求体的通用 `key` 一并封杀。 */
    fun isSensitiveQueryParameter(raw: String): Boolean {
        val normalized = normalizeFieldName(raw)
        return normalized == "key" || isSensitiveFieldName(raw)
    }
}

/**
 * 结构化 URL 操作：路径拼在 query 之前，query 凭据按参数名移除/脱敏，
 * 非凭据参数及其顺序、分隔符保持不变。
 */
object ApiUrl {
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** 任意文本中的 query 参数对：用 ApiSecrets 统一判断，避免 fallback 名单比主判定窄。 */
    private val QUERY_PAIR = Regex("""([?&])([^=&#\s"'<>]+)=([^&#\s"'<>]*)""")

    /** 在已有 query 之前追加路径段；已存在的 query 不会被路径覆盖。 */
    fun appendPath(
        baseUrl: String,
        path: String,
    ): String {
        val base = baseUrl.trim()
        val suffix = path.trim().trimStart('/')
        if (base.isBlank()) return suffix
        if (suffix.isBlank()) return base

        val httpUrl = base.toHttpUrlOrNull()
        if (httpUrl != null) {
            val builder = httpUrl.newBuilder()
            suffix.split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
            return builder.build().toString()
        }

        // 非标准 baseUrl（例如本地测试用 "u"）保持可用的字符串兜底，但路径仍需插在 query 前。
        val queryIndex = base.indexOf('?')
        val pathPart = if (queryIndex >= 0) base.substring(0, queryIndex) else base
        val queryPart = if (queryIndex >= 0) base.substring(queryIndex) else ""
        return "${pathPart.trimEnd('/')}/$suffix$queryPart"
    }

    /** 判断 URL 路径（忽略 query）是否以指定路径段结尾。 */
    fun hasPathSuffix(
        baseUrl: String,
        suffix: String,
    ): Boolean {
        val path =
            baseUrl.trim().toHttpUrlOrNull()?.encodedPath
                ?: baseUrl.trim().substringBefore('?').substringBefore('#')
        return path.trimEnd('/').endsWith("/${suffix.trim('/')}")
    }

    /**
     * 合并 query 参数：先移除已有凭据参数（避免 URL 里的旧 key 与 SecureStore 的新 key 并存），
     * 再按顺序追加调用方参数。路径已在 [appendPath] 中完成。
     */
    fun withQueryParameters(
        url: String,
        parameters: Map<String, String>,
        replacedParameters: Set<String> = emptySet(),
    ): String {
        val httpUrl = url.trim().toHttpUrlOrNull()
        if (httpUrl == null) {
            val separator = if ('?' in url) "&" else "?"
            return url + parameters.entries.joinToString("&", prefix = separator) { (key, value) -> "$key=$value" }
        }
        val replaced = replacedParameters.map { it.lowercase(Locale.ROOT) }.toSet()
        val builder = httpUrl.newBuilder()
        httpUrl.queryParameterNames
            .filter {
                ApiSecrets.isSensitiveQueryParameter(it) || it.lowercase(Locale.ROOT) in replaced
            }.forEach(builder::removeAllQueryParameters)
        parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    /**
     * 持久化前清理 URL：移除 userinfo 与 query 凭据。
     * 返回 null 表示地址畸形、无法在不猜测的前提下安全清理，调用方必须拒绝保存。
     */
    fun sanitizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        val uri = parseUri(trimmed) ?: return null
        if (trimmed.contains("://") && uri.rawAuthority == null) return null
        // 形如 localhost:8080/v1 会被 URI 当成 opaque scheme，无法安全重建：明确拒绝，不猜。
        if (uri.scheme != null && uri.rawAuthority == null) return null

        val rawQuery = uri.rawQuery
        val filteredQuery = rawQuery?.let { stripQueryCredentials(it) ?: return null }
        val hadUserInfo = uri.rawUserInfo != null
        if (!hadUserInfo && filteredQuery == rawQuery) return trimmed

        val authority = uri.rawAuthority?.substringAfterLast('@')
        return rebuild(
            uri = uri,
            rawQuery = filteredQuery?.ifBlank { null },
            rawAuthority = authority,
        )
    }

    /** UI/日志用的 URL 脱敏：替换 userinfo 和 query 凭据的值，保留其它参数与分隔符。 */
    fun redactCredentials(raw: String): String {
        if (raw.isBlank()) return raw

        val direct = redactSingleUrl(raw)
        if (direct != raw) return direct

        val embedded = URL_IN_TEXT.replace(raw) { match -> redactSingleUrl(match.value) }
        // 兜底处理没有 scheme 的相对错误消息；逐参数使用 ApiSecrets 判定，保留分隔符。
        return redactQueryParametersInText(embedded)
    }

    /**
     * 兼容展示/编辑路径：可安全清理时返回清理结果；畸形地址返回空串（后续 validate/保存入口拒绝），
     * 绝不把可能含 userinfo/query 凭据的原文继续传下去。持久化应优先使用 [sanitizeBaseUrl] 并对 null 报错。
     */
    fun stripCredentials(raw: String): String = sanitizeBaseUrl(raw).orEmpty()

    private fun redactSingleUrl(raw: String): String {
        val uri = parseUri(raw) ?: return raw
        // opaque URL（如 localhost:8080/v1）不重建；交给文本级逐参数 fallback，避免丢路径。
        if (uri.scheme != null && uri.rawAuthority == null) return raw
        val rawQuery = uri.rawQuery
        val hadUserInfo = uri.rawUserInfo != null
        val redactedQuery = rawQuery?.let(::redactQueryCredentials)
        if (!hadUserInfo && redactedQuery == rawQuery) return raw

        val authority =
            uri.rawAuthority?.let { rawAuthority ->
                if ('@' in rawAuthority) {
                    "${ApiSecrets.REDACTED}@${rawAuthority.substringAfterLast('@')}"
                } else {
                    rawAuthority
                }
            }
        return rebuild(
            uri = uri,
            rawQuery = redactedQuery?.ifBlank { null },
            rawAuthority = authority,
        )
    }

    private fun stripQueryCredentials(rawQuery: String): String? {
        val kept = mutableListOf<String>()
        rawQuery.split('&').forEach { part ->
            val decodedName = decodeQueryName(part.substringBefore('=')) ?: return null
            if (!ApiSecrets.isSensitiveQueryParameter(decodedName)) kept += part
        }
        return kept.joinToString("&")
    }

    private fun redactQueryCredentials(rawQuery: String): String =
        rawQuery
            .split('&')
            .joinToString("&") { part ->
                val rawName = part.substringBefore('=')
                val decodedName = decodeQueryName(rawName) ?: rawName
                if (ApiSecrets.isSensitiveQueryParameter(decodedName)) {
                    "$rawName=${ApiSecrets.REDACTED}"
                } else {
                    part
                }
            }

    private fun redactQueryParametersInText(text: String): String =
        QUERY_PAIR.replace(text) { match ->
            val separator = match.groupValues[1]
            val rawName = match.groupValues[2]
            val decodedName = decodeQueryName(rawName) ?: rawName
            if (ApiSecrets.isSensitiveQueryParameter(decodedName)) {
                "$separator$rawName=${ApiSecrets.REDACTED}"
            } else {
                match.value
            }
        }

    private fun decodeQueryName(rawName: String): String? =
        runCatching {
            URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
        }.getOrNull()

    private fun parseUri(raw: String): URI? = runCatching { URI(raw) }.getOrNull()

    /** 用原始（未解码）组件重建 URL，避免把 `%2F`、已编码空格等二次编码。 */
    private fun rebuild(
        uri: URI,
        rawQuery: String?,
        rawAuthority: String? = uri.rawAuthority,
    ): String {
        val prefix =
            buildString {
                uri.scheme?.let { append(it).append(':') }
                rawAuthority?.let { append("//").append(it) }
                uri.rawPath?.let { append(it) }
            }
        val query = rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return prefix + query + fragment
    }
}
