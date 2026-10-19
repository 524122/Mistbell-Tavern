package com.mistbell.tavern.android.data.repository

import androidx.room.withTransaction
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore

class VectorIndexRecoveryReconciler(
    private val db: AppDatabase,
    private val vectorMemoryService: VectorMemoryService,
) {
    private val scheduler = VectorSyncOutboxScheduler(db)
    private val validator = VectorRecallValidator(db)

    suspend fun reconcileOnce() {
        if (db.settingsDao().getValue(RECONCILE_MARKER) == MARKER_DONE) return
        val entries = vectorMemoryService.indexStore().entriesSnapshot()
        val memories = db.structuredMemoryDao().getAllOnce()
        val ownersBySession = db.sessionDao().getAllOnce().associate { it.id to it.ownerId }
        val entryValidity = evaluateEntries(entries)
        val validMessageIds =
            entryValidity.mapNotNull { item ->
                if (item.valid) item.entry.metadata["message_id"] as? String else null
            }.toSet()
        val pending = LinkedHashMap<String, VectorSyncOutboxEntity>()
        planObsoleteEntries(entryValidity, validMessageIds, pending)
        planMemoryOperations(memories, entries, validMessageIds, ownersBySession, pending)

        db.withTransaction {
            if (db.settingsDao().getValue(RECONCILE_MARKER) == MARKER_DONE) return@withTransaction
            pending.values.forEach { scheduler.enqueue(it) }
            db.settingsDao().upsert(SettingsEntity(RECONCILE_MARKER, MARKER_DONE))
        }
    }

    private suspend fun evaluateEntries(entries: List<VectorStore.VectorEntry>): List<EntryValidity> {
        val fingerprint = vectorMemoryService.embeddingFingerprint()
        return entries.map { entry ->
            val valid =
                entry.metadata["embedding_fingerprint"] == fingerprint &&
                    entry.metadata["message_id"] is String && validator.isValid(entry.metadata)
            EntryValidity(entry, valid)
        }
    }

    private fun planObsoleteEntries(
        entryValidity: List<EntryValidity>,
        validMessageIds: Set<String>,
        pending: LinkedHashMap<String, VectorSyncOutboxEntity>,
    ) {
        entryValidity.forEach { item ->
            val messageId = item.entry.metadata["message_id"] as? String ?: return@forEach
            if (!item.valid && messageId !in validMessageIds) {
                pending.put(obsoleteOperation(item.entry.metadata, messageId))
            }
        }
    }

    private fun planMemoryOperations(
        memories: List<StructuredMemoryEntity>,
        entries: List<VectorStore.VectorEntry>,
        validMessageIds: Set<String>,
        ownersBySession: Map<String, String>,
        pending: LinkedHashMap<String, VectorSyncOutboxEntity>,
    ) {
        memories.forEach { memory ->
            val messageId = VectorSyncSource.summaryMessageId(memory.id)
            val attachable = ownersBySession[memory.sessionId] == memory.ownerId
            val important = memory.importance >= VectorSyncSource.SUMMARY_IMPORTANCE_THRESHOLD
            val hasIndexedCopy = entries.any { it.metadata["message_id"] == messageId }
            val operation =
                when {
                    important && attachable && messageId !in validMessageIds ->
                        VectorSyncSource.summaryOperation(memory, VectorSyncOutboxEntity.UPSERT)
                    !important && hasIndexedCopy ->
                        VectorSyncSource.summaryOperation(memory, VectorSyncOutboxEntity.DELETE)
                    else -> null
                }
            operation?.let { pending.put(it) }
        }
    }

    private fun LinkedHashMap<String, VectorSyncOutboxEntity>.put(operation: VectorSyncOutboxEntity) {
        this[operation.dedupeKey()] = operation
    }

    private data class EntryValidity(
        val entry: VectorStore.VectorEntry,
        val valid: Boolean,
    )

    private fun obsoleteOperation(
        metadata: Map<String, Any>,
        messageId: String,
    ): VectorSyncOutboxEntity {
        val summary = metadata["content_type"] == VectorStore.SUMMARY_CONTENT_TYPE
        val memoryId = messageId.removePrefix(STRUCTURED_MEMORY_PREFIX).toLongOrNull()
        val sourceId = if (summary && memoryId != null) memoryId.toString() else messageId
        return VectorSyncOutboxEntity(
            sourceType =
                if (summary) VectorSyncOutboxEntity.SOURCE_SUMMARY else VectorSyncOutboxEntity.SOURCE_MESSAGE,
            ownerId = metadata["owner_id"] as? String ?: "",
            sessionId = metadata["session_id"] as? String ?: "",
            sourceId = sourceId,
            vectorMessageId = messageId,
            operation = VectorSyncOutboxEntity.DELETE,
            sourceRevision = metadata["source_revision"] as? String ?: "",
            contentType = metadata["content_type"] as? String ?: "",
        )
    }

    private fun VectorSyncOutboxEntity.dedupeKey(): String =
        listOf(sourceType, ownerId, sessionId, sourceId, operation, sourceRevision).joinToString("\u0000")

    companion object {
        internal const val RECONCILE_MARKER = "vector_source_reconcile_v1_done"
        private const val MARKER_DONE = "1"
        private const val STRUCTURED_MEMORY_PREFIX = "structured_memory_"
    }
}
