package com.mistbell.tavern.android.data.vector

internal val SQLITE_VEC_FILTERABLE_COLUMNS =
    setOf(
        "owner_id",
        "session_id",
        "message_id",
        "character_id",
        "content_type",
        "created_at",
        "embedding_fingerprint",
        "source_revision",
    )

internal data class SqliteFilterWhere(
    val clause: String,
    val args: List<Any?>,
)

private class SqliteFilterBuilder {
    val clause = StringBuilder()
    val args = mutableListOf<Any?>()
}

/**
 * 未知键或空集合返回 null，表示无匹配；遇到无效条件后不再读取后续过滤值。
 */
internal fun buildSqliteFilterWhere(filters: Map<String, Any>): SqliteFilterWhere? {
    if (filters.isEmpty()) return SqliteFilterWhere("", emptyList())
    val entries = filters.entries.iterator()
    var where: SqliteFilterBuilder? = SqliteFilterBuilder()
    while (where != null && entries.hasNext()) {
        val (key, value) = entries.next()
        where = where?.appendFilter(key, value)
    }
    return where?.let { SqliteFilterWhere(it.clause.toString(), it.args) }
}

private fun SqliteFilterBuilder?.appendFilter(
    key: String,
    value: Any,
): SqliteFilterBuilder? {
    if (this == null || key !in SQLITE_VEC_FILTERABLE_COLUMNS) return null
    return when (value) {
        is Iterable<*> -> appendIterableFilter(key, value)
        else ->
            apply {
                clause.append(" AND $key = ?")
                args += value.toString()
            }
    }
}

private fun SqliteFilterBuilder.appendIterableFilter(
    key: String,
    value: Iterable<*>,
): SqliteFilterBuilder? {
    val values = value.toList()
    if (values.isEmpty()) return null
    clause.append(" AND $key IN (")
    clause.append(values.joinToString(",") { "?" })
    clause.append(")")
    values.forEach { args += it?.toString() }
    return this
}
