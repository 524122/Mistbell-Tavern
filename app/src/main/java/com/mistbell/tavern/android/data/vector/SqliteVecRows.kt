package com.mistbell.tavern.android.data.vector

import android.database.Cursor

private const val OWNER_ID_COLUMN_OFFSET = 0
private const val SESSION_ID_COLUMN_OFFSET = 1
private const val MESSAGE_ID_COLUMN_OFFSET = 2
private const val CHARACTER_ID_COLUMN_OFFSET = 3
private const val CONTENT_TYPE_COLUMN_OFFSET = 4
private const val CONTENT_COLUMN_OFFSET = 5
private const val CREATED_AT_COLUMN_OFFSET = 6
private const val FINGERPRINT_COLUMN_OFFSET = 7
private const val SOURCE_REVISION_COLUMN_OFFSET = 8
private const val ESTIMATED_VEC0_FLOAT_CHARACTERS = 12
private const val VEC0_LITERAL_AFFIX_CHARACTERS = 2
private const val VEC0_LITERAL_MIN_LENGTH = 2

internal fun Cursor.toSqliteVecSearchResult(dimension: Int): VectorStore.SearchResult {
    val metadata = toSqliteVecMetadata(dimension, contentStartIndex = 2)
    return VectorStore.SearchResult(
        id = getString(0),
        // vec0 cosine distance → similarity
        score = 1f - getFloat(1),
        content = metadata["content"] as? String ?: "",
        metadata = metadata,
    )
}

/** vec0 内部以 float32 BLOB 存储向量，维护路径经 vec_to_json 取出后再解析。 */
internal fun Cursor.toSqliteVecEntry(dimension: Int): VectorStore.VectorEntry =
    VectorStore.VectorEntry(
        id = getString(0),
        vector = getString(1).parseSqliteVecLiteral(),
        metadata = toSqliteVecMetadata(dimension, contentStartIndex = 2),
    )

/** 游标列序固定；dimension 由表名补回，与写入侧的元数据形状一致。 */
private fun Cursor.toSqliteVecMetadata(
    dimension: Int,
    contentStartIndex: Int,
): Map<String, Any> =
    buildMap {
        put("owner_id", getString(contentStartIndex + OWNER_ID_COLUMN_OFFSET) ?: "")
        put("session_id", getString(contentStartIndex + SESSION_ID_COLUMN_OFFSET) ?: "")
        put("message_id", getString(contentStartIndex + MESSAGE_ID_COLUMN_OFFSET) ?: "")
        put("character_id", getString(contentStartIndex + CHARACTER_ID_COLUMN_OFFSET) ?: "")
        put("content_type", getString(contentStartIndex + CONTENT_TYPE_COLUMN_OFFSET) ?: "")
        put("content", getString(contentStartIndex + CONTENT_COLUMN_OFFSET) ?: "")
        put("created_at", getString(contentStartIndex + CREATED_AT_COLUMN_OFFSET) ?: "")
        put("embedding_fingerprint", getString(contentStartIndex + FINGERPRINT_COLUMN_OFFSET) ?: "")
        // 缺键落成空串；读回时把空串还原为键缺失，避免被误判为版本漂移。
        getString(contentStartIndex + SOURCE_REVISION_COLUMN_OFFSET)
            ?.takeIf { it.isNotEmpty() }
            ?.let { put("source_revision", it) }
        put("dimension", dimension.toString())
    }

/** FloatArray → vec0 的 JSON 文本字面量形态 `[0.1,0.2,...]`。 */
internal fun FloatArray.toSqliteVecLiteral(): String =
    buildString(size * ESTIMATED_VEC0_FLOAT_CHARACTERS + VEC0_LITERAL_AFFIX_CHARACTERS) {
        append('[')
        this@toSqliteVecLiteral.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append(f)
        }
        append(']')
    }

/** vec0 返回的 embedding 文本形态解析回 FloatArray。 */
internal fun String.parseSqliteVecLiteral(): FloatArray {
    val trimmed = trim()
    val body =
        trimmed
            .takeIf { it.length >= VEC0_LITERAL_MIN_LENGTH }
            ?.substring(1, trimmed.lastIndex)
            .orEmpty()
    val parts = if (body.isBlank()) emptyList() else body.split(',')
    return FloatArray(parts.size) { parts[it].trim().toFloat() }
}
