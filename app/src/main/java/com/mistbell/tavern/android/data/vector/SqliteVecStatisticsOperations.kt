package com.mistbell.tavern.android.data.vector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SqliteVecStatisticsOperations(
    private val state: SqliteVecStoreState,
) : StatisticsOperations {
    override suspend fun count(): Int =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                countSqliteVecRowsLocked(state, db)
            }
        }

    override suspend fun indexBytes(): Long =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                state.openLocked()
                state.walAwareBytes()
            }
        }

    override suspend fun indexStats(): IndexStats =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                val db = state.openLocked()
                val bytes = state.walAwareBytes()
                IndexStats(
                    count = countSqliteVecRowsLocked(state, db),
                    bytes = bytes,
                    budgetBytes = state.indexBudgetBytes,
                    persistedBytes = bytes,
                )
            }
        }
}
