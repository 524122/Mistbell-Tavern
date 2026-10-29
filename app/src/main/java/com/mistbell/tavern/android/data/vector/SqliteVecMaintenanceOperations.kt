package com.mistbell.tavern.android.data.vector

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SqliteVecMaintenanceOperations(
    private val state: SqliteVecStoreState,
) : MaintenanceOperations {
    override suspend fun indexedMessageIds(fingerprint: String): Set<String> =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                val ids = mutableSetOf<String>()
                state.knownTables.forEach { dim ->
                    db.rawQuery(
                        "SELECT message_id FROM ${sqliteVecTableName(dim)} WHERE embedding_fingerprint = ?",
                        arrayOf(fingerprint),
                    ).use { cursor ->
                        while (cursor.moveToNext()) cursor.getString(0)?.let { ids += it }
                    }
                }
                ids
            }
        }

    override suspend fun entriesSnapshot(): List<VectorStore.VectorEntry> =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                readSqliteVecEntriesLocked(state, db)
            }
        }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                // 清向量并推进删除代次，但不清闸门，挡住尚在途的写入。
                state.deletionGeneration++
                state.knownTables.forEach { dim ->
                    db.execSQL("DELETE FROM ${sqliteVecTableName(dim)}")
                }
                incrementalVacuumSqliteVecLocked(db)
            }
        }
    }

    /** SQLite 库文件永远是权威态，reload 是 no-op。 */
    override suspend fun reload() = Unit

    /** 事务提交即持久（WAL + synchronous=NORMAL），没有待写的合并窗口。 */
    override suspend fun flush(): Boolean = true

    override suspend fun importIndex(content: String): Boolean =
        withContext(Dispatchers.IO) {
            val entries =
                decodeVectorIndex(content) ?: run {
                    Log.w(SQLITE_VEC_LOG_TAG, "索引内容无法解析，保留本地索引")
                    return@withContext false
                }
            state.mutex.withLock {
                val db = state.openLocked()
                db.beginTransaction()
                try {
                    val failure =
                        runCatching {
                            state.knownTables.forEach { dim ->
                                db.execSQL("DELETE FROM ${sqliteVecTableName(dim)}")
                            }
                            entries.forEach { entry ->
                                // 已登记删除意图的 message_id 不再复活。
                                val messageId = entry.metadata["message_id"] as? String
                                if (messageId != null && messageId in state.deletedMessageIds) return@forEach
                                upsertSqliteVecRowLocked(state, db, entry.id, entry.vector, entry.metadata)
                            }
                            db.setTransactionSuccessful()
                        }.exceptionOrNull()
                    if (failure is Exception) {
                        Log.e(SQLITE_VEC_LOG_TAG, "索引导入失败，已回滚：${failure.message}", failure)
                        return@withLock false
                    }
                    if (failure != null) throw failure
                } finally {
                    db.endTransaction()
                }
                incrementalVacuumSqliteVecLocked(db)
                Log.d(SQLITE_VEC_LOG_TAG, "索引已整体导入（${entries.size} 条）")
                true
            }
        }
}
