package com.mistbell.tavern.android.data.repository

import androidx.room.withTransaction
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.vector.VectorMemoryService

fun interface RecentStructuredMemorySource {
    suspend fun recent(
        ownerId: String,
        limit: Int,
    ): List<StructuredMemoryEntity>
}

class PersistentVectorIndexRebuilder(
    private val db: AppDatabase,
    private val vectorMemoryService: VectorMemoryService,
    private val messageSource: RecentMessageSource,
    private val memorySource: RecentStructuredMemorySource,
    private val recovery: VectorSyncRecovery,
) {
    private val scheduler = VectorSyncOutboxScheduler(db)

    suspend fun rebuild(
        ownerId: String,
        limit: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): VectorIndexRebuilder.Result {
        val sessions = db.sessionDao().getAllOnce().associateBy { it.id }
        val messages =
            messageSource.recent(ownerId, limit).filter {
                sessions[it.sessionId]?.ownerId == it.ownerId
            }
        val memories =
            memorySource.recent(ownerId, limit).filter {
                sessions[it.sessionId]?.ownerId == it.ownerId
            }
        val scheduled = schedule(messages, memories, indexedStates())
        val operationIds = scheduled.operationIds
        var skipped = scheduled.skipped

        var embedded = 0
        var failed = 0
        var declined = 0
        operationIds.forEachIndexed { index, operationId ->
            when (recovery.processNow(operationId)) {
                VectorSyncRecovery.Outcome.COMPLETED -> embedded++
                VectorSyncRecovery.Outcome.CAPACITY_SKIPPED -> declined++
                VectorSyncRecovery.Outcome.RETRY_SCHEDULED -> failed++
                VectorSyncRecovery.Outcome.SKIPPED_STALE,
                VectorSyncRecovery.Outcome.ALREADY_RUNNING,
                -> skipped++
            }
            onProgress(index + 1, operationIds.size)
        }
        return VectorIndexRebuilder.Result(
            embedded = embedded,
            skipped = skipped,
            failed = failed,
            total = messages.size + memories.size,
            declined = declined,
        )
    }

    private suspend fun indexedStates(): Map<String, IndexedState> {
        val fingerprint = vectorMemoryService.embeddingFingerprint()
        return vectorMemoryService.indexStore().entriesSnapshot()
            .filter { it.metadata["embedding_fingerprint"] == fingerprint }
            .mapNotNull { entry ->
                val messageId = entry.metadata["message_id"] as? String ?: return@mapNotNull null
                messageId to
                    IndexedState(
                        entry.metadata["source_revision"] as? String,
                        entry.metadata["content"] as? String,
                    )
            }.toMap()
    }

    private suspend fun schedule(
        messages: List<MessageEntity>,
        memories: List<StructuredMemoryEntity>,
        indexed: Map<String, IndexedState>,
    ): ScheduleResult {
        val operationIds = mutableListOf<Long>()
        var skipped = 0
        db.withTransaction {
            messages.forEach { message ->
                db.messageDao().ensureVectorRevision(message.id)
                val current = requireNotNull(db.messageDao().getAnyById(message.id))
                val existing = indexed[message.id]
                if (existing?.revision == current.vectorRevision && existing.content == current.content) {
                    skipped++
                } else {
                    operationIds += scheduler.enqueueMessage(current)
                }
            }
            memories.forEach { memory ->
                db.structuredMemoryDao().ensureVectorRevision(memory.id)
                val current = requireNotNull(db.structuredMemoryDao().getById(memory.id))
                val existing = indexed[VectorSyncSource.summaryMessageId(memory.id)]
                val content = current.toDomain().buildVectorContent()
                if (existing?.revision == current.vectorRevision && existing.content == content) {
                    skipped++
                } else {
                    operationIds += scheduler.enqueueSummary(current)
                }
            }
        }
        return ScheduleResult(operationIds, skipped)
    }

    private data class ScheduleResult(
        val operationIds: List<Long>,
        val skipped: Int,
    )

    private data class IndexedState(
        val revision: String?,
        val content: String?,
    )
}
