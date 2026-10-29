package com.mistbell.tavern.android.data.vector

import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val INDEX_SHELL_BYTES = 16L
private const val PURGE_IN_CHUNK = 500

internal class SqliteVecContentOperations(
    private val state: SqliteVecStoreState,
) : ContentOperations {
    override suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                deleteSqliteVecIdentityLocked(state, db, metadata)
                upsertSqliteVecRowLocked(state, db, id, vector, metadata)
                evictSqliteVecForBudgetLocked(state, db)
            }
        }
    }

    override suspend fun beginMessageWrite(
        messageId: String,
        replacePrevious: Boolean,
    ): VectorStore.MessageWriteToken? =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                state.openLocked()
                if (!replacePrevious && messageId in state.deletedMessageIds) return@withLock null
                if (replacePrevious) state.deletedMessageIds.remove(messageId)
                VectorStore.MessageWriteToken(messageId, state.deletionGeneration, replacePrevious)
            }
        }

    override suspend fun addIfAllowed(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
        messageId: String,
        token: VectorStore.MessageWriteToken,
    ): Boolean =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                if (token.messageId != messageId || token.generation != state.deletionGeneration) {
                    return@withLock false
                }
                if (!token.allowDeleted && messageId in state.deletedMessageIds) return@withLock false
                val candidate = VectorStore.VectorEntry(id, vector, metadata)
                val oversized =
                    state.indexBudgetBytes > 0 &&
                        VectorEviction.serializedSizeOf(candidate) + INDEX_SHELL_BYTES > state.indexBudgetBytes
                if (oversized) {
                    Log.w(SQLITE_VEC_LOG_TAG, "Dropping vector $id because one entry exceeds the index budget")
                    return@withLock false
                }
                deleteSqliteVecIdentityLocked(state, db, metadata)
                upsertSqliteVecRowLocked(state, db, id, vector, metadata)
                evictSqliteVecForBudgetLocked(state, db)
                sqliteVecEntryExistsLocked(state, db, id)
            }
        }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                db.beginTransaction()
                try {
                    entries.forEach { entry ->
                        deleteSqliteVecIdentityLocked(state, db, entry.metadata)
                        upsertSqliteVecRowLocked(state, db, entry.id, entry.vector, entry.metadata)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                evictSqliteVecForBudgetLocked(state, db)
            }
        }
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                if (topK <= 0 || queryVector.size !in state.knownTables) return@withLock emptyList()
                val where = buildSqliteFilterWhere(filters) ?: return@withLock emptyList()
                val table = sqliteVecTableName(queryVector.size)
                // k 与 LIMIT 互斥；元数据过滤仍在 vec0 的 C 层下推。
                val sql =
                    "SELECT vector_id, distance, owner_id, session_id, message_id, character_id, " +
                        "content_type, content, created_at, embedding_fingerprint, source_revision " +
                        "FROM $table WHERE embedding MATCH ? AND k = ?${where.clause} ORDER BY distance"
                val args = arrayListOf<Any?>(queryVector.toSqliteVecLiteral(), topK)
                args.addAll(where.args)
                val results = mutableListOf<VectorStore.SearchResult>()
                db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        results += cursor.toSqliteVecSearchResult(queryVector.size)
                    }
                }
                results
            }
        }

    override suspend fun get(id: String): VectorStore.VectorEntry? =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                state.knownTables.forEach { dim ->
                    db.rawQuery(
                        "SELECT vector_id, vec_to_json(embedding), owner_id, session_id, message_id, character_id, " +
                            "content_type, content, created_at, embedding_fingerprint, source_revision " +
                            "FROM ${sqliteVecTableName(dim)} WHERE vector_id = ? LIMIT 1",
                        arrayOf(id),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) return@withLock cursor.toSqliteVecEntry(dim)
                    }
                }
                null
            }
        }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                state.knownTables.forEach { dim ->
                    db.execSQL("DELETE FROM ${sqliteVecTableName(dim)} WHERE vector_id=?", arrayOf(id))
                }
            }
        }
    }

    override suspend fun deleteByFilters(filters: Map<String, Any>): Int =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                val where = buildSqliteFilterWhere(filters.filterKeys { it != VectorStore.MARK_MESSAGES_DELETED })
                if (where == null && filters.keys.any { it != VectorStore.MARK_MESSAGES_DELETED }) {
                    return@withLock 0
                }
                val markDeleted = filters[VectorStore.MARK_MESSAGES_DELETED] == true
                var deleted = 0
                state.knownTables.forEach { dim ->
                    deleted += deleteSqliteVecTableByFiltersLocked(state, db, dim, where, markDeleted)
                }
                if (deleted > 0) incrementalVacuumSqliteVecLocked(db)
                deleted
            }
        }

    override suspend fun deleteWhereMetadataDiffers(
        key: String,
        value: String,
    ): Int =
        withContext(Dispatchers.IO) {
            require(key in SQLITE_VEC_FILTERABLE_COLUMNS) { "Unknown metadata column: $key" }
            state.mutex.withLock {
                val db = state.openLocked()
                var deleted = 0
                state.knownTables.forEach { dim ->
                    // 缺失该键的历史条目（空串）同样算不相符。
                    db.execSQL(
                        "DELETE FROM ${sqliteVecTableName(dim)} WHERE $key = '' OR $key != ?",
                        arrayOf(value),
                    )
                    deleted += sqliteVecChanges(db)
                }
                if (deleted > 0) incrementalVacuumSqliteVecLocked(db)
                deleted
            }
        }
}

private fun deleteSqliteVecTableByFiltersLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
    dim: Int,
    where: SqliteFilterWhere?,
    markDeleted: Boolean,
): Int {
    val table = sqliteVecTableName(dim)
    if (markDeleted) {
        markSqliteVecMessagesDeletedLocked(state, db, table, where)
    }
    db.execSQL(
        "DELETE FROM $table WHERE 1=1${where?.clause ?: ""}",
        (where?.args ?: emptyList()).toTypedArray(),
    )
    return sqliteVecChanges(db)
}

private fun markSqliteVecMessagesDeletedLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
    table: String,
    where: SqliteFilterWhere?,
) {
    val ids = mutableListOf<String>()
    db.rawQuery(
        "SELECT message_id FROM $table WHERE message_id != ''${where?.clause ?: ""}",
        (where?.args ?: emptyList()).toTypedArray(),
    ).use { cursor ->
        while (cursor.moveToNext()) cursor.getString(0)?.let { ids += it }
    }
    if (ids.isNotEmpty()) {
        state.deletionGeneration++
        state.deletedMessageIds.addAll(ids)
    }
}

/** 超预算时仍按 VectorEviction 的价值序淘汰，字节数以 db + WAL 的真实大小为准。 */
private fun evictSqliteVecForBudgetLocked(
    state: SqliteVecStoreState,
    db: SQLiteDatabase,
) {
    if (state.indexBudgetBytes <= 0) return
    val bytes = state.walAwareBytes()
    if (bytes <= state.indexBudgetBytes) return
    val entries = readSqliteVecEntriesLocked(state, db)
    val plan = VectorEviction.planForBudget(entries, bytes, state.indexBudgetBytes)
    if (plan.victims.isNotEmpty()) {
        plan.victims.chunked(PURGE_IN_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            state.knownTables.forEach { dim ->
                db.execSQL(
                    "DELETE FROM ${sqliteVecTableName(dim)} WHERE vector_id IN ($placeholders)",
                    chunk.toTypedArray(),
                )
            }
        }
        incrementalVacuumSqliteVecLocked(db)
        Log.w(
            SQLITE_VEC_LOG_TAG,
            "索引超出磁盘预算，淘汰 ${plan.victims.size} 条向量（summary 优先保留）",
        )
    }
}
