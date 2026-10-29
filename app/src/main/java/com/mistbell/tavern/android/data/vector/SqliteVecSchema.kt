package com.mistbell.tavern.android.data.vector

import io.requery.android.database.sqlite.SQLiteDatabase

internal fun ensureSqliteVecTableLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
    dimension: Int,
): String {
    if (dimension !in state.knownTables) {
        // content 永不过滤，放在 KNN 查询无法过滤的辅助列；其余元数据列可在 WHERE 中下推。
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS ${sqliteVecTableName(dimension)} USING vec0(" +
                "embedding float[$dimension] distance_metric=cosine, " +
                "vector_id TEXT PRIMARY KEY, " +
                "owner_id TEXT, session_id TEXT, message_id TEXT, character_id TEXT, " +
                "content_type TEXT, created_at TEXT, embedding_fingerprint TEXT, source_revision TEXT, " +
                "+content TEXT)",
        )
        state.knownTables += dimension
    }
    return sqliteVecTableName(dimension)
}

internal fun upsertSqliteVecRowLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
    id: String,
    vector: FloatArray,
    metadata: Map<String, Any>,
) {
    val table = ensureSqliteVecTableLocked(state, db, vector.size)
    // vec0 的 TEXT 元数据列拒绝 NULL：缺失键统一落为空串。
    db.execSQL(
        "INSERT OR REPLACE INTO $table " +
            "(vector_id, embedding, owner_id, session_id, message_id, character_id, content_type, " +
            "content, created_at, embedding_fingerprint, source_revision) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        arrayOf(
            id,
            vector.toSqliteVecLiteral(),
            metadata["owner_id"]?.toString() ?: "",
            metadata["session_id"]?.toString() ?: "",
            metadata["message_id"]?.toString() ?: "",
            metadata["character_id"]?.toString() ?: "",
            metadata["content_type"]?.toString() ?: "",
            metadata["content"]?.toString(),
            metadata["created_at"]?.toString() ?: "",
            metadata["embedding_fingerprint"]?.toString() ?: "",
            metadata["source_revision"]?.toString() ?: "",
        ),
    )
}

/** 同来源身份的旧副本全部删除；无身份三元组的条目不收敛，仅按 vector_id 覆盖。 */
internal fun deleteSqliteVecIdentityLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
    metadata: Map<String, Any>,
) {
    val owner = metadata["owner_id"] as? String
    val session = metadata["session_id"] as? String
    val message = metadata["message_id"] as? String
    if (owner != null && session != null && message != null) {
        state.knownTables.forEach { dim ->
            db.execSQL(
                "DELETE FROM ${sqliteVecTableName(dim)} WHERE owner_id=? AND session_id=? AND message_id=?",
                arrayOf(owner, session, message),
            )
        }
    }
}

/** 单条存在性核查：addIfAllowed 写入后可能已被淘汰，按实际存在性上报。 */
internal fun sqliteVecEntryExistsLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
    id: String,
): Boolean {
    state.knownTables.forEach { dim ->
        db.rawQuery(
            "SELECT 1 FROM ${sqliteVecTableName(dim)} WHERE vector_id=? LIMIT 1",
            arrayOf(id),
        ).use { cursor ->
            if (cursor.moveToFirst()) return true
        }
    }
    return false
}

internal fun readSqliteVecEntriesLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
): List<VectorStore.VectorEntry> {
    val entries = mutableListOf<VectorStore.VectorEntry>()
    state.knownTables.forEach { dim ->
        db.rawQuery(
            "SELECT vector_id, vec_to_json(embedding), owner_id, session_id, message_id, character_id, " +
                "content_type, content, created_at, embedding_fingerprint, source_revision " +
                "FROM ${sqliteVecTableName(dim)}",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) entries += cursor.toSqliteVecEntry(dim)
        }
    }
    return entries
}

internal fun countSqliteVecRowsLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
): Int {
    var total = 0
    state.knownTables.forEach { dim ->
        db.rawQuery("SELECT COUNT(*) FROM ${sqliteVecTableName(dim)}", null).use {
            it.moveToFirst()
            total += it.getInt(0)
        }
    }
    return total
}

internal fun sqliteVecChanges(db: SQLiteDatabase): Int =
    db.rawQuery("SELECT changes()", null).use {
        it.moveToFirst()
        it.getInt(0)
    }

internal fun incrementalVacuumSqliteVecLocked(db: SQLiteDatabase) {
    runCatching { db.rawQuery("PRAGMA incremental_vacuum", null).use { it.moveToFirst() } }
}
