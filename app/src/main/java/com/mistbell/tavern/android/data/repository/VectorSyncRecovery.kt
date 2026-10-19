package com.mistbell.tavern.android.data.repository

import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.data.vector.deleteByMessageIdPersisted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 非取消异常都必须回到持久化重试状态，不能把失败伪装成同步完成。 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class VectorSyncRecovery(
    private val db: AppDatabase,
    private val vectorMemoryService: VectorMemoryService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val afterFlushBeforeAck: suspend (Long) -> Unit = {},
) {
    enum class Outcome {
        COMPLETED,
        RETRY_SCHEDULED,
        SKIPPED_STALE,
        CAPACITY_SKIPPED,
        ALREADY_RUNNING,
    }

    private val outboxDao = db.vectorSyncOutboxDao()

    suspend fun processNow(operationId: Long): Outcome {
        val claim = Claim(db, operationId)
        if (!claim(claim)) return Outcome.ALREADY_RUNNING
        return try {
            val operation = outboxDao.getById(operationId)
            if (operation == null) Outcome.COMPLETED else runSafely(operation)
        } finally {
            release(claim)
        }
    }

    suspend fun recoverDue(limit: Int = DEFAULT_BATCH_SIZE): Outcome {
        var processed = false
        var shouldContinue: Boolean
        do {
            val due = outboxDao.due(clock(), limit)
            var claimedAny = false
            for (operation in due) {
                val claimed = processIfClaimed(operation)
                claimedAny = claimedAny || claimed
                processed = processed || claimed
            }
            shouldContinue = due.isNotEmpty() && claimedAny && due.size == limit
        } while (shouldContinue)
        return if (processed) Outcome.COMPLETED else Outcome.SKIPPED_STALE
    }

    private suspend fun processIfClaimed(operation: VectorSyncOutboxEntity): Boolean {
        val claim = Claim(db, operation.operationId)
        if (!claim(claim)) return false
        return try {
            runSafely(operation)
            true
        } finally {
            release(claim)
        }
    }

    suspend fun recoverSource(
        sourceType: String,
        ownerId: String,
        sessionId: String,
        sourceId: String,
    ) {
        val due = outboxDao.dueForSource(sourceType, ownerId, sessionId, sourceId, clock())
        due.forEach { processNow(it.operationId) }
    }

    private suspend fun runSafely(operation: VectorSyncOutboxEntity): Outcome =
        try {
            when (operation.operation) {
                VectorSyncOutboxEntity.UPSERT -> processUpsert(operation)
                VectorSyncOutboxEntity.DELETE -> processDelete(operation)
                else -> {
                    Log.w(TAG, "丢弃未知向量恢复操作 ${operation.operation}")
                    outboxDao.deleteById(operation.operationId)
                    Outcome.SKIPPED_STALE
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "向量恢复操作 ${operation.operationId} 失败：${e.message}", e)
            scheduleRetry(operation, e)
            Outcome.RETRY_SCHEDULED
        }

    private suspend fun processUpsert(operation: VectorSyncOutboxEntity): Outcome {
        val source = readSource(operation)
        return when {
            !source.isEligibleCurrent(operation) -> removeObsoleteCopy(operation)
            !vectorMemoryService.available -> invalidateAndRetry(operation, "embedding unavailable")
            indexAtCapacity() -> skipForCapacity(operation)
            else -> {
                val embedding = embeddingOrNull(operation, source?.content.orEmpty())
                if (embedding == null) {
                    invalidateAndRetry(operation, "embedding failure")
                } else {
                    commitUpsert(operation, embedding)
                }
            }
        }
    }

    private suspend fun embeddingOrNull(
        operation: VectorSyncOutboxEntity,
        content: String,
    ): FloatArray? =
        try {
            vectorMemoryService.prepareEmbedding(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "向量恢复 embedding 失败（操作 ${operation.operationId}）：${e.message}")
            null
        }

    private suspend fun skipForCapacity(operation: VectorSyncOutboxEntity): Outcome =
        SessionMutationLocks.withSessionLock(operation.sessionId) {
            val source = readSource(operation)
            if (isLatest(operation) && source.isEligibleCurrent(operation)) {
                acknowledge(operation)
                Outcome.CAPACITY_SKIPPED
            } else {
                outboxDao.deleteById(operation.operationId)
                Outcome.SKIPPED_STALE
            }
        }

    private suspend fun commitUpsert(
        operation: VectorSyncOutboxEntity,
        embedding: FloatArray,
    ): Outcome =
        SessionMutationLocks.withSessionLock(operation.sessionId) {
            val source = readSource(operation)
            if (!isLatest(operation) || source == null || !source.isEligibleCurrent(operation)) {
                outboxDao.deleteById(operation.operationId)
                return@withSessionLock Outcome.SKIPPED_STALE
            }
            val vectorId =
                vectorMemoryService.commitPrepared(
                    embedding = embedding,
                    content = source.content,
                    ownerId = source.ownerId,
                    characterId = source.characterId,
                    sessionId = source.sessionId,
                    messageId = operation.vectorMessageId,
                    contentType = source.contentType,
                    replacePrevious = true,
                    sourceRevision = source.revision,
                )
            val flushed = vectorMemoryService.indexStore().flush()
            if (flushed) afterFlushBeforeAck(operation.operationId)
            val capacityRejected = vectorId == null && indexAtCapacity()
            if (flushed && (vectorId != null || capacityRejected)) {
                acknowledge(operation)
                Outcome.COMPLETED
            } else {
                scheduleRetry(operation, IllegalStateException("vector commit or flush failed"))
                Outcome.RETRY_SCHEDULED
            }
        }

    private suspend fun processDelete(operation: VectorSyncOutboxEntity): Outcome =
        SessionMutationLocks.withSessionLock(operation.sessionId) {
            if (!isLatest(operation)) return@withSessionLock Outcome.SKIPPED_STALE
            val source = readSource(operation)
            if (source.shouldProtectFromRemoval(operation)) {
                outboxDao.deleteById(operation.operationId)
                return@withSessionLock Outcome.SKIPPED_STALE
            }
            deleteAndAck(operation)
        }

    private suspend fun removeObsoleteCopy(operation: VectorSyncOutboxEntity): Outcome =
        SessionMutationLocks.withSessionLock(operation.sessionId) {
            if (!isLatest(operation)) return@withSessionLock Outcome.SKIPPED_STALE
            val source = readSource(operation)
            if (source.shouldProtectFromRemoval(operation)) {
                outboxDao.deleteById(operation.operationId)
                return@withSessionLock Outcome.SKIPPED_STALE
            }
            deleteAndAck(operation)
        }

    private suspend fun invalidateAndRetry(
        operation: VectorSyncOutboxEntity,
        reason: String,
    ): Outcome =
        SessionMutationLocks.withSessionLock(operation.sessionId) {
            val source = readSource(operation)
            if (!isLatest(operation) || !source.isCurrent(operation)) {
                outboxDao.deleteById(operation.operationId)
                return@withSessionLock Outcome.SKIPPED_STALE
            }
            vectorMemoryService.deleteByMessageIdPersisted(
                operation.vectorMessageId,
                operation.ownerId,
                operation.sessionId,
            )
            scheduleRetry(operation, IllegalStateException(reason))
            Outcome.RETRY_SCHEDULED
        }

    private suspend fun deleteAndAck(operation: VectorSyncOutboxEntity): Outcome {
        val persisted =
            vectorMemoryService.deleteByMessageIdPersisted(
                operation.vectorMessageId,
                operation.ownerId,
                operation.sessionId,
            )
        return if (persisted) {
            afterFlushBeforeAck(operation.operationId)
            acknowledge(operation)
            Outcome.COMPLETED
        } else {
            scheduleRetry(operation, IllegalStateException("vector deletion flush failed"))
            Outcome.RETRY_SCHEDULED
        }
    }

    private suspend fun acknowledge(operation: VectorSyncOutboxEntity) {
        outboxDao.acknowledgeThrough(
            operation.sourceType,
            operation.ownerId,
            operation.sessionId,
            operation.sourceId,
            operation.operationId,
        )
    }

    /** 调用方持有会话锁。旧操作只删除自己的 outbox 行，绝不确认期间的新操作。 */
    private suspend fun isLatest(operation: VectorSyncOutboxEntity): Boolean {
        if (outboxDao.getById(operation.operationId) == null) return false
        val latest =
            outboxDao.latestForSource(
                operation.sourceType,
                operation.ownerId,
                operation.sessionId,
                operation.sourceId,
            )
        val current = latest?.operationId == operation.operationId
        if (!current) outboxDao.deleteById(operation.operationId)
        return current
    }

    private suspend fun scheduleRetry(
        operation: VectorSyncOutboxEntity,
        error: Exception,
    ) {
        val shift = operation.attemptCount.coerceIn(0, MAX_RETRY_BACKOFF_SHIFT)
        val delay = (BASE_RETRY_DELAY_MS * (1L shl shift)).coerceAtMost(MAX_RETRY_DELAY_MS)
        val reason = "${error::class.simpleName}:${error.message.orEmpty()}".take(MAX_ERROR_CHARS)
        try {
            outboxDao.markRetry(operation.operationId, clock() + delay, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "记录向量恢复重试状态失败：${e.message}", e)
        }
    }

    private suspend fun indexAtCapacity(): Boolean {
        val stats = vectorMemoryService.indexStore().indexStats()
        return stats.budgetBytes > 0 && stats.bytes >= stats.budgetBytes
    }

    private suspend fun readSource(operation: VectorSyncOutboxEntity): SourceSnapshot? =
        when (operation.sourceType) {
            VectorSyncOutboxEntity.SOURCE_SUMMARY -> {
                val id = operation.sourceId.toLongOrNull() ?: return null
                val memory = db.structuredMemoryDao().getById(id) ?: return null
                val identityMatches =
                    memory.ownerId == operation.ownerId && memory.sessionId.orEmpty() == operation.sessionId
                val session = db.sessionDao().getById(operation.sessionId)
                SourceSnapshot(
                    revision = memory.vectorRevision,
                    content = memory.toDomain().buildVectorContent(),
                    ownerId = memory.ownerId,
                    characterId = memory.characterId ?: VectorStore.CHARACTER_LEVEL_CHARACTER,
                    sessionId = memory.sessionId.orEmpty(),
                    contentType = VectorStore.SUMMARY_CONTENT_TYPE,
                    identityMatches = identityMatches,
                    eligible =
                        identityMatches && session?.ownerId == memory.ownerId &&
                            memory.importance >= VectorSyncSource.SUMMARY_IMPORTANCE_THRESHOLD,
                )
            }
            VectorSyncOutboxEntity.SOURCE_MESSAGE -> {
                val message = db.messageDao().getAnyById(operation.sourceId) ?: return null
                val identityMatches =
                    message.ownerId == operation.ownerId && message.sessionId == operation.sessionId
                val session = db.sessionDao().getById(operation.sessionId)
                SourceSnapshot(
                    revision = message.vectorRevision,
                    content = message.content,
                    ownerId = message.ownerId,
                    characterId = message.characterId,
                    sessionId = message.sessionId,
                    contentType = VectorSyncSource.messageContentType(message.role),
                    identityMatches = identityMatches,
                    eligible = identityMatches && session?.ownerId == message.ownerId,
                )
            }
            else -> null
        }

    private fun SourceSnapshot?.isCurrent(operation: VectorSyncOutboxEntity): Boolean =
        this != null && identityMatches && revision == operation.sourceRevision

    private fun SourceSnapshot?.isEligibleCurrent(operation: VectorSyncOutboxEntity): Boolean =
        isCurrent(operation) && this?.eligible == true && this.content.isNotBlank()

    private fun SourceSnapshot?.shouldProtectFromRemoval(operation: VectorSyncOutboxEntity): Boolean {
        if (this == null) return false
        return !identityMatches || revision != operation.sourceRevision || eligible
    }

    private data class SourceSnapshot(
        val revision: String,
        val content: String,
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val contentType: String,
        val identityMatches: Boolean,
        val eligible: Boolean,
    )

    private data class Claim(
        val database: AppDatabase,
        val operationId: Long,
    )

    companion object {
        private const val TAG = "VectorSyncRecovery"
        private const val DEFAULT_BATCH_SIZE = 32
        private const val BASE_RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 5 * 60_000L
        private const val MAX_RETRY_BACKOFF_SHIFT = 6
        private const val MAX_ERROR_CHARS = 200
        private val claimMutex = Mutex()
        private val claims = mutableSetOf<Claim>()

        private suspend fun claim(claim: Claim): Boolean = claimMutex.withLock { claims.add(claim) }

        private suspend fun release(claim: Claim) {
            claimMutex.withLock { claims.remove(claim) }
        }
    }
}
