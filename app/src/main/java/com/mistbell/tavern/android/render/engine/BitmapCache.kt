package com.mistbell.tavern.android.render.engine

/**
 * 位图 LRU 缓存（M3）：自写 LinkedHashMap 实现（**不用 android.util.LruCache**，保持本类可 JVM
 * 单测），按条目字节计费淘汰，线程安全（synchronize）。
 *
 * 共享实例（EngineBootstrap.bitmapCache）**只允许在引擎渲染调度器（EngineBootstrap.renderDispatcher）
 * 上访问**：方法级 synchronize 只保护 map 一致性，不保证跨渲染的可见顺序，共享复用必须串行。
 *
 * key 语义（PanelRenderer 使用）：(内容 hash, 变量版本, 宽度, 密度)；本类对 key 类型无假设。
 * 泛型 V 的字节计费由 [sizeOf] 注入（引擎出口处传 Bitmap.byteCount）。
 */
class BitmapCache<K : Any, V : Any>(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val sizeOf: (V) -> Int,
) {
    private val map = LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    private var totalBytes = 0L

    /** 命中并提升为最近使用 */
    @Synchronized
    fun get(key: K): V? = map[key]

    /** 插入/更新；超出 [maxBytes] 时从最久未用端淘汰（新条目本身超限时也会被淘汰） */
    @Synchronized
    fun put(
        key: K,
        value: V,
    ) {
        map[key]?.let { existing -> totalBytes -= sizeOf(existing).coerceAtLeast(0).toLong() }
        map[key] = value
        totalBytes += sizeOf(value).coerceAtLeast(0).toLong()
        evictUntilWithinBudget()
    }

    @Synchronized
    fun clear() {
        map.clear()
        totalBytes = 0
    }

    /** 当前缓存条目数（测试/诊断用） */
    @Synchronized
    fun entryCount(): Int = map.size

    /** 当前计费字节（测试/诊断用） */
    @Synchronized
    fun billedBytes(): Long = totalBytes

    private fun evictUntilWithinBudget() {
        val iterator = map.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= sizeOf(eldest.value).coerceAtLeast(0).toLong()
            iterator.remove()
        }
    }

    companion object {
        /** 默认预算：24MB（单条 600x400 ARGB 位图约 1MB，覆盖滚动窗口的常用面板） */
        const val DEFAULT_MAX_BYTES = 24L * 1024 * 1024

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}

/** 位图缓存 key：内容 hash + 变量版本 + 宽度/密度（设计文档 §2.2 四元组） */
data class BitmapCacheKey(
    val contentHash: Int,
    val varsVersion: Long,
    /** 宽度（dp 取整） */
    val widthDp: Int,
    /** 密度（取整；同一设备内恒定，跨设备不共享缓存） */
    val density: Int,
)
