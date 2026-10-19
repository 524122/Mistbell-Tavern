package com.mistbell.tavern.android.data.vector

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 带缓存的向量存储装饰器
 *
 * 优化策略：
 * - 缓存搜索结果（基于查询向量哈希 + 过滤条件）
 * - LRU 缓存策略，避免内存过度占用
 * - 写操作自动清除相关缓存
 *
 * ## 缓存 generation 协议（删除/更新后不得召回旧值）
 *
 * 检索在锁外 await delegate.search（不能跨慢操作持锁），期间任何变更都可能已提交——
 * 若无条件回填，"变更前启动的检索"会把旧结果回填进"变更后的缓存"，后续相同查询一直
 * 命中已删/已改的旧值。协议：
 * - 任何变更路径（[invalidateCache]：add/addIfAllowed/delete/clear/reload/importIndex 等）
 *   在方法返回前**驱逐缓存并递增 [cacheGeneration]**；
 * - [search] 在 await delegate 前读一次 generation，回填前在 [cacheMutex] 内复查——
 *   不一致则丢弃结果（照常返回调用方，但不缓存）。检查与回填同处一把锁、失效与递增
 *   同处一把锁，两边互斥，旧检索永远无法污染新缓存。
 *
 * 其他读路径（get/count/indexBytes/indexStats/闸门/indexedMessageIds）均为纯透传、
 * 无缓存回填，无同类时序问题。
 *
 * 注解说明：装饰器必须逐一转发 VectorStore 的全部方法，方法数由接口决定，非本类可拆分。
 */
@Suppress("TooManyFunctions")
class CachedVectorStore(
    private val delegate: VectorStore,
    cacheSize: Int = 50,
) : VectorStore {
    private val searchCache = LruCache<SearchCacheKey, List<VectorStore.SearchResult>>(cacheSize)
    private val cacheMutex = Mutex()

    /**
     * 缓存世代：每次失效时递增。"检索启动时的世代" != "回填时的世代" ⇒ 检索期间发生过
     * 变更，其结果不得进入缓存。
     */
    private val cacheGeneration = AtomicLong(0)

    companion object {
        private const val TAG = "CachedVectorStore"
    }

    override suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        delegate.add(id, vector, metadata)
        invalidateCache()
    }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        delegate.addBatch(entries)
        invalidateCache()
    }

    override suspend fun addIfAllowed(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
        messageId: String,
        token: VectorStore.MessageWriteToken,
    ): Boolean {
        val added = delegate.addIfAllowed(id, vector, metadata, messageId, token)
        if (added) invalidateCache()
        return added
    }

    override suspend fun beginMessageWrite(
        messageId: String,
        replacePrevious: Boolean,
    ): VectorStore.MessageWriteToken? = delegate.beginMessageWrite(messageId, replacePrevious)

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> {
        val cacheKey =
            SearchCacheKey(
                vectorHash = queryVector.contentHashCode(),
                topK = topK,
                filters = filters,
            )
        val generationBefore = cacheGeneration.get()

        // 尝试从缓存获取
        cacheMutex.withLock {
            searchCache.get(cacheKey)?.let { cached ->
                Log.d(TAG, "Cache hit for search (hash=${cacheKey.vectorHash})")
                return cached
            }
        }

        // 缓存未命中，执行实际搜索（锁外 await 慢操作，不持锁跨 delegate）
        Log.d(TAG, "Cache miss for search (hash=${cacheKey.vectorHash})")
        val results = delegate.search(queryVector, topK, filters)

        // 回填前复查世代：await 期间发生的任何变更都已驱逐缓存并递增世代——
        // "变更前启动的检索"不得把旧结果回填进"变更后的缓存"。检查与回填同在
        // cacheMutex 内，与 invalidateCache 的"驱逐+递增"互斥，无复查-回填窗口。
        cacheMutex.withLock {
            if (cacheGeneration.get() == generationBefore) {
                searchCache.put(cacheKey, results)
            } else {
                Log.d(TAG, "Search results stale (hash=${cacheKey.vectorHash}); not cached")
            }
        }

        return results
    }

    override suspend fun get(id: String): VectorStore.VectorEntry? {
        return delegate.get(id)
    }

    override suspend fun delete(id: String) {
        delegate.delete(id)
        invalidateCache()
    }

    override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
        val count = delegate.deleteByFilters(filters)
        if (count > 0) {
            invalidateCache()
        }
        return count
    }

    override suspend fun deleteWhereMetadataDiffers(
        key: String,
        value: String,
    ): Int {
        val count = delegate.deleteWhereMetadataDiffers(key, value)
        if (count > 0) {
            invalidateCache()
        }
        return count
    }

    override suspend fun count(): Int {
        return delegate.count()
    }

    override suspend fun indexBytes(): Long = delegate.indexBytes()

    override suspend fun indexStats(): IndexStats = delegate.indexStats()

    // 闸门直接透传，不缓存：删除意图随时在变，缓存它等于让"删除后又被写回来"重新出现
    override suspend fun canIndexMessage(messageId: String): Boolean = delegate.canIndexMessage(messageId)

    override suspend fun markMessagesDeleted(messageIds: Collection<String>) {
        delegate.markMessagesDeleted(messageIds)
    }

    override suspend fun clearMessageDeleted(messageId: String) {
        delegate.clearMessageDeleted(messageId)
    }

    override suspend fun purgeSessionsNotIn(knownSessionIds: Set<String>): Int {
        val deleted = delegate.purgeSessionsNotIn(knownSessionIds)
        if (deleted > 0) {
            invalidateCache()
        }
        return deleted
    }

    // 不缓存：索引随时在变，缓存它只会让重建跳过"其实还没索引"的消息
    override suspend fun indexedMessageIds(fingerprint: String): Set<String> {
        return delegate.indexedMessageIds(fingerprint)
    }

    override suspend fun entriesSnapshot(): List<VectorStore.VectorEntry> = delegate.entriesSnapshot()

    override suspend fun clear() {
        delegate.clear()
        invalidateCache()
    }

    override suspend fun reload() {
        delegate.reload()
        invalidateCache()
    }

    override suspend fun flush(): Boolean {
        // 落盘不改变检索结果，无需失效缓存
        return delegate.flush()
    }

    override suspend fun importIndex(content: String): Boolean {
        val ok = delegate.importIndex(content)
        if (ok) {
            invalidateCache()
        }
        return ok
    }

    /**
     * 失效缓存并递增世代。
     *
     * suspend：与 [search] 的回填守卫共用 [cacheMutex]——"驱逐+递增"与"复查+回填"
     * 互斥，旧检索无法在失效之后把结果塞进新缓存。所有变更路径在返回调用方前完成本方法，
     * 调用方拿到"已删/已改"结果后，后续查询绝不读到旧缓存。
     */
    private suspend fun invalidateCache() {
        cacheMutex.withLock {
            searchCache.evictAll()
            cacheGeneration.incrementAndGet()
        }
        Log.d(TAG, "Cache invalidated")
    }

    /**
     * 搜索缓存键
     */
    private data class SearchCacheKey(
        val vectorHash: Int,
        val topK: Int,
        val filters: Map<String, Any>,
    )
}
