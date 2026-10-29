package com.mistbell.tavern.android.data.vector

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_IN_VARIABLES = 30000

internal class SqliteVecGateOperations(
    private val state: SqliteVecStoreState,
) : GateOperations {
    override suspend fun canIndexMessage(messageId: String): Boolean {
        return state.mutex.withLock { messageId !in state.deletedMessageIds }
    }

    override suspend fun markMessagesDeleted(messageIds: Collection<String>) {
        state.mutex.withLock {
            if (messageIds.isNotEmpty()) {
                state.deletionGeneration++
                state.deletedMessageIds.addAll(messageIds)
            }
        }
    }

    override suspend fun clearMessageDeleted(messageId: String) {
        state.mutex.withLock { state.deletedMessageIds.remove(messageId) }
    }

    override suspend fun purgeSessionsNotIn(knownSessionIds: Set<String>): Int =
        withContext(Dispatchers.IO) {
            if (knownSessionIds.isEmpty()) return@withContext 0
            if (knownSessionIds.size > MAX_IN_VARIABLES) {
                // 查不全时宁可残留，也不能把没查全当成已删除。
                Log.w(
                    SQLITE_VEC_LOG_TAG,
                    "会话数超出单次对账上限（${knownSessionIds.size}），跳过本轮孤儿会话清理",
                )
                return@withContext 0
            }
            state.mutex.withLock {
                val db = state.openLocked()
                var deleted = 0
                val purgedMessageIds = mutableListOf<String>()
                state.knownTables.forEach { dim ->
                    val table = sqliteVecTableName(dim)
                    // session_id 缺失（空串）的行无法证明归属，一律保留。
                    val placeholders = knownSessionIds.joinToString(",") { "?" }
                    val args = knownSessionIds.toTypedArray()
                    db.rawQuery(
                        "SELECT message_id FROM $table WHERE session_id != '' AND session_id NOT IN ($placeholders)",
                        args,
                    ).use { cursor ->
                        while (cursor.moveToNext()) cursor.getString(0)?.let { purgedMessageIds += it }
                    }
                    db.execSQL(
                        "DELETE FROM $table WHERE session_id != '' AND session_id NOT IN ($placeholders)",
                        args,
                    )
                    deleted += sqliteVecChanges(db)
                }
                if (deleted > 0) {
                    state.deletionGeneration++
                    purgedMessageIds.forEach { state.deletedMessageIds.remove(it) }
                    incrementalVacuumSqliteVecLocked(db)
                    Log.w(SQLITE_VEC_LOG_TAG, "启动对账：删除 $deleted 条所属会话已不存在的向量")
                }
                deleted
            }
        }
}
