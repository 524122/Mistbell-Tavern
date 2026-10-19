package com.mistbell.tavern.android.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按 sessionId 的互斥锁注册表：让"删除/清空会话"与"在途写入"（记忆保存、消息向量化）串行化。
 *
 * 为什么需要它：LLM 抽取和 embedding 都很慢，完成时它们的目标会话可能已被删除/清空。
 * 只做"写前一次存在性查询"挡不住并发——查询与会话删除之间仍有窗口。统一经本注册表
 * 对同一 sessionId 加锁：慢写入在锁内复查存在性，删除在锁内执行，两边天然互斥。
 *
 * 粒度取舍：按会话（而非全局一把锁）——不同会话的删除/写入互不相干，全局锁会让
 * 删会话 A 卡住会话 B 的消息向量化。
 *
 * 生命周期：注册表按引用计数清理（[Entry.users] 归零即移除条目），不会因会话数量
 * 增长而无限膨胀；计数增减与移除都在 [sync] 临界区内，无"旧锁引用方与新锁并行"的竞态。
 */
object SessionMutationLocks {
    private class Entry {
        val mutex = Mutex()
        val users = AtomicInteger(0)
    }

    private val sync = Any()
    private val locks = ConcurrentHashMap<String, Entry>()

    /**
     * 在指定会话的互斥锁内执行 [block]，串行化该会话的一切删除/在途写入。
     *
     * 不可重入：同协程已持锁时再进同 sessionId 的锁会死锁，调用方需自行保证不嵌套。
     */
    suspend fun <T> withSessionLock(
        sessionId: String,
        block: suspend () -> T,
    ): T {
        val entry =
            synchronized(sync) {
                locks.getOrPut(sessionId) { Entry() }.also { it.users.incrementAndGet() }
            }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(sync) {
                if (entry.users.decrementAndGet() == 0) locks.remove(sessionId, entry)
            }
        }
    }
}
