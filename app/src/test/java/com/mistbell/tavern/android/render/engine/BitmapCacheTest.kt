package com.mistbell.tavern.android.render.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** BitmapCache 的 JVM 单测：LRU 淘汰按字节计费、命中提升、并发 put/get 不炸。 */
class BitmapCacheTest {
    private fun key(id: Int) = BitmapCacheKey(contentHash = id, varsVersion = 0L, widthDp = 100, density = 3)

    @Test
    fun `超预算淘汰最久未用条目`() {
        val cache = BitmapCache<BitmapCacheKey, String>(maxBytes = 100, sizeOf = { it.length })
        cache.put(key(1), "A".repeat(60))
        cache.put(key(2), "B".repeat(50))
        assertNull(cache.get(key(1)))
        assertNotNull(cache.get(key(2)))
        assertEquals(50L, cache.billedBytes())
        assertEquals(1, cache.entryCount())
    }

    @Test
    fun `命中提升避免被淘汰`() {
        val cache = BitmapCache<BitmapCacheKey, String>(maxBytes = 100, sizeOf = { it.length })
        cache.put(key(1), "A".repeat(40))
        cache.put(key(2), "B".repeat(40))
        // 访问 A 使其变为最近使用，随后 C 插入时应淘汰 B
        cache.get(key(1))
        cache.put(key(3), "C".repeat(40))
        assertNotNull(cache.get(key(1)))
        assertNull(cache.get(key(2)))
        assertNotNull(cache.get(key(3)))
    }

    @Test
    fun `新条目自身超预算被淘汰`() {
        val cache = BitmapCache<BitmapCacheKey, String>(maxBytes = 100, sizeOf = { it.length })
        cache.put(key(1), "X".repeat(200))
        assertEquals(0, cache.entryCount())
        assertEquals(0L, cache.billedBytes())
    }

    @Test
    fun `更新已存在条目计费按新值`() {
        val cache = BitmapCache<BitmapCacheKey, String>(maxBytes = 100, sizeOf = { it.length })
        cache.put(key(1), "A".repeat(60))
        cache.put(key(1), "A".repeat(30))
        assertEquals(1, cache.entryCount())
        assertEquals(30L, cache.billedBytes())
    }

    @Test
    fun `clear 清空计费`() {
        val cache = BitmapCache<BitmapCacheKey, String>(maxBytes = 100, sizeOf = { it.length })
        cache.put(key(1), "A".repeat(60))
        cache.clear()
        assertEquals(0, cache.entryCount())
        assertEquals(0L, cache.billedBytes())
        assertNull(cache.get(key(1)))
    }

    @Test
    fun `并发 put get 不炸且预算不破`() {
        val cache =
            BitmapCache<BitmapCacheKey, ByteArray>(
                maxBytes = 1 shl 20,
                sizeOf = { it.size },
            )
        val threads = 8
        val opsPerThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { threadId ->
            pool.submit {
                ready.countDown()
                try {
                    ready.await()
                    repeat(opsPerThread) { op ->
                        cache.put(key(threadId * 1000 + op), ByteArray(256))
                        cache.get(key((threadId * 7919 + op) % (threads * 1000)))
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("并发任务未在时限内完成", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("缓存条目数超出预算约束", cache.billedBytes() <= 1 shl 20)
    }
}
