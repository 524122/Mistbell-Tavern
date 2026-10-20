// 这些维护动作的契约都是"失败不掀翻调用方"——统计/清理失败最多让召回少一条，
// 不该让发送链路或备份流程崩掉，因此统一捕获 Exception 并降级为返回值。
@file:Suppress("TooGenericExceptionCaught")

package com.mistbell.tavern.android.data.vector

import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.repository.VectorSyncSource

/**
 * 向量索引的维护动作：清空、对账、统计、清理旧指纹。
 *
 * 与 [VectorMemoryService] 的读写热路径分开：这些是**低频维护**（备份合并、进程启动、换模型、
 * 设置页展示），而热路径只关心"写一条、删一条、查一次"。拆开之后两边的依赖也更清楚——
 * 维护动作需要 Room（对账）与索引统计，读写不需要。
 */

/**
 * 清空当前索引；备份合并后需要从合并后的 Room 数据重建，避免旧索引与新正文错配。
 *
 * 同时登记"全部 message_id 已删除"：清空往往紧跟着会话/记忆的删除，此时在途的写入
 * 若照常落地，会把刚清掉的索引又填回去。
 */
suspend fun VectorMemoryService.clearIndex() {
    try {
        val ids = indexedMessageIds()
        if (ids.isNotEmpty()) {
            indexStore().markMessagesDeleted(ids)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "清空索引前登记删除意图失败：${e.message}")
    }
    indexStore().clear()
    check(indexStore().flush()) { "向量索引清空后尚未可靠落盘" }
}

/**
 * 启动对账：把"所属会话已被删除"的向量从索引里收敛掉。
 *
 * 内存里的删除意图会随进程结束丢失，磁盘上却留着已删会话的向量；重启后它们照样能被召回
 * （跨会话召回放大这个影响）。以 Room 的会话表为准做一次收敛，代价是一次会话 id 全表读。
 *
 * 安全边界：会话表为空时**什么都不做**——空库/查询失败绝不能被当成"所有会话都已删除"，
 * 那会一次抹掉整份索引。
 *
 * @return 删除条数
 */
suspend fun VectorMemoryService.reconcileWithDatabase(db: AppDatabase): Int {
    return try {
        if (db.sessionDao().countAll() <= 0) {
            Log.d(TAG, "会话表为空，跳过索引对账")
            return 0
        }
        val known = db.sessionDao().allSessionIds().toHashSet()
        val deleted = indexStore().purgeSessionsNotIn(known)
        if (deleted > 0) {
            Log.w(TAG, "启动对账：索引中有 $deleted 条向量所属会话已不存在，已清理")
        }
        deleted
    } catch (e: Exception) {
        Log.e(TAG, "索引对账失败（保留原索引）：${e.message}", e)
        0
    }
}

/**
 * 启动对账（消息级）：把"源消息/源记忆已不存在"的孤儿向量从索引里清掉。
 *
 * 为什么需要它：删除路径先在锁内删内存、再在锁外落盘，进程在落盘成功前被强杀时，
 * 已删源的向量会在磁盘上复活；而一次性 v1 对账（VectorIndexRecoveryReconciler）只收敛
 * 存量，不管之后新增的孤儿；会话级对账（[reconcileWithDatabase]）又只看会话是否存在，
 * 管不到"会话还在、消息没了"这一层。孤儿不影响正确性（召回热路径有 VectorRecallValidator
 * 兜底过滤），但会占着索引字节预算、把有效向量挤出去。
 *
 * 安全边界与会话级对账一致：**只有"能证明源没了"才删**——
 * - 缺 message_id、summary id 无法解析的条目保留（无法证明归属）；
 * - 源表为空时跳过对应命名空间（空表只能证明"读不到"，不能证明"全删了"）；
 * - 只核查存在性：内容/revision 漂移是召回时 Validator 的职责，不在此处理。
 *
 * @return 删除条数
 */
suspend fun VectorMemoryService.reconcileOrphanedSourcesWithDatabase(db: AppDatabase): Int {
    return try {
        val entries = indexStore().entriesSnapshot()
        if (entries.isEmpty()) return 0
        val sourceIds = collectVectorSourceIds(entries)

        val existingMessageIds = existingMessageIdsOrNull(db, sourceIds.messageIds)
        val existingMemoryIds =
            if (sourceIds.memoryIds.isEmpty()) {
                emptySet()
            } else {
                // 与消息表同一闸门：空表不能作为"全被删了"的证据
                db.structuredMemoryDao().getAllOnce().mapTo(mutableSetOf()) { it.id }
                    .takeIf { it.isNotEmpty() }
            }

        val orphans = orphanedVectorMessageIds(entries, existingMessageIds, existingMemoryIds)
        if (orphans.isEmpty()) return 0
        var deleted = 0
        orphans.chunked(SOURCE_CHECK_CHUNK_SIZE).forEach { chunk ->
            deleted += indexStore().deleteByFilters(mapOf("message_id" to chunk))
        }
        if (deleted > 0) {
            Log.w(TAG, "启动对账：索引中有 $deleted 条向量的源消息/记忆已不存在，已清理")
        }
        deleted
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "孤儿向量对账失败（保留原索引）：${e.message}", e)
        0
    }
}

/** 消息表非空才做存在性核查；空表 → null（本命名空间跳过），与 [reconcileWithDatabase] 的闸门同语义 */
private suspend fun existingMessageIdsOrNull(
    db: AppDatabase,
    messageIds: Set<String>,
): Set<String>? =
    when {
        messageIds.isEmpty() -> emptySet()
        db.messageDao().countAll() <= 0 -> {
            Log.w(TAG, "消息表为空，跳过消息命名空间的孤儿对账")
            null
        }
        else ->
            messageIds
                .chunked(SOURCE_CHECK_CHUNK_SIZE)
                .flatMap { db.messageDao().existingIds(it) }
                .toSet()
    }

