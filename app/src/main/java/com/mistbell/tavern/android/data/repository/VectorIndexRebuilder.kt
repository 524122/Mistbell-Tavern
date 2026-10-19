package com.mistbell.tavern.android.data.repository

import android.util.Log
import com.mistbell.tavern.android.data.api.model.VectorMemory.Companion.ContentType
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.VectorEviction
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.getStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 重建索引时的消息来源（生产是 Room，测试可替身） */
fun interface RecentMessageSource {
    suspend fun recent(
        ownerId: String,
        limit: Int,
    ): List<MessageEntity>
}

/**
 * 向量索引重建：把消息库里最近的对话重新灌回索引。
 *
 * 为什么需要它：索引有两条容量上限——条数（[InMemoryVectorStore.DEFAULT_MAX_VECTORS]）与
 * **磁盘字节预算**（[InMemoryVectorStore.DEFAULT_INDEX_BUDGET_BYTES]），超限按 [VectorEviction]
 * 淘汰，而被淘汰的向量**无法自动重建**——用得越久，早期记忆越会被丢掉。这是唯一的恢复入口，
 * 也是"永久遗忘"变回"可恢复"的那一步。
 *
 * 边界与取舍：
 * - **只重建最近 [DEFAULT_LIMIT] 条消息**：embedding 有成本（本地跑推理、API 花额度），而索引
 *   本来就只留得下最近的一段，重放更早的内容只会立刻被淘汰掉；
 * - **按 message_id + 源修订号跳过已索引内容**：重复执行因而很廉价；也避免重复向量；
 * - **预算已满就停手**：继续灌只会把刚写进去的向量再淘汰掉，白花额度还什么都留不下；
 *   调用方据 [Result.declined] 告诉用户"索引满了，先删点东西或换更大的预算"；
 * - 生产重建同时覆盖普通消息和符合条件的高重要度摘要，并先写持久化待办再执行。
 *
 * 逐条 embedding 而不用 `embedBatch`：本地 ONNX 的 embedBatch 就是逐条循环（没有收益），
 * 而 API 侧一次发几百条会撞上单请求上限与限流——逐条至少可控、可报进度、可单条降级。
 *
 * 这是维护入口而非热路径：不做自动触发，由用户在设置里显式发起。
 */
