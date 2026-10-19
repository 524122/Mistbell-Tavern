package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.repository.RecallWindow
import com.mistbell.tavern.android.data.repository.SessionMutationLocks
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.data.repository.StructuredMemoryRestoreMerger
import com.mistbell.tavern.android.data.repository.VectorIndexRebuilder
import com.mistbell.tavern.android.data.repository.VectorRecallValidator
import com.mistbell.tavern.android.data.repository.VectorSyncRecovery
import com.mistbell.tavern.android.data.repository.buildVectorContent
import com.mistbell.tavern.android.data.vector.CachedVectorStore
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.OpenAIEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VectorSyncRecoveryContractTest {
    private lateinit var context: android.content.Context
    private lateinit var dbName: String
    private lateinit var indexFile: File
    private lateinit var runtime: TestRuntime
    private var clock = 1_000L
    private lateinit var oldResetGate: suspend () -> Unit
    private lateinit var oldVectorProvider: () -> VectorMemoryService

    private val owner = "owner-recovery"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        dbName = "vector-recovery-$suffix.db"
        indexFile = File(context.cacheDir, "vector-recovery-$suffix.json")
        oldResetGate = PromptBuilder.memoryResetGate
        oldVectorProvider = PromptBuilder.vectorMemoryServiceProvider
        PromptBuilder.memoryResetGate = {}
    }

    @After
    fun tearDown() {
        if (::runtime.isInitialized) runtime.db.close()
        context.deleteDatabase(dbName)
        indexFile.delete()
        File(indexFile.path + ".tmp").delete()
        PromptBuilder.memoryResetGate = oldResetGate
        PromptBuilder.vectorMemoryServiceProvider = oldVectorProvider
    }

    private fun boundedRun(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(20_000) { block() } }
    }

    private fun newRuntime(
        embedding: EmbeddingService = GateEmbedding(),
        afterFlushBeforeAck: suspend (Long) -> Unit = {},
    ): TestRuntime {
        val db =
            Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addCallback(AppDatabase.vectorSyncCallback)
                .allowMainThreadQueries()
                .build()
        val base = InMemoryVectorStore(indexFile, maxVectorsInMemory = 0, flushDelayMs = 60_000)
        val cached = CachedVectorStore(base)
        val service = VectorMemoryService(cached, embedding, VectorRecallValidator(db)::filterValid)
        return TestRuntime(
            db = db,
            baseStore = base,
            embedding = embedding,
            service = service,
            recovery = VectorSyncRecovery(db, service, { clock }, afterFlushBeforeAck),
            repository = StructuredMemoryRepository(context, resetGate = {}, db = db, vectorMemoryService = service),
        )
    }

    private fun restartRuntime(): TestRuntime {
        val fingerprint = runtime.embedding.fingerprint
        runtime.db.close()
        runtime = newRuntime(GateEmbedding(fingerprint))
        return runtime
    }

    private suspend fun seedSession(
        sessionId: String,
        ownerId: String = owner,
    ) {
        runtime.db.sessionDao().upsert(
            SessionEntity(
                id = sessionId,
                ownerId = ownerId,
                characterId = "character-1",
                title = "恢复测试",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
                messageCount = 0,
                providerId = "",
                modelId = "",
                worldBookId = "",
                summaryJson = "",
                enableLongTermMemory = true,
                contextTokenLimit = 256,
            ),
        )
    }

    private fun memory(
        content: String,
        sessionId: String,
        importance: Int = 9,
        ownerId: String = owner,
    ) = StructuredMemory(
        ownerId = ownerId,
        characterId = "character-1",
        sessionId = sessionId,
        memoryType = "fact",
        title = "合成事实",
        content = content,
        structuredData = null,
        importance = importance,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        lastAccessedAt = null,
    )

    private suspend fun createLocked(memory: StructuredMemory): Pair<Long, List<StructuredMemoryRepository.VectorFollowUp>> =
        SessionMutationLocks.withSessionLock(requireNotNull(memory.sessionId)) {
            runtime.repository.createMemoryLocked(memory)
        }

    private suspend fun indexContents(messageId: String): List<String> =
        runtime.baseStore.search(FloatArray(DIMENSION) { 0.01f }, 20, mapOf("message_id" to messageId))
            .map { it.content }

    private suspend fun latestOperationId(): Long = runtime.db.vectorSyncOutboxDao().getAll().last().operationId

    @Test
    fun `创建提交后中断_重建运行时后补建摘要`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-create")
            val (memoryId, _) = createLocked(memory("创建后尚未向量化的合成事实", "s-create"))
            assertEquals(1, runtime.db.vectorSyncOutboxDao().count())
            assertTrue(indexContents("structured_memory_$memoryId").isEmpty())

            restartRuntime()
            runtime.recovery.recoverDue()

            val expected = requireNotNull(runtime.db.structuredMemoryDao().getById(memoryId)).toDomain().buildVectorContent()
            assertEquals(listOf(expected), indexContents("structured_memory_$memoryId"))
            assertEquals(0, runtime.db.vectorSyncOutboxDao().count())
            restartRuntime()
            assertEquals(listOf(expected), indexContents("structured_memory_$memoryId"))
        }

    @Test
    fun `编辑提交后中断_缓存与最终prompt不召回旧摘要_恢复后只有新版本`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-edit")
            val (memoryId, createFollowUp) = createLocked(memory("旧版本秘密-7f3a", "s-edit"))
            runtime.repository.runVectorFollowUps(createFollowUp)
            assertEquals(1, indexContents("structured_memory_$memoryId").size)

            val current = requireNotNull(runtime.db.structuredMemoryDao().getById(memoryId)).toDomain()
            val updateFollowUps =
                SessionMutationLocks.withSessionLock("s-edit") {
                    runtime.repository.updateMemoryLocked(current.copy(content = "新版本秘密-9c2b"))
                }
            assertTrue(updateFollowUps.isNotEmpty())
            val staleRecall =
                runtime.service.searchRelevantMemories(
                    "旧版本秘密",
                    RecallWindow.resolve(
                        owner,
                        "character-1",
                        "s-edit",
                        com.mistbell.tavern.android.data.repository.MemoryScope.SESSION,
                        emptyList(),
                    ),
                )
            assertTrue(staleRecall.results.isEmpty())

            PromptBuilder.vectorMemoryServiceProvider = { runtime.service }
            repeat(12) { index ->
                runtime.db.messageDao().upsert(message("history-$index", "s-edit", "历史填充内容 $index"))
            }
            val prompt =
                PromptBuilder.buildPrompt(
                    runtime.db,
                    owner,
                    "character-1",
                    "s-edit",
                    "请回忆旧版本秘密",
                    contextTokenLimitOverride = 64,
                )
            assertFalse(prompt.joinToString("\n") { it.content }.contains("旧版本秘密-7f3a"))

            restartRuntime()
            runtime.recovery.recoverSource("summary", owner, "s-edit", memoryId.toString())
            val latest = requireNotNull(runtime.db.structuredMemoryDao().getById(memoryId)).toDomain()
            assertEquals(listOf(latest.buildVectorContent()), indexContents("structured_memory_$memoryId"))
            assertEquals(0, runtime.db.vectorSyncOutboxDao().dueForSource("summary", owner, "s-edit", memoryId.toString(), clock).size)
        }

    @Test
    fun `删除提交后中断_重启先屏蔽再完成清理`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-delete")
            val (memoryId, createFollowUp) = createLocked(memory("删除后不得复活-51d2", "s-delete"))
            runtime.repository.runVectorFollowUps(createFollowUp)
            SessionMutationLocks.withSessionLock("s-delete") {
                runtime.repository.deleteMemoryLocked(memoryId, owner, "s-delete")
            }

            restartRuntime()
            val outcome =
                runtime.service.searchRelevantMemories(
                    "删除后不得复活",
                    RecallWindow.resolve(
                        owner,
                        "character-1",
                        "s-delete",
                        com.mistbell.tavern.android.data.repository.MemoryScope.SESSION,
                        emptyList(),
                    ),
                )
            assertTrue(outcome.results.isEmpty())
            runtime.recovery.recoverDue()
            assertTrue(indexContents("structured_memory_$memoryId").isEmpty())
            assertEquals(0, runtime.db.vectorSyncOutboxDao().count())
            restartRuntime()
            assertTrue(indexContents("structured_memory_$memoryId").isEmpty())
        }

    @Test
    fun `索引落盘后待办未确认中断_重放幂等`() =
        boundedRun {
            var crashes = AtomicInteger(0)
            runtime =
                newRuntime(
                    afterFlushBeforeAck = {
                        crashes.incrementAndGet()
                        throw CancellationException("模拟索引落盘后的进程终止")
                    },
                )
            seedSession("s-acked")
            val (memoryId, _) = createLocked(memory("已落盘未确认-8ab1", "s-acked"))
            val operationId = latestOperationId()
            val job = async { runCatching { runtime.recovery.processNow(operationId) } }
            job.await()
            assertEquals(1, crashes.get())
            assertEquals(1, runtime.db.vectorSyncOutboxDao().count())
            assertEquals(1, indexContents("structured_memory_$memoryId").size)

            restartRuntime()
            runtime.recovery.processNow(operationId)
            runtime.recovery.processNow(operationId)
            assertEquals(1, indexContents("structured_memory_$memoryId").size)
            assertEquals(0, runtime.db.vectorSyncOutboxDao().count())
        }

    @Test
    fun `向量flush失败不确认_解除故障后重试成功`() =
        boundedRun {
            val blocker = File(context.cacheDir, "vector-blocker-${UUID.randomUUID()}")
            blocker.writeText("not a directory")
            indexFile = File(blocker, "vector.json")
            runtime = newRuntime()
            seedSession("s-flush")
            val (memoryId, _) = createLocked(memory("落盘失败恢复-63c0", "s-flush"))
            val operationId = latestOperationId()

            assertEquals(VectorSyncRecovery.Outcome.RETRY_SCHEDULED, runtime.recovery.processNow(operationId))
            assertEquals(1, runtime.db.vectorSyncOutboxDao().count())

            runtime.db.close()
            blocker.delete()
            blocker.mkdirs()
            clock += 10 * 60_000L
            runtime = newRuntime()
            runtime.recovery.recoverDue()
            assertEquals(1, indexContents("structured_memory_$memoryId").size)
            assertEquals(0, runtime.db.vectorSyncOutboxDao().count())
        }

    @Test
    fun `离线编辑保留待办_恢复配置与切换模型不混用向量`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-offline")
            val (memoryId, createFollowUp) = createLocked(memory("离线前版本-42dd", "s-offline"))
            runtime.repository.runVectorFollowUps(createFollowUp)
            val current = requireNotNull(runtime.db.structuredMemoryDao().getById(memoryId)).toDomain()
            SessionMutationLocks.withSessionLock("s-offline") {
                runtime.repository.updateMemoryLocked(current.copy(content = "离线后版本-84ee"))
            }

            val offlineDb = runtime.db
            val offlineService =
                VectorMemoryService(
                    CachedVectorStore(runtime.baseStore),
                    MockEmbeddingService(),
                    VectorRecallValidator(offlineDb)::filterValid,
                )
            VectorSyncRecovery(offlineDb, offlineService, { clock }).recoverDue()
            assertTrue(indexContents("structured_memory_$memoryId").isEmpty())
            assertTrue(runtime.db.vectorSyncOutboxDao().count() > 0)

            clock += 10 * 60_000L
            restartRuntime()
            runtime.recovery.recoverDue()
            assertEquals("离线后版本-84ee [fact]", indexContents("structured_memory_$memoryId").single().substringAfter("合成事实: "))

            runtime.db.close()
            runtime = newRuntime(GateEmbedding("test:model-b:$DIMENSION"))
            val beforeRebuild =
                runtime.service.searchRelevantMemories(
                    "离线后版本",
                    RecallWindow.resolve(
                        owner,
                        "character-1",
                        "s-offline",
                        com.mistbell.tavern.android.data.repository.MemoryScope.SESSION,
                        emptyList(),
                    ),
                )
            assertTrue(beforeRebuild.results.isEmpty())
            VectorIndexRebuilder.forDatabase(runtime.db, runtime.service).rebuild(owner)
            val afterRebuild =
                runtime.service.searchRelevantMemories(
                    "离线后版本",
                    RecallWindow.resolve(
                        owner,
                        "character-1",
                        "s-offline",
                        com.mistbell.tavern.android.data.repository.MemoryScope.SESSION,
                        emptyList(),
                    ),
                )
            assertTrue(afterRebuild.results.isNotEmpty())
            assertTrue(afterRebuild.results.all { it.metadata["embedding_fingerprint"] == "test:model-b:$DIMENSION" })
        }

    @Test
    fun `批量删除与owner会话隔离_没有孤儿或跨范围误删`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-a")
            seedSession("s-b")
            seedSession("s-other", "owner-other")
            val (a1, _) = createLocked(memory("会话A记忆", "s-a"))
            val (b1, _) = createLocked(memory("会话B记忆", "s-b"))
            val (other, _) = createLocked(memory("其他owner记忆", "s-other", ownerId = "owner-other"))
            runtime.recovery.recoverDue()

            runtime.db.withTransaction {
                runtime.db.structuredMemoryDao().deleteBySession(owner, "s-a")
                runtime.db.sessionDao().delete("s-a", owner, "character-1")
            }
            restartRuntime()
            runtime.recovery.recoverDue()

            assertTrue(indexContents("structured_memory_$a1").isEmpty())
            assertEquals(1, indexContents("structured_memory_$b1").size)
            assertEquals(1, indexContents("structured_memory_$other").size)
            val otherRecall =
                runtime.service.searchRelevantMemories(
                    "会话A记忆",
                    RecallWindow.resolve(
                        "owner-other",
                        "character-1",
                        "s-other",
                        com.mistbell.tavern.android.data.repository.MemoryScope.SESSION,
                        emptyList(),
                    ),
                )
            assertTrue(otherRecall.results.none { it.content.contains("会话A记忆") })
        }

    @Test
    fun `最新删除确认会收敛旧待办但不误删其他会话`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-ack-a")
            seedSession("s-ack-b")
            val (memoryId, createFollowUp) = createLocked(memory("旧离线版本", "s-ack-a"))
            runtime.repository.runVectorFollowUps(createFollowUp)
            val current = requireNotNull(runtime.db.structuredMemoryDao().getById(memoryId)).toDomain()
            val updateFollowUps =
                SessionMutationLocks.withSessionLock("s-ack-a") {
                    runtime.repository.updateMemoryLocked(current.copy(content = "最新离线版本"))
                }
            val updateOperation =
                updateFollowUps.single().let {
                    when (it) {
                        is StructuredMemoryRepository.VectorFollowUp.Sync -> it.operationId
                        is StructuredMemoryRepository.VectorFollowUp.DeleteCopy -> it.operationId
                    }
                }
            val offlineService =
                VectorMemoryService(
                    CachedVectorStore(runtime.baseStore),
                    MockEmbeddingService(),
                    VectorRecallValidator(runtime.db)::filterValid,
                )
            VectorSyncRecovery(runtime.db, offlineService, { clock }).processNow(updateOperation)
            assertTrue(runtime.db.vectorSyncOutboxDao().getById(updateOperation) != null)
            val (otherMemoryId, _) = createLocked(memory("其他会话待办", "s-ack-b"))

            runtime.repository.deleteMemory(memoryId, owner, "s-ack-a")

            assertTrue(
                runtime.db.vectorSyncOutboxDao().dueForSource("summary", owner, "s-ack-a", memoryId.toString(), clock)
                    .isEmpty(),
            )
            assertTrue(
                runtime.db.vectorSyncOutboxDao().dueForSource("summary", owner, "s-ack-b", otherMemoryId.toString(), clock)
                    .isNotEmpty(),
            )
            runtime.recovery.recoverDue()
            assertEquals(0, runtime.db.vectorSyncOutboxDao().count())
        }

    @Test
    fun `重复与并发恢复不重复消费_取消后仍可继续`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-concurrent")
            val (memoryId, _) = createLocked(memory("并发恢复-71ac", "s-concurrent"))
            val operationId = latestOperationId()
            val embedding = runtime.embedding as GateEmbedding
            val gate = embedding.enqueueGate()
            val first = async { runtime.recovery.processNow(operationId) }
            val second = async { runtime.recovery.recoverDue() }
            gate.entered.await()
            gate.release.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, embedding.calls.get())
            assertEquals(1, indexContents("structured_memory_$memoryId").size)
            runtime.recovery.recoverDue()
            assertEquals(1, embedding.calls.get())

            val (_, _) = createLocked(memory("取消恢复-91bd", "s-concurrent"))
            val cancelOperation = latestOperationId()
            val cancelGate = embedding.enqueueGate()
            val cancelled = async { runtime.recovery.processNow(cancelOperation) }
            cancelGate.entered.await()
            cancelled.cancel()
            cancelled.join()
            assertTrue(runtime.db.vectorSyncOutboxDao().getById(cancelOperation) != null)

            restartRuntime()
            runtime.recovery.processNow(cancelOperation)
            assertEquals(0, runtime.db.vectorSyncOutboxDao().count())
        }

    @Test
    fun `旧备份合并记录待办_重复合并不制造副本`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-backup")
            val backup =
                StructuredMemoryEntity(
                    id = 91,
                    ownerId = owner,
                    characterId = "character-1",
                    sessionId = "s-backup",
                    memoryType = "fact",
                    title = "旧备份",
                    content = "备份合成事实-b701",
                    structuredData = null,
                    importance = 9,
                    tags = null,
                    keywords = null,
                    createdAt = "2025-01-01T00:00:00Z",
                    updatedAt = "2025-01-01T00:00:00Z",
                    lastAccessedAt = null,
                    accessCount = 0,
                    relatedMessageIds = null,
                    sourceType = "import",
                )
            val merger = StructuredMemoryRestoreMerger(runtime.db)
            runtime.db.withTransaction { merger.merge(listOf(backup)) }
            runtime.db.withTransaction { merger.merge(listOf(backup)) }
            runtime.recovery.recoverDue()

            assertEquals(1, runtime.db.structuredMemoryDao().getByOwnerAndSession(owner, "s-backup").size)
            assertEquals(1, indexContents("structured_memory_91").size)
            assertTrue(indexContents("structured_memory_91").single().contains("备份合成事实-b701"))
        }

    @Test
    fun `普通消息提交后中断_同一恢复协议补建且不失真`() =
        boundedRun {
            runtime = newRuntime()
            seedSession("s-message")
            runtime.db.messageDao().upsert(message("message-crash", "s-message", "普通消息恢复-f092"))
            assertEquals(1, runtime.db.vectorSyncOutboxDao().count())

            restartRuntime()
            runtime.recovery.recoverDue()
            assertEquals(listOf("普通消息恢复-f092"), indexContents("message-crash"))
            assertEquals(0, runtime.db.vectorSyncOutboxDao().count())
        }

    private fun message(
        id: String,
        sessionId: String,
        content: String,
    ) = MessageEntity(
        id = id,
        sessionId = sessionId,
        ownerId = owner,
        characterId = "character-1",
        role = "user",
        content = content,
        thinking = null,
        createdAt = "2026-01-01T00:00:00Z",
        memoryIdsJson = "",
        swipesJson = "",
        swipeIndex = 0,
        thinkingSwipesJson = "",
    )

    private data class TestRuntime(
        val db: AppDatabase,
        val baseStore: InMemoryVectorStore,
        val embedding: EmbeddingService,
        val service: VectorMemoryService,
        val recovery: VectorSyncRecovery,
        val repository: StructuredMemoryRepository,
    )

    private class EmbedGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class GateEmbedding(
        private val fp: String = "test:model-a:$DIMENSION",
    ) : OpenAIEmbeddingService(apiKey = "test", baseUrl = "http://localhost/", model = "test") {
        private val gates = ArrayDeque<EmbedGate>()
        val calls = AtomicInteger(0)

        fun enqueueGate(): EmbedGate = EmbedGate().also { gates.addLast(it) }

        override val fingerprint: String get() = fp

        override suspend fun embed(text: String): FloatArray {
            calls.incrementAndGet()
            val gate = gates.removeFirstOrNull()
            if (gate != null) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            val random = Random(text.hashCode().toLong())
            return FloatArray(DIMENSION) { random.nextFloat() * 2 - 1 }
        }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    }

    private companion object {
        const val DIMENSION = 8
    }
}
