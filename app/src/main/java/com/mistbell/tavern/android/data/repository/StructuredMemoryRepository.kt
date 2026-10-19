package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.deleteSummaryVectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * @param resetGate 记忆链闸门（默认生产闸门）：升级清零未完成时抛
 *   [MemoryResetPendingException]。所有 suspend 读写入口（含 *Locked 变体——锁只保证
 *   同会话串行，不替代"清零先行"）必须先过闸门，写入宁可失败也不与一次性清零竞争。
 *   Flow 返回的观察型入口无法挂起，不在此列；它们的消费者是 UI 列表，看到 pre/post
 *   清零快照均可接受，真正的写入竞争已由 suspend 入口挡住。
 */
class StructuredMemoryRepository(
    context: Context,
    private val resetGate: suspend () -> Unit =
        TavernApplication.instance.container.sessionMemoryResetGate,
    private val db: AppDatabase =
        TavernApplication.instance.container.database,
    vectorMemoryService: VectorMemoryService? = null,
) {
    // 测试注入缝：默认 null → 首次使用时才经容器解析。
    // 不能在构造期求值默认值：记忆列表页构造 Repository 时会连带初始化
    // vectorMemoryService → embeddingService（可能加载 ONNX 模型），破坏生产的 lazy 行为。
    private val vectorMemoryService: VectorMemoryService by lazy(LazyThreadSafetyMode.NONE) {
        vectorMemoryService ?: TavernApplication.instance.container.vectorMemoryService
    }
    private val memoryDao = db.structuredMemoryDao()
    private val outboxScheduler = VectorSyncOutboxScheduler(db)
    private val vectorSyncRecovery by lazy(LazyThreadSafetyMode.NONE) {
        VectorSyncRecovery(db, this@StructuredMemoryRepository.vectorMemoryService)
    }

    /** 记忆链闸门（fail-closed）：未通过即抛异常，由调用方决定上抛或降级 */
    private suspend fun awaitResetGate() = resetGate()

    companion object {
        private const val TAG = "StructuredMemoryRepo"
    }

    // 获取所有记忆
    fun getAllMemories(ownerId: String): Flow<List<StructuredMemory>> {
        return memoryDao.getAll(ownerId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按角色获取记忆
    fun getMemoriesByCharacter(
        ownerId: String,
        characterId: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getByCharacter(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * 记忆管理页的可见集合：仅当前会话的记忆（owner + session）。
     *
     * 与召回口径一致；类型/关键词筛选由调用方在这份集合上做，
     * 保证"筛出来的"和"列出来的"永远是同一份数据的子集。
     *
     * characterId 不再影响可见集合（会话内长期记忆），参数仅为兼容旧调用方保留。
     */
    @Deprecated("会话内长期记忆：characterId 不再参与可见集合过滤，保留参数仅为兼容旧调用方")
    fun getMemoriesVisibleInSession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getVisibleInSession(ownerId, characterId, sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /** 一次性查询：某会话内的全部记忆（LLM 抽取去重的唯一查重来源——只认当前会话） */
    suspend fun getMemoriesByOwnerAndSession(
        ownerId: String,
        sessionId: String,
    ): List<StructuredMemory> {
        awaitResetGate()
        return withContext(Dispatchers.IO) {
            memoryDao.getByOwnerAndSession(ownerId, sessionId).map { it.toDomain() }
        }
    }

    // 按类型获取记忆
    fun getMemoriesByType(
        ownerId: String,
        memoryType: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getByType(ownerId, memoryType).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 按重要性获取记忆
    fun getMemoriesByImportance(
        ownerId: String,
        minImportance: Int,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.getByImportance(ownerId, minImportance).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 搜索记忆
    fun searchMemories(
        ownerId: String,
        query: String,
    ): Flow<List<StructuredMemory>> {
        return memoryDao.search(ownerId, query).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // 获取单个记忆
    suspend fun getMemoryById(id: Long): StructuredMemory? {
        awaitResetGate()
        return withContext(Dispatchers.IO) {
            memoryDao.getById(id)?.toDomain()
        }
    }

    /**
     * 归属硬校验：会话内长期记忆（owner + session）。
     *
     * 创建/更新都必须满足：ownerId、sessionId 非 blank；sessionId 对应的会话存在；
     * 且 session.ownerId == ownerId。违反即抛 [IllegalArgumentException]/[IllegalStateException]——
     * 宁可写不进去，也不落一条召不回/串线的孤儿记忆。
     *
     * 契约：必须在 [SessionMutationLocks] 会话锁内调用（由公开入口或 *Locked 变体的调用方保证），
     * "校验 + 写入"同一临界区，无 TOCTOU 窗口。
     */
    private suspend fun validateOwnership(
        ownerId: String,
        sessionId: String?,
    ) {
        require(ownerId.isNotBlank()) { "ownerId 不能为空" }
        require(!sessionId.isNullOrBlank()) { "会话内长期记忆要求 sessionId 非空（禁止角色级/跨会话记忆）" }
        val session =
            db.sessionDao().getById(sessionId)
                ?: error("会话不存在: $sessionId，拒绝写入记忆")
        check(session.ownerId == ownerId) { "会话 $sessionId 不属于 owner $ownerId，拒绝写入记忆" }
    }

    /** 无会话归属（blank）时无法取会话锁：直接按归属校验拒绝（与 validateOwnership 的 blank 分支同文案） */
    private fun requireLockableSessionId(sessionId: String?): String =
        sessionId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "会话内长期记忆要求 sessionId 非空（禁止角色级/跨会话记忆）",
            )

    /**
     * 锁外向量收尾的待办：Room 写入（锁内）与派生向量索引（慢 embedding，锁外）分离。
     * 每个待办携带**写入时快照的修订标识**（[revisionAtCommit]）。
     *
     * 执行协议（见 [runVectorFollowUps]）：慢 embedding 在锁外；**最终版本核验 + 索引
     * 提交/删除在会话锁内完成**——核验与提交同一临界区，中间不可能插入 Room 变更。
     */
    sealed interface VectorFollowUp {
        /** 重要性 ≥ 阈值：把该版本的内容同步为向量摘要 */
        data class Sync(
            val memory: StructuredMemory,
            val revisionAtCommit: String,
            val operationId: Long,
        ) : VectorFollowUp

        /**
         * 重要性降到阈值以下（或记忆被删）：清理既有的向量摘要副本。
         * 仅当"记忆已不存在"或"现存版本签名与快照一致（没有更新的版本）"时才执行——
         * 否则放弃，防止晚到的旧 DeleteCopy 删掉新版本重新升高重要度产生的有效摘要。
         */
        data class DeleteCopy(
            val memoryId: Long,
            val revisionAtCommit: String,
            /** 取会话锁用：记忆可能已被删（无法从库读回 sessionId），快照必须自带 */
            val lockSessionId: String,
            val operationId: Long,
        ) : VectorFollowUp
    }

    /** 执行与源写入同一 Room 事务记录的持久化待办；调用方不得持有会话锁。 */
    internal suspend fun runVectorFollowUps(followUps: List<VectorFollowUp>) {
        followUps.forEach { followUp ->
            val operationId =
                when (followUp) {
                    is VectorFollowUp.Sync -> followUp.operationId
                    is VectorFollowUp.DeleteCopy -> followUp.operationId
                }
            vectorSyncRecovery.processNow(operationId)
        }
    }

    // 创建记忆（公开入口：自行取会话锁；向量同步移到锁外）
    suspend fun createMemory(memory: StructuredMemory): Long {
        awaitResetGate()
        val (memoryId, followUps) =
            withContext(Dispatchers.IO) {
                SessionMutationLocks.withSessionLock(requireLockableSessionId(memory.sessionId)) {
                    createMemoryLocked(memory)
                }
            }
        runVectorFollowUps(followUps)
        return memoryId
    }

    /**
     * 创建记忆（*Locked 变体）：**调用方必须已持有该 sessionId 的 [SessionMutationLocks] 锁**，
     * 锁内完成"归属校验 + Room 插入"，无 TOCTOU；不触碰向量层（返回待办，由调用方出锁后执行）。
     */
    internal suspend fun createMemoryLocked(memory: StructuredMemory): Pair<Long, List<VectorFollowUp>> {
        awaitResetGate()
        return db.withTransaction {
            validateOwnership(memory.ownerId, memory.sessionId)
            val entity =
                StructuredMemoryEntity.fromDomain(
                    memory.copy(
                        createdAt = Instant.now().toString(),
                        updatedAt = Instant.now().toString(),
                    ),
                )
            val memoryId = memoryDao.insert(entity)
            memoryDao.ensureVectorRevision(memoryId)
            val writtenEntity = requireNotNull(memoryDao.getById(memoryId))
            val writtenMemory = writtenEntity.toDomain()
            val followUps =
                if (writtenMemory.shouldSyncToVector()) {
                    val operationId = outboxScheduler.enqueueSummary(writtenEntity)
                    listOf(
                        VectorFollowUp.Sync(
                            writtenMemory,
                            writtenEntity.vectorRevision,
                            operationId,
                        ),
                    )
                } else {
                    emptyList()
                }
            memoryId to followUps
        }
    }

    // 更新记忆（公开入口：自行取会话锁；向量同步/清理移到锁外）
    suspend fun updateMemory(memory: StructuredMemory) {
        awaitResetGate()
        val followUps =
            withContext(Dispatchers.IO) {
                SessionMutationLocks.withSessionLock(requireLockableSessionId(memory.sessionId)) {
                    updateMemoryLocked(memory)
                }
            }
        runVectorFollowUps(followUps)
    }

    /**
     * 更新记忆（*Locked 变体）：**调用方必须已持有该 sessionId 的 [SessionMutationLocks] 锁**；
     * 锁内完成"归属校验 + Room 更新"。向量同步/清理以 [VectorFollowUp] 返回，锁外执行。
     */
    internal suspend fun updateMemoryLocked(memory: StructuredMemory): List<VectorFollowUp> {
        awaitResetGate()
        return db.withTransaction {
            val existing = memoryDao.getById(memory.id) ?: error("记忆不存在: ${memory.id}")
            check(existing.sessionId == memory.sessionId) { "记忆的会话归属创建时确定，不允许变更" }
            check(existing.ownerId == memory.ownerId) { "记忆的 owner 归属不允许变更" }
            validateOwnership(memory.ownerId, memory.sessionId)
            val entity =
                StructuredMemoryEntity.fromDomain(memory.copy(updatedAt = Instant.now().toString()))
            memoryDao.update(entity)
            memoryDao.ensureVectorRevision(memory.id)
            val writtenEntity = requireNotNull(memoryDao.getById(memory.id))
            val writtenMemory = writtenEntity.toDomain()
            val operationId = outboxScheduler.enqueueSummary(writtenEntity)
            if (writtenMemory.shouldSyncToVector()) {
                listOf(VectorFollowUp.Sync(writtenMemory, writtenEntity.vectorRevision, operationId))
            } else {
                listOf(
                    VectorFollowUp.DeleteCopy(
                        memoryId = writtenMemory.id,
                        revisionAtCommit = writtenEntity.vectorRevision,
                        lockSessionId = requireNotNull(memory.sessionId),
                        operationId = operationId,
                    ),
                )
            }
        }
    }

    /**
     * 删除记忆（公开入口）：必须声明归属（ownerId + sessionId），与实际记录一致才删，
     * 不一致拒绝——防止按裸 id 误删/跨会话删除。
     */
    suspend fun deleteMemory(
        id: Long,
        ownerId: String,
        sessionId: String,
    ) {
        awaitResetGate()
        val followUps =
            withContext(Dispatchers.IO) {
                SessionMutationLocks.withSessionLock(requireLockableSessionId(sessionId)) {
                    deleteMemoryLocked(id, ownerId, sessionId)
                }
            }
        runVectorFollowUps(followUps)
    }

    /** 删除记忆（*Locked 变体）：调用方必须已持有该 sessionId 的锁；锁内校验归属并删除 */
    internal suspend fun deleteMemoryLocked(
        id: Long,
        ownerId: String,
        sessionId: String,
    ): List<VectorFollowUp> {
        awaitResetGate()
        return db.withTransaction {
            val existing = memoryDao.getById(id) ?: error("记忆不存在: $id")
            check(existing.ownerId == ownerId && existing.sessionId == sessionId) {
                "记忆 $id 归属 (${existing.ownerId}, ${existing.sessionId}) 与声明的 ($ownerId, $sessionId) 不一致，拒绝删除"
            }
            memoryDao.ensureVectorRevision(id)
            val deletedEntity = requireNotNull(memoryDao.getById(id))
            memoryDao.deleteById(id)
            val operationId =
                outboxScheduler.enqueueSummary(deletedEntity, VectorSyncOutboxEntity.DELETE)
            listOf(
                VectorFollowUp.DeleteCopy(
                    memoryId = id,
                    revisionAtCommit = deletedEntity.vectorRevision,
                    lockSessionId = sessionId,
                    operationId = operationId,
                ),
            )
        }
    }

    // 按角色删除记忆
    suspend fun deleteMemoriesByCharacter(
        ownerId: String,
        characterId: String,
    ) {
        awaitResetGate()
        withContext(Dispatchers.IO) {
            memoryDao.deleteByCharacter(ownerId, characterId)
        }
        deleteSummaryCopies(ownerId, characterId = characterId)
    }

    // 按会话删除记忆
    suspend fun deleteMemoriesBySession(
        ownerId: String,
        sessionId: String,
    ) {
        awaitResetGate()
        withContext(Dispatchers.IO) {
            memoryDao.deleteBySession(ownerId, sessionId)
        }
        deleteSummaryCopies(ownerId, sessionId = sessionId)
    }

    // 增加访问计数
    suspend fun incrementAccessCount(id: Long) {
        awaitResetGate()
        withContext(Dispatchers.IO) {
            memoryDao.incrementAccessCount(id, Instant.now().toString())
        }
    }

    // 获取记忆数量
    suspend fun getMemoryCount(ownerId: String): Int {
        awaitResetGate()
        return withContext(Dispatchers.IO) {
            memoryDao.getCount(ownerId)
        }
    }

    // 获取角色的记忆数量
    suspend fun getMemoryCountByCharacter(
        ownerId: String,
        characterId: String,
    ): Int {
        awaitResetGate()
        return withContext(Dispatchers.IO) {
            memoryDao.getCountByCharacter(ownerId, characterId)
        }
    }

    /** 批量清理摘要副本；不依赖 embedding 可用，失败仍由持久化待办继续恢复。 */
    private suspend fun deleteSummaryCopies(
        ownerId: String,
        characterId: String? = null,
        sessionId: String? = null,
    ) {
        try {
            vectorMemoryService.deleteSummaryVectors(ownerId, characterId, sessionId)
        } catch (e: Exception) {
            // 向量清理失败不应影响主流程
            Log.e(TAG, "Failed to delete summary vectors: ${e.message}", e)
        }
    }
}
