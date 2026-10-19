package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity

@Suppress("TooManyFunctions")
@Dao
interface VectorSyncOutboxDao {
    @Insert
    suspend fun insert(operation: VectorSyncOutboxEntity): Long

    @Query("SELECT * FROM vector_sync_outbox WHERE operation_id = :operationId")
    suspend fun getById(operationId: Long): VectorSyncOutboxEntity?

    @Query(
        "SELECT * FROM vector_sync_outbox WHERE next_attempt_at <= :now " +
            "ORDER BY operation_id ASC LIMIT :limit",
    )
    suspend fun due(
        now: Long,
        limit: Int,
    ): List<VectorSyncOutboxEntity>

    @Query(
        "SELECT * FROM vector_sync_outbox WHERE source_type = :sourceType AND owner_id = :ownerId " +
            "AND session_id = :sessionId AND source_id = :sourceId " +
            "ORDER BY operation_id DESC LIMIT 1",
    )
    suspend fun latestForSource(
        sourceType: String,
        ownerId: String,
        sessionId: String,
        sourceId: String,
    ): VectorSyncOutboxEntity?

    @Query(
        "SELECT * FROM vector_sync_outbox WHERE source_type = :sourceType AND owner_id = :ownerId " +
            "AND session_id = :sessionId AND source_id = :sourceId " +
            "AND next_attempt_at <= :now ORDER BY operation_id ASC",
    )
    suspend fun dueForSource(
        sourceType: String,
        ownerId: String,
        sessionId: String,
        sourceId: String,
        now: Long,
    ): List<VectorSyncOutboxEntity>

    @Query(
        "SELECT * FROM vector_sync_outbox WHERE source_type = :sourceType AND owner_id = :ownerId " +
            "AND session_id = :sessionId AND source_id = :sourceId AND operation = :operation " +
            "AND source_revision = :sourceRevision ORDER BY operation_id DESC LIMIT 1",
    )
    @Suppress("LongParameterList")
    suspend fun findEquivalent(
        sourceType: String,
        ownerId: String,
        sessionId: String,
        sourceId: String,
        operation: String,
        sourceRevision: String,
    ): VectorSyncOutboxEntity?

    @Query(
        "UPDATE vector_sync_outbox SET attempt_count = attempt_count + 1, " +
            "next_attempt_at = :nextAttemptAt, last_error = :lastError WHERE operation_id = :operationId",
    )
    suspend fun markRetry(
        operationId: Long,
        nextAttemptAt: Long,
        lastError: String?,
    )

    @Query("DELETE FROM vector_sync_outbox WHERE operation_id = :operationId")
    suspend fun deleteById(operationId: Long): Int

    @Query(
        "DELETE FROM vector_sync_outbox WHERE source_type = :sourceType AND owner_id = :ownerId " +
            "AND session_id = :sessionId AND source_id = :sourceId AND operation_id <= :operationId",
    )
    suspend fun acknowledgeThrough(
        sourceType: String,
        ownerId: String,
        sessionId: String,
        sourceId: String,
        operationId: Long,
    ): Int

    @Query("DELETE FROM vector_sync_outbox")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM vector_sync_outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM vector_sync_outbox ORDER BY operation_id ASC")
    suspend fun getAll(): List<VectorSyncOutboxEntity>
}