/** 向量条目的源 id 分流（纯函数，internal 供单测）：普通消息 id 与 summary 的记忆 id 两个命名空间 */
internal data class VectorSourceIds(
    val messageIds: Set<String>,
    val memoryIds: Set<Long>,
)

/**
 * 从索引条目里收集待核查的源 id。缺 message_id、summary id 畸形的条目不收集——
 * 它们同时也会被 [orphanedVectorMessageIds] 跳过（无法证明归属，一律保留）。
 */
internal fun collectVectorSourceIds(entries: List<VectorStore.VectorEntry>): VectorSourceIds {
    val messageIds = mutableSetOf<String>()
    val memoryIds = mutableSetOf<Long>()
    entries.forEach { entry ->
        val messageId = entry.metadata["message_id"] as? String ?: return@forEach
        if (entry.metadata["content_type"] == VectorStore.SUMMARY_CONTENT_TYPE) {
            summaryMemoryIdOf(messageId)?.let { memoryIds += it }
        } else {
            messageIds += messageId
        }
    }
    return VectorSourceIds(messageIds, memoryIds)
}

/**
 * 判定孤儿向量（纯函数，internal 供单测），返回待删除的 message_id 集合。
 *
 * [existingMessageIds] / [existingMemoryIds] 为 null 表示对应命名空间本次跳过
 * （源表为空、无法取证），该命名空间的条目一律保留。
 */
internal fun orphanedVectorMessageIds(
    entries: List<VectorStore.VectorEntry>,
    existingMessageIds: Set<String>?,
    existingMemoryIds: Set<Long>?,
): Set<String> =
    entries.mapNotNullTo(mutableSetOf()) { entry ->
        val messageId = entry.metadata["message_id"] as? String ?: return@mapNotNullTo null
        if (entry.metadata["content_type"] == VectorStore.SUMMARY_CONTENT_TYPE) {
            val memoryId = summaryMemoryIdOf(messageId) ?: return@mapNotNullTo null
            if (existingMemoryIds != null && memoryId !in existingMemoryIds) messageId else null
        } else {
            if (existingMessageIds != null && messageId !in existingMessageIds) messageId else null
        }
    }

/**
 * summary 向量的 message_id → 记忆 id。规范编码器是 VectorSyncSource.summaryMessageId，
 * 这里用"编码往返一致"做校验，前缀字面量若与编码器漂移则解析失败、条目保留（保守方向）。
 */
private fun summaryMemoryIdOf(messageId: String): Long? =
    messageId
        .removePrefix(STRUCTURED_MEMORY_PREFIX)
        .toLongOrNull()
        ?.takeIf { messageId == VectorSyncSource.summaryMessageId(it) }

private const val STRUCTURED_MEMORY_PREFIX = "structured_memory_"

/** SQLite IN 变量的安全上限是 999，留足余量按 500 分块 */
private const val SOURCE_CHECK_CHUNK_SIZE = 500

/**
 * 备份导出：把当前索引序列化为 `vector_store.json` 线格式（与存储引擎无关——
 * sqlite-vec 与 JSON 内存索引产出同一格式，备份 zip 条目对两种引擎可互相恢复）。
 *
 * 失败返回 null（调用方跳过该条目，与"索引文件不存在"的旧语义一致）；
 * 索引为空时导出 `{"vectors":[]}`——恢复侧据此跳过昂贵的全量重建。
 */
suspend fun VectorMemoryService.exportIndexJson(): String? =
    try {
        encodeVectorIndex(indexStore().entriesSnapshot())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "索引导出失败（备份将不含索引条目）：${e.message}", e)
        null
    }

/**
 * 获取向量存储统计信息（条数 + 落盘字节 + 预算）。
 *
 * 磁盘容量治理必须**可观测**：设置页据此显示占用，索引是否接近上限不再是一个看不见的状态。
 */
suspend fun VectorMemoryService.getStatistics(): VectorStatistics {
    return try {
        val stats = indexStore().indexStats()
        VectorStatistics(
            totalVectors = stats.count,
            dimension = embeddingDimension(),
            bytes = stats.bytes,
            budgetBytes = stats.budgetBytes,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get statistics: ${e.message}", e)
        VectorStatistics(0, 0, 0, 0)
    }
}

/**
 * 清理不属于当前 embedding 指纹的向量（含指纹引入前写入、来源不明的历史向量）。
 *
 * 这些向量已被检索过滤器挡住、不会再被召回，但仍占着内存与磁盘预算，
 * 会把当前模型的向量挤出去，因此换模型后清一次。
 */
suspend fun VectorMemoryService.purgeStaleEmbeddings(): Int {
    return try {
        val deleted =
            indexStore().deleteWhereMetadataDiffers(
                "embedding_fingerprint",
                embeddingFingerprint(),
            )
        if (deleted > 0) {
            Log.d(TAG, "Purged $deleted vectors from other embedding fingerprints")
        }
        deleted
    } catch (e: Exception) {
        Log.e(TAG, "Failed to purge stale embeddings: ${e.message}", e)
        0
    }
}

/**
 * 向量统计信息
 */
data class VectorStatistics(
    val totalVectors: Int,
    val dimension: Int,
    val bytes: Long = 0,
    val budgetBytes: Long = 0,
)

private const val TAG = "VectorMemoryService"
