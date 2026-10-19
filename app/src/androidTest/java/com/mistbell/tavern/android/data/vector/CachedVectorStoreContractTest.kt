package com.mistbell.tavern.android.data.vector

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CachedVectorStore 的缓存 generation 契约测试（instrumented，需要真机/模拟器）。
 *
 * 必须 instrumented 的原因：缓存底层是 android.util.LruCache，JVM 单测里是空桩，
 * 证明不了缓存行为。
 *
 * 钉住的产品契约（删除/更新后不再召回旧值）：
 * - **核心时序**：search 在锁外 await delegate.search，期间删除完成（缓存失效 + 代递增）——
 *   该 search 的旧结果照常返回给在途调用方，但**不得回填缓存**；再次相同查询必须打到
 *   delegate 拿新值，不得命中回填的旧结果；
 * - 同身份 replacePrevious 写入（update）后查询必须新值；
 * - clear / importIndex 后查询不得命中旧缓存；
 * - 无变更时缓存确实命中（delegate 检索计数不增）——缓存仍然有效、不是被协议禁用。
 *
 * generation 协议（生产实现，见 CachedVectorStore 类头）：search 在 await delegate 前读
 * 世代、回填前在 cacheMutex 内复查，不一致则不缓存；invalidateCache 在同一锁内"驱逐+递增"，
 * 两边互斥——"变更前启动的检索"永远无法污染"变更后的缓存"。
 *
 * 确定性时序：fake delegate 的 search 挂起在 EmbedGate 门闩（entered=已挂起、release=放行），
 * 变更与放行顺序由测试显式编排，无 sleep。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest`（需连接设备）
 */
@RunWith(AndroidJUnit4::class)
class CachedVectorStoreContractTest {
    private lateinit var delegate: ProgrammableVectorStore
    private lateinit var cached: CachedVectorStore

    /**
     * 有界执行：整体 10s 超时。withTimeout 超时即失败并以取消传播收口——
     * 挂起在门闩上的 async 子任务随作用域取消，不泄漏、不只依赖设备级超时。
     */
    private fun boundedRun(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(10_000) { block() } }
    }

    // ---- 可编程 delegate：检索结果可换、调用计数、search 可门闩挂起 ----

    private class SearchGate {
        /** delegate.search 已进入挂起 */
        val entered = CompletableDeferred<Unit>()

        /** 测试放行 */
        val release = CompletableDeferred<Unit>()
    }

    private class ProgrammableVectorStore : VectorStore {
        private val mutex = Mutex()
        private var currentResults: List<VectorStore.SearchResult> = emptyList()
        private val vectors = mutableMapOf<String, VectorStore.VectorEntry>()
        private val gates = Channel<SearchGate>(Channel.UNLIMITED)

        /** delegate.search 累计调用次数（缓存命中的判据） */
        var searchCount = 0
            private set

        fun setSearchResults(results: List<VectorStore.SearchResult>) {
            runBlocking { mutex.withLock { currentResults = results } }
        }

        /** 预登记下一次 delegate.search 的门闩 */
        fun enqueueGate(): SearchGate = SearchGate().also { gates.trySend(it) }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            filters: Map<String, Any>,
        ): List<VectorStore.SearchResult> {
            searchCount++
            // 先取不可变快照再挂起：这是"拿到旧快照后暂停"的生产时序——
            // 放行后必须返回快照（旧值），而不是读取已变更的 currentResults
            val snapshot = mutex.withLock { currentResults.toList() }
            gates.tryReceive().getOrNull()?.let { gate ->
                gate.entered.complete(Unit)
                gate.release.await()
            }
            return snapshot
        }

        override suspend fun add(
            id: String,
            vector: FloatArray,
            metadata: Map<String, Any>,
        ) {
            mutex.withLock { vectors[id] = VectorStore.VectorEntry(id, vector, metadata) }
        }

        override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
            mutex.withLock { entries.forEach { vectors[it.id] = it } }
        }

        override suspend fun addIfAllowed(
            id: String,
            vector: FloatArray,
            metadata: Map<String, Any>,
            messageId: String,
            token: VectorStore.MessageWriteToken,
        ): Boolean {
            add(id, vector, metadata)
            return true
        }

        override suspend fun beginMessageWrite(
            messageId: String,
            replacePrevious: Boolean,
        ): VectorStore.MessageWriteToken = VectorStore.MessageWriteToken(messageId, 0, replacePrevious)

        override suspend fun get(id: String): VectorStore.VectorEntry? = mutex.withLock { vectors[id] }

        override suspend fun delete(id: String) {
            mutex.withLock { vectors.remove(id) }
        }

        override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
            val before = vectors.size
            mutex.withLock {
                vectors.entries.removeAll { (_, e) -> filters.all { (k, v) -> e.metadata[k] == v } }
            }
            return before - vectors.size
        }

        override suspend fun deleteWhereMetadataDiffers(
            key: String,
            value: String,
        ): Int = 0

        override suspend fun count(): Int = vectors.size

        override suspend fun indexBytes(): Long = 0

        override suspend fun indexStats(): IndexStats = IndexStats(count(), 0, 0)

        override suspend fun canIndexMessage(messageId: String): Boolean = true

        override suspend fun markMessagesDeleted(messageIds: Collection<String>) {}

        override suspend fun clearMessageDeleted(messageId: String) {}

        override suspend fun purgeSessionsNotIn(knownSessionIds: Set<String>): Int = 0

        override suspend fun indexedMessageIds(fingerprint: String): Set<String> =
            vectors.values.mapNotNull { it.metadata["message_id"] as? String }.toSet()

        override suspend fun entriesSnapshot(): List<VectorStore.VectorEntry> = vectors.values.toList()

        override suspend fun clear() {
            mutex.withLock {
                vectors.clear()
                currentResults = emptyList()
            }
        }

        override suspend fun reload() {}

        override suspend fun flush(): Boolean = true

        override suspend fun importIndex(content: String): Boolean {
            // 模拟"索引被整体替换"：后续检索只返回新快照
            mutex.withLock { currentResults = listOf(newSnapshot) }
            return true
        }

        companion object {
            val newSnapshot =
                VectorStore.SearchResult(
                    id = "vec-imported",
                    score = 0.8f,
                    content = "导入后的新索引",
                    metadata = mapOf("message_id" to "m-imported"),
                )
        }
    }

    private val query = FloatArray(4) { 0.1f }
    private val filters = mapOf<String, Any>("owner_id" to "u1")

    private fun oldResult() =
        VectorStore.SearchResult(
            id = "vec-old",
            score = 0.9f,
            content = "旧记忆",
            metadata = mapOf("message_id" to "m1"),
        )

    private fun newResult() =
        VectorStore.SearchResult(
            id = "vec-new",
            score = 0.9f,
            content = "新记忆",
            metadata = mapOf("message_id" to "m1"),
        )

    private suspend fun search(): List<VectorStore.SearchResult> = cached.search(query, 5, filters)

    @Before
    fun setUp() {
        delegate = ProgrammableVectorStore()
        cached = CachedVectorStore(delegate)
    }

    @Test
    fun `核心时序_检索在途时删除完成_旧结果不回填_再次查询必须打到delegate拿新值`() {
        boundedRun {
            delegate.setSearchResults(listOf(oldResult()))
            val gate = delegate.enqueueGate()

            // 1) search 开始：delegate 进入即冻结旧快照，随后门闩挂起
            val inFlight = async { search() }
            gate.entered.await()

            // 2) 删除完成：delegate 新状态（空）+ 装饰器缓存失效与代递增都在 delete 返回前完成
            delegate.setSearchResults(emptyList())
            cached.delete("vec-old")

            // 3) 放行在途检索：它拿到的是进入时冻结的旧快照（生产时序），
            //    照常返回给在途调用方，但 generation 已变、不得回填缓存
            gate.release.complete(Unit)
            val stale = inFlight.await()
            assertEquals("在途调用方拿到启动时的结果（协议不保证新旧）", listOf(oldResult()), stale)

            // 4) 再次相同查询：必须缓存未命中、打到 delegate 拿新值
            val fresh = search()
            assertTrue("删除后查询不得召回旧记忆", fresh.isEmpty())
            assertEquals(
                "旧结果不得回填：第二次查询必须真正打到 delegate",
                2,
                delegate.searchCount,
            )
        }
    }

    @Test
    fun `同身份replacePrevious写入后_查询必须拿到新值`() {
        boundedRun {
            delegate.setSearchResults(listOf(oldResult()))
            assertEquals(listOf(oldResult()), search())
            assertEquals(1, delegate.searchCount)

            // update：同 messageId 的 replacePrevious 写入（生产 VectorMemoryService.commitPrepared 路径）
            delegate.setSearchResults(listOf(newResult()))
            val token = cached.beginMessageWrite("m1", replacePrevious = true)!!
            assertTrue(
                cached.addIfAllowed("vec-new", query, mapOf("message_id" to "m1"), "m1", token),
            )

            val got = search()
            assertEquals("update 后查询必须新值", listOf(newResult()), got)
            assertEquals("更新不得让旧缓存存活", 2, delegate.searchCount)
        }
    }

    @Test
    fun `clear后查询不得命中旧缓存`() {
        boundedRun {
            delegate.setSearchResults(listOf(oldResult()))
            assertEquals(listOf(oldResult()), search())

            cached.clear() // delegate 清空 + 缓存失效/代递增

            val got = search()
            assertTrue("clear 后不得召回旧值", got.isEmpty())
            assertEquals(2, delegate.searchCount)
        }
    }

    @Test
    fun `importIndex后查询不得命中旧缓存`() {
        boundedRun {
            delegate.setSearchResults(listOf(oldResult()))
            assertEquals(listOf(oldResult()), search())

            assertTrue(cached.importIndex("{\"replaced\":true}"))

            val got = search()
            assertEquals("importIndex 后必须拿到替换后的新索引", listOf(ProgrammableVectorStore.newSnapshot), got)
            assertEquals(2, delegate.searchCount)
        }
    }

    @Test
    fun `无变更时缓存确实命中_delegate检索计数不增`() {
        boundedRun {
            delegate.setSearchResults(listOf(oldResult()))

            val first = search()
            val second = search()
            val third = search()

            assertEquals(listOf(oldResult()), first)
            assertEquals(listOf(oldResult()), second)
            assertEquals(listOf(oldResult()), third)
            assertEquals(
                "无变更时三次相同查询只打一次 delegate（缓存有效，非被协议禁用）",
                1,
                delegate.searchCount,
            )
        }
    }
}
