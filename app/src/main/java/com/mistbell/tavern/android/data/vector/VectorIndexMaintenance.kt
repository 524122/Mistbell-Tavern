// 这些维护动作的契约都是"失败不掀翻调用方"——统计/清理失败最多让召回少一条，
// 不该让发送链路或备份流程崩掉，因此统一捕获 Exception 并降级为返回值。
@file:Suppress("TooGenericExceptionCaught")

package com.mistbell.tavern.android.data.vector

import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase

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
