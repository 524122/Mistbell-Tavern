package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.repository.SessionMutationLocks
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.data.repository.buildVectorContent
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.OpenAIEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.UUID

/**
 * 结构化记忆 → 向量摘要同步的并发契约测试（instrumented，需要真机/模拟器）。
 *
 * 钉住的产品契约（锁外向量收尾的最终版本核验 + 会话锁内提交）：
 * - **逆序提交**：旧版本的 embedding 先完成也不会覆盖新版本——核验与提交在同一临界区，
 *   Room 里已是新版本则旧提交被结构性拒绝；
 * - **旧 DeleteCopy 晚到**：重要性下调产生的 DeleteCopy 在"重新升高 + 新 Sync 已提交"之后才
 *   执行时，版本核验让它放弃，不得删掉有效的新摘要；
 * - **计算期间删除**：embedding 挂起中记忆被删，提交前的锁内复查（记忆已不存在）阻止复活——
 *   这是唯一防线，因为 commitPrepared 的 replacePrevious=true 会解除删除意图闸门；
 * - **顺序更新**：同一条记忆的连续 Sync 以同一 messageId 幂等替换，索引中恰好一条且为最新内容；
 * - **删除不依赖 embedding 可用**：删除索引/登记删除意图不需要生成新向量——可用期写入摘要、
 *   关闭 embedding 后删除记忆时，available=false 不得跳过摘要清理（否则重开相同 embedding
 *   后旧摘要复活可检索；与 ChatRepositoryVectorExt 删除入口同口径，不带 available 前置）。
 *
 * 实现要点：
 * - 走生产协调路径：公开入口（createMemory/updateMemory/deleteMemory = 会话锁 + *Locked 变体 +
 *   runVectorFollowUps）与生产调用方同款；场景 b 由测试线程持有 SessionMutationLocks 锁并直接
 *   调 *Locked 变体——与 ChatRepository 的生产调用约定一致；
 * - 暂停全部用 CompletableDeferred 门闩（EmbedGate.entered 确认 embedding 已挂起、release 放行），
 *   不用 sleep；确定性时序见各测试注释；
 * - embedding 替身继承 OpenAIEmbeddingService 并覆写 embed/embedBatch：VectorMemoryService.available
 *   是类型门（只认 OpenAI/Local 实现），纯接口 fake 会被 available=false 早退挡在测试目标之外；
 * - SessionMutationLocks 是全局 object，每个测试用独立 sessionId 隔离。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest`（需连接设备）
 */
@RunWith(AndroidJUnit4::class)
class StructuredMemoryVectorSyncContractTest {
    private lateinit var db: AppDatabase
    private lateinit var appContext: android.content.Context
    private lateinit var storageFile: File
    private lateinit var store: InMemoryVectorStore
    private lateinit var embedding: GateControlledEmbeddingService
    private lateinit var repository: StructuredMemoryRepository

    private val owner = "u-conc"

