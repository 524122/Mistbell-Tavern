package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity
import com.mistbell.tavern.android.data.vector.VectorStore

object VectorSyncSource {
    const val SUMMARY_IMPORTANCE_THRESHOLD = 7

    fun summaryMessageId(memoryId: Long): String = "structured_memory_$memoryId"

    fun messageContentType(role: String): String = if (role == "user") "user_message" else "ai_message"

    fun summaryOperation(memory: StructuredMemoryEntity): String =
        if (memory.importance >= SUMMARY_IMPORTANCE_THRESHOLD) {
            VectorSyncOutboxEntity.UPSERT
        } else {
            VectorSyncOutboxEntity.DELETE
        }

    fun summaryOperation(
        memory: StructuredMemoryEntity,
        operation: String,
    ): VectorSyncOutboxEntity =
        VectorSyncOutboxEntity(
            sourceType = VectorSyncOutboxEntity.SOURCE_SUMMARY,
            ownerId = memory.ownerId,
            sessionId = memory.sessionId.orEmpty(),
            sourceId = memory.id.toString(),
            vectorMessageId = summaryMessageId(memory.id),
            operation = operation,
            sourceRevision = memory.vectorRevision,
            contentType = VectorStore.SUMMARY_CONTENT_TYPE,
        )

    fun messageOperation(
        message: MessageEntity,
        operation: String,
    ): VectorSyncOutboxEntity =
        VectorSyncOutboxEntity(
            sourceType = VectorSyncOutboxEntity.SOURCE_MESSAGE,
            ownerId = message.ownerId,
            sessionId = message.sessionId,
            sourceId = message.id,
            vectorMessageId = message.id,
            operation = operation,
            sourceRevision = message.vectorRevision,
            contentType = messageContentType(message.role),
        )
}

class VectorSyncOutboxScheduler(
    private val db: AppDatabase,
) {
    private val dao = db.vectorSyncOutboxDao()

    suspend fun enqueueSummary(
        memory: StructuredMemoryEntity,
        operation: String = VectorSyncSource.summaryOperation(memory),
    ): Long = enqueue(VectorSyncSource.summaryOperation(memory, operation))

    suspend fun enqueueMessage(
        message: MessageEntity,
        operation: String = VectorSyncOutboxEntity.UPSERT,
    ): Long = enqueue(VectorSyncSource.messageOperation(message, operation))

    suspend fun enqueue(operation: VectorSyncOutboxEntity): Long {
        require(operation.sourceRevision.isNotBlank() || operation.operation == VectorSyncOutboxEntity.DELETE) {
            "同步待办必须携带源修订号"
        }
        dao.findEquivalent(
            operation.sourceType,
            operation.ownerId,
            operation.sessionId,
            operation.sourceId,
            operation.operation,
            operation.sourceRevision,
        )?.let { return it.operationId }
        return dao.insert(operation)
    }
}
