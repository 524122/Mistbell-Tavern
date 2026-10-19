package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vector_sync_outbox",
    indices = [
        Index(value = ["source_type", "owner_id", "session_id", "source_id", "operation_id"]),
        Index(value = ["next_attempt_at", "operation_id"]),
    ],
)
data class VectorSyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "operation_id")
    val operationId: Long = 0,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "vector_message_id")
    val vectorMessageId: String,
    val operation: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: String,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SOURCE_SUMMARY = "summary"
        const val SOURCE_MESSAGE = "message"
        const val UPSERT = "UPSERT"
        const val DELETE = "DELETE"
    }
}