    /**
     * 有界执行：整体 10s 超时，包住整个编排（含 async 子任务——它们在 withTimeout
     * 作用域内启动，超时/异常时随作用域取消，挂死在门闩上的任务不泄漏），
     * 不只依赖设备级超时。
     */
    private fun boundedRun(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(10_000) { block() } }
    }

    // ---- 可控 embedding：第 N 次 embed 调用挂起在第 N 个门闩上 ----

    private class EmbedGate(
        /** 放行后 embed 抛异常（注定失败的 embedding，测试注入） */
        val failOnRelease: Boolean = false,
    ) {
        /** embed 已挂起等待放行（测试据此获得确定性时序） */
        val entered = CompletableDeferred<Unit>()

        /** 测试放行 */
        val release = CompletableDeferred<Unit>()
    }

    /**
     * 继承生产 OpenAI 类型以通过 VectorMemoryService.available 类型门；
     * embed/embedBatch 全量覆写（无网络），按调用顺序消费测试注入的门闩。
     */
    private class GateControlledEmbeddingService :
        OpenAIEmbeddingService(
            apiKey = "test",
            baseUrl = "http://localhost/",
            model = "test",
        ) {
        private val gates = Channel<EmbedGate>(Channel.UNLIMITED)

        /** 预登记下一次 embed 调用的门闩；不调用entered永不完成（测试死锁即失败，可发现） */
        fun enqueueGate(failOnRelease: Boolean = false): EmbedGate = EmbedGate(failOnRelease).also { gates.trySend(it) }

        override val fingerprint: String get() = "test:gate-controlled:1536"

        override suspend fun embed(text: String): FloatArray {
            val gate = gates.receive()
            gate.entered.complete(Unit)
            gate.release.await()
            if (gate.failOnRelease) throw IllegalStateException("测试注入的 embedding 失败")
            return vectorFor(text)
        }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

        private fun vectorFor(text: String): FloatArray {
            val random = Random(text.hashCode().toLong())
            return FloatArray(1536) { random.nextFloat() * 2 - 1 }
        }
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        appContext = ctx
        db =
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        storageFile = File(ctx.cacheDir, "sm-vector-sync-${UUID.randomUUID()}.json")
        store = InMemoryVectorStore(storageFile)
        embedding = GateControlledEmbeddingService()
        val vectorService = VectorMemoryService(store, embedding)
        repository =
            StructuredMemoryRepository(
                ctx,
                resetGate = {},
                db = db,
                vectorMemoryService = vectorService,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 工具 ----

    private fun seedSession(sessionId: String) {
        boundedRun {
            db.sessionDao().upsert(
                SessionEntity(
                    id = sessionId,
                    ownerId = owner,
                    characterId = "c1",
                    title = "并发测试会话",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                    messageCount = 0,
                    providerId = "",
                    modelId = "",
                    worldBookId = "",
                    summaryJson = "",
                ),
            )
        }
    }

    private fun memoryDomain(
        content: String,
        sessionId: String,
        importance: Int,
    ) = StructuredMemory(
        ownerId = owner,
        characterId = "c1",
        sessionId = sessionId,
        memoryType = "fact",
        title = null,
        content = content,
        structuredData = null,
        importance = importance,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        lastAccessedAt = null,
    )

    private fun messageIdOf(memoryId: Long) = "structured_memory_$memoryId"

    /** 索引中该 messageId 的全部向量内容（按 message_id 过滤，topK 放宽到 10） */
    private suspend fun indexContents(messageId: String): List<String> =
        store.search(
            FloatArray(1536) { 0.01f },
            10,
            mapOf("message_id" to messageId),
        ).map { it.content }

    private suspend fun indexedIds(): Set<String> = store.indexedMessageIds(embedding.fingerprint)

    // ---- 场景 a：逆序提交（旧 embedding 先完成，不得覆盖新版本） ----

    @Test
    fun `逆序提交_旧版本embedding先完成_锁内签名核验拒绝_索引只有新版本`() {
        val sessionId = "s-a"
        seedSession(sessionId)

        boundedRun {
            val g1 = embedding.enqueueGate()
            // 创建 v1（importance 9 ≥ 7 → Sync 待办）→ embedding #1 挂起
            val createJob = async { repository.createMemory(memoryDomain("旧版本内容", sessionId, 9)) }
            g1.entered.await() // 确定性：Room 已写入 v1、embedding #1 已挂起

            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val v2 =
                db.structuredMemoryDao().getById(memoryId)!!.toDomain()
                    .copy(content = "新版本内容")

            // 更新 v2：Room（锁内）提交 → 新 Sync 待办 → embedding #2 挂起
            val g2 = embedding.enqueueGate()
            val updateJob = async { repository.updateMemory(v2) }
            g2.entered.await() // 确定性：Room 已是 v2、两条 embedding 都在途

            // 逆序：先放行旧版本的 embedding——它随后进会话锁做最终核验；
            // 旧 Sync 属于 createJob：核验失败即完成，随后再放行新版本
            g1.release.complete(Unit)
            createJob.await() // 旧 Sync 在锁内发现 Room 已是 v2，签名不符，放弃
            g2.release.complete(Unit)
            updateJob.await() // 新 Sync 核验 v2 一致 → 提交

            val mid = messageIdOf(memoryId)
            assertEquals("索引中该记忆只允许一个新版本", 1, indexContents(mid).size)
            assertEquals(
                "内容必须是新版本的向量摘要，旧版本不得复活/覆盖",
                listOf(v2.buildVectorContent()),
                indexContents(mid),
            )
        }
    }

    // ---- 场景 b：旧 DeleteCopy 晚到（重新升高 + 新 Sync 已提交之后） ----

    @Test
    fun `旧DeleteCopy晚到_重新升高的新Sync已提交_版本核验放弃删除_新摘要存活`() {
        val sessionId = "s-b"
        seedSession(sessionId)

        boundedRun {
            // 1) 创建 importance=9 → Sync → 放行 → 索引里有 v9 摘要
            val g1 = embedding.enqueueGate()
            val createJob = async { repository.createMemory(memoryDomain("起伏记忆", sessionId, 9)) }
            g1.entered.await()
            g1.release.complete(Unit)
            createJob.await()
            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val v9 = db.structuredMemoryDao().getById(memoryId)!!.toDomain()
            assertEquals(listOf(v9.buildVectorContent()), indexContents(messageIdOf(memoryId)))

            // 2) 测试线程持有生产会话锁（与 ChatRepository 的 Locked 变体调用约定一致）：
            //    先排 DeleteCopy（降到 5）再排 Sync（升回 9），待办都带出锁执行
            val (deleteCopy, reSync) =
                SessionMutationLocks.withSessionLock(sessionId) {
                    val fu5 = repository.updateMemoryLocked(v9.copy(importance = 5))
                    val fu9 = repository.updateMemoryLocked(v9.copy(importance = 9))
                    fu5.single() to fu9.single()
                }

            // 3) 新 Sync 先完成：embedding 挂起 → 放行 → 锁内核验（Room 正是 v9 版本）→ 提交
            val g2 = embedding.enqueueGate()
            val syncJob = async { repository.runVectorFollowUps(listOf(reSync)) }
            g2.entered.await()
            g2.release.complete(Unit)
            syncJob.await()

            // 4) 旧 DeleteCopy 晚到：它携带的是 importance=5 版本的签名，Room 已是 v9 → 放弃删除
            repository.runVectorFollowUps(listOf(deleteCopy))

            val latest = db.structuredMemoryDao().getById(memoryId)!!.toDomain()
            assertEquals(
                "晚到的旧 DeleteCopy 不得删掉重新升高后产生的新摘要",
                listOf(latest.buildVectorContent()),
                indexContents(messageIdOf(memoryId)),
            )
        }
    }

    // ---- 场景 c：计算期间删除（提交前锁内复查阻止复活） ----

    @Test
    fun `embedding挂起期间删除记忆_提交前复查记忆已不存在_索引无复活`() {
        val sessionId = "s-c"
        seedSession(sessionId)

        boundedRun {
            val g1 = embedding.enqueueGate()
            val createJob = async { repository.createMemory(memoryDomain("将被删除的记忆", sessionId, 9)) }
            g1.entered.await() // embedding 在途、记忆尚在

            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val mid = messageIdOf(memoryId)

            // 删除先行完成：Room 删除 + DeleteCopy 清索引 + 登记删除意图
            repository.deleteMemory(memoryId, owner, sessionId)
            assertTrue("删除后索引应无该摘要", indexContents(mid).isEmpty())
            assertFalse(indexedIds().contains(mid))

            // 放行在途 embedding：锁内复查 getById == null → 放弃提交
            // （唯一防线：commitPrepared 的 replacePrevious=true 会解除删除意图闸门，
            //   若不在会话锁内复查源存在性，旧向量必然落地复活）
            g1.release.complete(Unit)
            createJob.await()

            assertTrue("已删记忆的摘要不得复活", indexContents(mid).isEmpty())
            assertFalse(indexedIds().contains(mid))
        }
    }

    // ---- 场景 d：顺序更新（幂等替换，索引恰好一条且为最新） ----

    @Test
    fun `顺序更新_同一messageId恰好一条向量_内容始终最新`() {
        val sessionId = "s-d"
        seedSession(sessionId)

        boundedRun {
            suspend fun CoroutineScope.updateContent(newContent: String) {
                val memoryId =
                    db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
                val current = db.structuredMemoryDao().getById(memoryId)!!.toDomain()
                val gate = embedding.enqueueGate()
                val job = async { repository.updateMemory(current.copy(content = newContent)) }
                gate.entered.await()
                gate.release.complete(Unit)
                job.await()
            }

            val g1 = embedding.enqueueGate()
            val createJob = async { repository.createMemory(memoryDomain("版本一", sessionId, 9)) }
            g1.entered.await()
            g1.release.complete(Unit)
            createJob.await()

            updateContent("版本二")
            updateContent("版本三")

            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val finalMemory = db.structuredMemoryDao().getById(memoryId)!!.toDomain()
            val mid = messageIdOf(memoryId)

            assertEquals("同一 messageId 只许一条向量（replacePrevious 幂等）", 1, indexContents(mid).size)
            assertEquals(
                "内容必须是最新版本",
                listOf(finalMemory.buildVectorContent()),
                indexContents(mid),
            )
        }
    }

    // ---- 场景 e/f：embedding 关闭（available=false）后删除，摘要副本仍必须清理 ----
    //
    // 用户曾有可用 embedding 并写入摘要 → 后关闭 embedding/清空配置 → 删除记忆。
    // 删除索引/登记删除意图不需要生成新向量，不得带 available 前置——否则 Room 删了、
    // 摘要副本残留，重新启用相同 embedding 后旧摘要复活可检索（PromptBuilder 向量命中
    // 不回查源记忆，启动对账也清不掉活跃会话下的已删摘要）。
    // "关闭"用 MockEmbeddingService 替身（VectorMemoryService.available 的类型门对它恒 false）。

    /** 构造一个 embedding 关闭（available=false）但共享同一索引文件的 repository */
    private fun offlineRepository(): StructuredMemoryRepository =
        StructuredMemoryRepository(
            appContext,
            resetGate = {},
            db = db,
            vectorMemoryService = VectorMemoryService(store, MockEmbeddingService()),
        )

    @Test
    fun `embedding关闭后删除记忆_摘要副本仍被清理_重开store摘要不存在`() {
        val sessionId = "s-e"
        seedSession(sessionId)

        boundedRun {
            // 可用期写入摘要
            val g1 = embedding.enqueueGate()
            val createJob = async { repository.createMemory(memoryDomain("可用期摘要", sessionId, 9)) }
            g1.entered.await()
            g1.release.complete(Unit)
            createJob.await()
            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val mid = messageIdOf(memoryId)
            assertEquals(1, indexContents(mid).size)

            // 关闭 embedding 后删除：摘要副本必须照样清理（不依赖 available）
            offlineRepository().deleteMemory(memoryId, owner, sessionId)
            assertTrue("available=false 下删除必须带走摘要副本", indexContents(mid).isEmpty())
            assertFalse(indexedIds().contains(mid))

            // 重开（同一索引文件重建实例 = 重新启用相同 embedding 后可见的持久状态）：
            // 已删摘要不得存在
            store.flush()
            val reopened = InMemoryVectorStore(storageFile)
            assertTrue(
                "重开 store 后已删摘要不得复活",
                reopened.search(
                    FloatArray(1536) { 0.01f },
                    10,
                    mapOf("message_id" to mid),
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `embedding关闭后按会话删除_批量摘要副本仍被清理`() {
        val sessionId = "s-f"
        seedSession(sessionId)

        boundedRun {
            val g1 = embedding.enqueueGate()
            val createJob = async { repository.createMemory(memoryDomain("会话级摘要", sessionId, 9)) }
            g1.entered.await()
            g1.release.complete(Unit)
            createJob.await()
            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val mid = messageIdOf(memoryId)
            assertEquals(1, indexContents(mid).size)

            // deleteSummaryCopies 路径（随会话删除联动）：同样不带 available 前置
            offlineRepository().deleteMemoriesBySession(owner, sessionId)
            assertTrue("available=false 下按会话删除必须带走摘要副本", indexContents(mid).isEmpty())
            assertFalse(indexedIds().contains(mid))
        }
    }

    // ---- 场景 g/h/i：内容已变但新向量无法生成（离线/抛错）时，旧摘要必须失效 ----
    //
    // 生产协议：Sync 待办的 prepareSync 在 available=false 或 embedding 抛错时不再静默
    // 放弃，而是降级为"版本校验的副本失效"（deleteVectorCopyIfVersionCurrent）——
    // Room 当前签名 == 本待办快照签名（本待办仍是当前版本）才删副本；面对更新版本则放弃。

    @Test
    fun `embedding关闭时编辑高重要度记忆_旧摘要失效_重开store不复活`() {
        val sessionId = "s-g"
        seedSession(sessionId)

        boundedRun {
            // 在线：写入 v1 摘要
            val g1 = embedding.enqueueGate()
            val createJob = async { repository.createMemory(memoryDomain("旧内容", sessionId, 9)) }
            g1.entered.await()
            g1.release.complete(Unit)
            createJob.await()
            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val mid = messageIdOf(memoryId)
            assertEquals(1, indexContents(mid).size)

            // 离线编辑（available=false）：Room 已是新内容；Sync 待办降级为版本校验失效——
            // 本待办仍是当前版本（签名一致）→ 旧摘要被删，不依赖 embedding 可用
            val current = db.structuredMemoryDao().getById(memoryId)!!.toDomain()
            offlineRepository().updateMemory(current.copy(content = "新内容"))
            assertTrue("离线编辑后旧摘要必须失效（宁缺毋错，Room 权威）", indexContents(mid).isEmpty())
            assertFalse(indexedIds().contains(mid))

            // 重开相同 embedding（同一索引文件重建）：旧内容不得复活可召回
            store.flush()
            val reopened = InMemoryVectorStore(storageFile)
            assertTrue(
                "重开 store 后旧摘要不得复活",
                reopened.search(
                    FloatArray(1536) { 0.01f },
                    10,
                    mapOf("message_id" to mid),
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `embedding抛错时编辑高重要度记忆_旧摘要同样失效`() {
        val sessionId = "s-h"
        seedSession(sessionId)

        boundedRun {
            val g1 = embedding.enqueueGate()
            val createJob = async { repository.createMemory(memoryDomain("旧内容", sessionId, 9)) }
            g1.entered.await()
            g1.release.complete(Unit)
            createJob.await()
            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val mid = messageIdOf(memoryId)
            assertEquals(1, indexContents(mid).size)

            // embedding 注定失败（放行即抛错）：与离线同口径——旧副本失效
            val current = db.structuredMemoryDao().getById(memoryId)!!.toDomain()
            val g2 = embedding.enqueueGate(failOnRelease = true)
            val updateJob = async { repository.updateMemory(current.copy(content = "新内容")) }
            g2.entered.await()
            g2.release.complete(Unit)
            updateJob.await()

            assertTrue("embedding 抛错后旧摘要必须失效", indexContents(mid).isEmpty())
            assertFalse(indexedIds().contains(mid))
        }
    }

    @Test
    fun `晚到的失败旧Sync_版本签名面对已落地的新版本不符_不得误删新摘要`() {
        val sessionId = "s-i"
        seedSession(sessionId)

        boundedRun {
            // v1 Sync：embedding 挂起且注定失败
            val g1 = embedding.enqueueGate(failOnRelease = true)
            val createJob = async { repository.createMemory(memoryDomain("版本一", sessionId, 9)) }
            g1.entered.await()
            val memoryId =
                db.structuredMemoryDao().getByOwnerAndSession(owner, sessionId).single().id
            val v2 =
                db.structuredMemoryDao().getById(memoryId)!!.toDomain()
                    .copy(content = "版本二")

            // v2 更新：Room 提交 + 新 Sync 挂起（embedding 在途）
            val g2 = embedding.enqueueGate()
            val updateJob = async { repository.updateMemory(v2) }
            g2.entered.await()

            // 先让 v2 完成：新摘要真实落地——g1 之后面对的才是"已存在的新版本摘要"
            g2.release.complete(Unit)
            updateJob.await()
            val latest = db.structuredMemoryDao().getById(memoryId)!!.toDomain()
            assertEquals(
                "v2 的新摘要应先落地（前置条件）",
                listOf(latest.buildVectorContent()),
                indexContents(messageIdOf(memoryId)),
            )

            // 再放行 v1（失败）：降级失效的版本核验读到 Room 已是 v2，签名 ≠ v1 快照 → 放弃删除
            g1.release.complete(Unit)
            createJob.await()

            assertEquals(
                "晚到的失败旧 Sync 不得误删已存在的新版本有效摘要",
                listOf(latest.buildVectorContent()),
                indexContents(messageIdOf(memoryId)),
            )
        }
    }
}