class VectorIndexRebuilder private constructor(
    private val vectorMemoryService: VectorMemoryService,
    private val source: RecentMessageSource,
    private val isEmbeddingAvailable: () -> Boolean,
    private val isIndexFull: suspend () -> Boolean,
    private val persistentDelegate: PersistentVectorIndexRebuilder? = null,
) {
    /** [embedded] 新写入、[skipped] 已在索引中而跳过、[failed] 单条失败被跳过、[declined] 因磁盘预算已满而放弃 */
    data class Result(
        val embedded: Int,
        val skipped: Int,
        val failed: Int,
        val total: Int,
        val declined: Int = 0,
    )

    /**
     * 重建。单条失败不中断整体（一条坏消息不该毁掉几百条的重建），失败数在结果里如实上报。
     *
     * **磁盘预算已满时提前停手**：再灌下去只会把刚写进去的向量按新旧顺序淘汰掉，
     * 白烧 embedding 额度却什么都没留下（表现为"重建跑了半天，还是想不起来"）。
     * 返回的 [Result.declined] 就是"因预算放弃"的条数，让调用方能如实告诉用户。
     */
    suspend fun rebuild(
        ownerId: String,
        limit: Int = DEFAULT_LIMIT,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result =
        withContext(Dispatchers.IO) {
            persistentDelegate?.let { return@withContext it.rebuild(ownerId, limit, onProgress) }
            if (!isEmbeddingAvailable()) {
                Log.w(TAG, "语义记忆源不可用（词法回退模式），跳过重建")
                return@withContext Result(embedded = 0, skipped = 0, failed = 0, total = 0)
            }
            if (isIndexFull()) {
                Log.w(TAG, "索引已达磁盘预算，跳过重建（继续写入只会把刚灌进去的向量再淘汰掉）")
                return@withContext Result(embedded = 0, skipped = 0, failed = 0, total = 0)
            }

            val messages = source.recent(ownerId, limit)
            val indexed = vectorMemoryService.indexedMessageIds()
            val pending = messages.filter { it.id !in indexed }
            Log.d(TAG, "重建索引：候选 ${messages.size} 条，待写入 ${pending.size} 条")

            var embedded = 0
            var failed = 0
            var declined = 0
            for (message in pending) {
                // 每批重新看一眼预算：重建过程中索引会真的长大，一次判定管不了整轮
                if (isIndexFull()) {
                    declined = pending.size - embedded - failed
                    Log.w(TAG, "索引已达磁盘预算，重建提前结束（放弃 $declined 条）")
                    break
                }
                if (storeOne(message)) embedded++ else failed++
                onProgress(embedded + failed, pending.size)
            }

            Result(
                embedded = embedded,
                skipped = messages.size - pending.size,
                failed = failed,
                total = messages.size,
                declined = declined,
            )
        }

    /**
     * 单条重建写入。
     *
     * 用 `replacePrevious = true`：重建的候选来自消息库里**现存**的消息，而那条消息可能带着
     * 早先删除操作留下的意图（回退/重抽/清空会话都登记过）。不解除意图的话，重建会"一条也写不进去"
     * ——用户点了重建、进度条走完，索引还是空的。
     *
     * 生产路径不直接调用本方法：它先记录 outbox，并在提交前复核当前源版本，避免删除后复活；
     * 本方法仅保留给现有轻量测试替身的直接重建契约。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun storeOne(message: MessageEntity): Boolean =
        try {
            vectorMemoryService.storeMessage(
                content = message.content,
                ownerId = message.ownerId,
                characterId = message.characterId,
                sessionId = message.sessionId,
                messageId = message.id,
                contentType = contentTypeOf(message.role),
                replacePrevious = true,
            ) != null
        } catch (e: Exception) {
            Log.w(TAG, "重建：消息 ${message.id} 写入失败，跳过。${e.message}")
            false
        }

    /** 角色 → content_type，与实时写入路径保持一致 */
    private fun contentTypeOf(role: String): String {
        return if (role == ROLE_USER) ContentType.USER_MESSAGE else ContentType.AI_MESSAGE
    }

    companion object {
        private const val TAG = "VectorIndexRebuilder"
        private const val ROLE_USER = "user"

        // 单次重建的消息上限：embedding 有成本，且索引只留得下最近一段。
        // 用户显式重建时覆盖当前 owner 的全部消息；生产存储不再用 1000 条硬上限淘汰。
        // embedding 成本由调用方承担，结果按进度回报，失败单条跳过。
        const val DEFAULT_LIMIT = Int.MAX_VALUE

        /** 生产装配：消息来自 Room */
        fun forDatabase(
            db: AppDatabase,
            vectorMemoryService: VectorMemoryService,
        ): VectorIndexRebuilder =
            VectorIndexRebuilder(
                vectorMemoryService = vectorMemoryService,
                source = { ownerId, limit -> db.messageDao().getRecentByOwner(ownerId, limit) },
                isEmbeddingAvailable = { vectorMemoryService.available },
                // 容量判定只看内存里的字节累加（InMemoryVectorStore.measureLocked），不碰磁盘
                isIndexFull = {
                    val stats = vectorMemoryService.getStatistics()
                    stats.budgetBytes > 0 && stats.bytes >= stats.budgetBytes
                },
                persistentDelegate =
                    PersistentVectorIndexRebuilder(
                        db = db,
                        vectorMemoryService = vectorMemoryService,
                        messageSource = { ownerId, limit -> db.messageDao().getRecentByOwner(ownerId, limit) },
                        memorySource = { ownerId, limit ->
                            db.structuredMemoryDao().getSummariesForReindex(ownerId, limit)
                        },
                        recovery = VectorSyncRecovery(db, vectorMemoryService),
                    ),
            )

        /** 测试用：消息来源、可用性与容量判定都可替身 */
        internal fun forTest(
            vectorMemoryService: VectorMemoryService,
            source: RecentMessageSource,
            isEmbeddingAvailable: () -> Boolean = { true },
            isIndexFull: suspend () -> Boolean = { false },
        ): VectorIndexRebuilder =
            VectorIndexRebuilder(
                vectorMemoryService = vectorMemoryService,
                source = source,
                isEmbeddingAvailable = isEmbeddingAvailable,
                isIndexFull = isIndexFull,
            )
    }
}
