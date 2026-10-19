package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.vector.VectorStore

class VectorRecallValidator(
    private val db: AppDatabase,
) {
    suspend fun filterValid(results: List<VectorStore.SearchResult>): List<VectorStore.SearchResult> =
        results.filter { result -> isValid(result.metadata) }

    suspend fun isValid(metadata: Map<String, Any>): Boolean {
        val ownerId = metadata.string("owner_id")
        val sessionId = metadata.string("session_id")
        val messageId = metadata.string("message_id")
        val identityPresent = !ownerId.isNullOrBlank() && !sessionId.isNullOrBlank() && !messageId.isNullOrBlank()
        val session = if (identityPresent) db.sessionDao().getById(sessionId!!) else null
        val sessionValid = session != null && session.ownerId == ownerId
        return if (!identityPresent || !sessionValid) {
            false
        } else if (metadata["content_type"] == VectorStore.SUMMARY_CONTENT_TYPE) {
            validSummary(metadata, ownerId!!, sessionId!!, messageId!!)
        } else {
            validMessage(metadata, ownerId!!, sessionId!!, messageId!!)
        }
    }

    private suspend fun validSummary(
        metadata: Map<String, Any>,
        ownerId: String,
        sessionId: String,
        messageId: String,
    ): Boolean {
        val memoryId = messageId.removePrefix(STRUCTURED_MEMORY_PREFIX).toLongOrNull()
        val memory =
            memoryId
                ?.takeIf { messageId == VectorSyncSource.summaryMessageId(it) }
                ?.let { db.structuredMemoryDao().getById(it) }
        return memory?.let {
            listOf(
                it.ownerId == ownerId,
                it.sessionId == sessionId,
                it.importance >= VectorSyncSource.SUMMARY_IMPORTANCE_THRESHOLD,
                metadata.string("character_id") == (it.characterId ?: VectorStore.CHARACTER_LEVEL_CHARACTER),
                metadata.string("content") == it.toDomain().buildVectorContent(),
                revisionMatches(metadata, it.vectorRevision),
            ).all { valid -> valid }
        } ?: false
    }

    private suspend fun validMessage(
        metadata: Map<String, Any>,
        ownerId: String,
        sessionId: String,
        messageId: String,
    ): Boolean {
        val message = db.messageDao().getById(messageId, sessionId, ownerId)
        return message?.let {
            listOf(
                metadata.string("character_id") == it.characterId,
                metadata["content_type"] == VectorSyncSource.messageContentType(it.role),
                metadata.string("content") == it.content,
                revisionMatches(metadata, it.vectorRevision),
            ).all { valid -> valid }
        } ?: false
    }

    private fun revisionMatches(
        metadata: Map<String, Any>,
        currentRevision: String,
    ): Boolean {
        val indexedRevision = metadata.string("source_revision")
        return indexedRevision == null || indexedRevision == currentRevision
    }

    private fun Map<String, Any>.string(key: String): String? = this[key] as? String

    private companion object {
        const val STRUCTURED_MEMORY_PREFIX = "structured_memory_"
    }
}
