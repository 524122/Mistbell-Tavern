package com.mistbell.tavern.android.data.api

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object ApiUrlSecurity {
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val QUERY_PAIR = Regex("""([?&])([^=&#\s"'<>]+)=([^&#\s"'<>]*)""")

    fun sanitizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        val uri = parseUri(trimmed)
        return when {
            trimmed.isBlank() -> trimmed
            uri == null -> null
            trimmed.contains("://") && uri.rawAuthority == null -> null
            uri.scheme != null && uri.rawAuthority == null -> null
            else -> {
                val rawQuery = uri.rawQuery
                val filteredQuery = rawQuery?.let(::stripQueryCredentials)
                when {
                    rawQuery != null && filteredQuery == null -> null
                    uri.rawUserInfo == null && filteredQuery == rawQuery -> trimmed
                    else ->
                        rebuild(
                            uri = uri,
                            rawQuery = filteredQuery?.ifBlank { null },
                            rawAuthority = uri.rawAuthority?.substringAfterLast('@'),
                        )
                }
            }
        }
    }

    fun redactCredentials(raw: String): String =
        if (raw.isBlank()) {
            raw
        } else {
            val direct = redactSingleUrl(raw)
            if (direct != raw) {
                direct
            } else {
                val embedded = URL_IN_TEXT.replace(raw) { match -> redactSingleUrl(match.value) }
                redactQueryParametersInText(embedded)
            }
        }

    private fun redactSingleUrl(raw: String): String = parseUri(raw)?.let { uri -> redactParsedUrl(raw, uri) } ?: raw

    private fun redactParsedUrl(
        raw: String,
        uri: URI,
    ): String {
        val rawQuery = uri.rawQuery
        val redactedQuery = rawQuery?.let(::redactQueryCredentials)
        return when {
            uri.scheme != null && uri.rawAuthority == null -> raw
            uri.rawUserInfo == null && redactedQuery == rawQuery -> raw
            else ->
                rebuild(
                    uri = uri,
                    rawQuery = redactedQuery?.ifBlank { null },
                    rawAuthority = redactAuthority(uri.rawAuthority),
                )
        }
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

private fun redactAuthority(rawAuthority: String?): String? =
    rawAuthority?.let { authority ->
        if ('@' in authority) {
            "${ApiSecrets.REDACTED}@${authority.substringAfterLast('@')}"
        } else {
            authority
        }
    }
