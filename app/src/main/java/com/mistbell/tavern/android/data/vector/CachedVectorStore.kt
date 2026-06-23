package com.mistbell.tavern.android.data.vector

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 带缓存的向量存储装饰器
 *
 * 优化策略：
 * - 缓存搜索结果（基于查询向量哈希 + 过滤条件）
 * - LRU 缓存策略，避免内存过度占用
 * - 写操作自动清除相关缓存
 */
class CachedVectorStore(
    private val delegate: VectorStore,
    cacheSize: Int = 50
) : VectorStore {

    private val searchCache = LruCache<SearchCacheKey, List<VectorStore.SearchResult>>(cacheSize)
    private val cacheMutex = Mutex()

    companion object {
        private const val TAG = "CachedVectorStore"
    }

    override suspend fun add(id: String, vector: FloatArray, metadata: Map<String, Any>) {
        delegate.add(id, vector, metadata)
        invalidateCache()
    }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        delegate.addBatch(entries)
        invalidateCache()
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>
    ): List<VectorStore.SearchResult> {
        val cacheKey = SearchCacheKey(
            vectorHash = queryVector.contentHashCode(),
            topK = topK,
            filters = filters
        )

        // 尝试从缓存获取
        cacheMutex.withLock {
            searchCache.get(cacheKey)?.let { cached ->
                Log.d(TAG, "Cache hit for search (hash=${cacheKey.vectorHash})")
                return cached
            }
        }

        // 缓存未命中，执行实际搜索
        Log.d(TAG, "Cache miss for search (hash=${cacheKey.vectorHash})")
        val results = delegate.search(queryVector, topK, filters)

        // 缓存结果
        cacheMutex.withLock {
            searchCache.put(cacheKey, results)
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

    override suspend fun count(): Int {
        return delegate.count()
    }

    override suspend fun clear() {
        delegate.clear()
        invalidateCache()
    }

    private fun invalidateCache() {
        searchCache.evictAll()
        Log.d(TAG, "Cache invalidated")
    }

    /**
     * 搜索缓存键
     */
    private data class SearchCacheKey(
        val vectorHash: Int,
        val topK: Int,
        val filters: Map<String, Any>
    )
}
