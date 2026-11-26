package com.mistbell.tavern.android.eval.memory

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.repository.SessionMutationLocks
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.data.repository.VectorRecallValidator
import com.mistbell.tavern.android.data.repository.VectorSyncRecovery
import com.mistbell.tavern.android.data.vector.CachedVectorStore
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.di.AppContainer
import com.mistbell.tavern.android.service.MemoryExtractionService
import java.io.File
import java.nio.file.Files

/** 文件内短别名：让 override 签名保持在 120 列内（detekt 上限），不改变任何类型 */
private typealias EvalDialogueTurn = MemoryExtractionService.DialogueTurn

private typealias Parsed = MemoryExtractionService.MemoryCandidate

/**
 * JVM 评测的运行时引导（JVM 专有；设备侧由后续任务用真容器/真 embedding 复用同一评测核心）。
 *
 * 三件事：
 * 1. 把 `TavernApplication.instance` / `AppContainer` 的懒加载属性替换为替身栈——
 *    生产代码里 `LexicalMemoryService`、`MemoryExtractionService` 的取库路径都经
 *    `TavernApplication.instance.container.database`，评测必须让它们拿到替身；
 * 2. 每会话装配「真实向量栈」：VectorMemoryService + CachedVectorStore + InMemoryVectorStore
 *    + MockEmbeddingService（确定性 embedding；`available=false` → PromptBuilder 如实走词法回退）；
 * 3. 注入 PromptBuilder 的记忆闸门与向量服务提供者（internal var，结束后恢复原值）。
 * 本运行时不开网络、不涉密钥。
 */
internal object EvalRuntime {
    private val mockEmbedding = MockEmbeddingService()
    private var savedGate: (suspend () -> Unit)? = null
    private var savedVectorProvider: (() -> VectorMemoryService)? = null
    private var appInstance: TavernApplication? = null
    private var containerInstance: AppContainer? = null
    private var tempRoot: File? = null

    /** 每次会话回放前调用：把容器指向该会话的替身库与向量栈；返回本会话的向量服务（供仓库注入） */
    fun installSession(fakeDb: FakeTavernDb): VectorMemoryService {
        ensureApplicationInstance()
        val database = fakeDb.db()
        val root = tempRoot ?: Files.createTempDirectory("memory-eval").toFile().also { tempRoot = it }
        val vectorStore =
            CachedVectorStore(
                InMemoryVectorStore(
                    File(root, "eval-vectors-${System.nanoTime()}.json"),
                    maxVectorsInMemory = 1000,
                    loadGate = {},
                ),
                cacheSize = 50,
            )
        val vectorMemoryService =
            VectorMemoryService(
                vectorStore = vectorStore,
                embeddingService = mockEmbedding,
                recallValidator = VectorRecallValidator(database)::filterValid,
            )
        val container = containerInstance ?: error("容器未初始化")
        swapLazy(container, "database", database)
        swapLazy(container, "vectorStore", vectorStore)
        swapLazy(container, "embeddingService", mockEmbedding)
        swapLazy(container, "vectorMemoryService", vectorMemoryService)
        swapLazy(container, "vectorSyncRecovery", VectorSyncRecovery(database, vectorMemoryService))
        if (savedGate == null) {
            savedGate = PromptBuilder.memoryResetGate
            savedVectorProvider = PromptBuilder.vectorMemoryServiceProvider
        }
        PromptBuilder.memoryResetGate = {} // 记忆链就绪（评测环境没有升级清零）
        PromptBuilder.vectorMemoryServiceProvider = { vectorMemoryService }
        return vectorMemoryService
    }

    /** 全部回放结束后恢复 PromptBuilder 的原始注入（不影响其它测试） */
    fun restore() {
        savedGate?.let { PromptBuilder.memoryResetGate = it }
        savedVectorProvider?.let { PromptBuilder.vectorMemoryServiceProvider = it }
        savedGate = null
        savedVectorProvider = null
    }

    private fun ensureApplicationInstance() {
        if (appInstance != null) return
        val app = TavernApplication()
        val container = AppContainer(EvalContext())
        containerInstance = container
        // TavernApplication.container 是 by lazy：替换它的 delegate 字段，让 instance.container 指向替身容器
        val delegateField = TavernApplication::class.java.getDeclaredField("container\$delegate")
        delegateField.isAccessible = true
        delegateField.set(app, lazyOf(container))
        setCompanionInstance(app)
    }

    /** `TavernApplication.instance` 是 companion 的 lateinit var，编译为外层类的私有静态字段 */
    private fun setCompanionInstance(app: TavernApplication) {
        val field = TavernApplication::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, app)
    }

    /** 按属性名替换 AppContainer 上 `by lazy` 属性的 delegate 字段（property$delegate）；
     *  非 lazy 属性（如 sessionMemoryResetGate 计算属性）没有该字段，静默跳过（评测链路不触它） */
    private fun swapLazy(
        container: AppContainer,
        propertyName: String,
        value: Any,
    ) {
        val delegateName = "$propertyName\$delegate"
        val field = AppContainer::class.java.declaredFields.firstOrNull { it.name == delegateName } ?: return
        field.isAccessible = true
        field.set(container, lazyOf(value))
    }
}

/** JVM 评测的资源读取：sharedTest/resources 挂在测试类路径上，走类加载器 */
internal object JvmEvalResources {
    fun resolver(): EvalResourceResolver =
        EvalResourceResolver { path ->
            Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                ?: MemoryEvalFixtures::class.java.classLoader?.getResourceAsStream(path)
        }
}

/** 评测核心依赖面的 JVM 实现：全部方法直通生产代码（MemoryExtractionService / StructuredMemoryRepository） */
internal class JvmMemoryEvalPipeline(
    private val service: MemoryExtractionService,
    private val repository: StructuredMemoryRepository,
    private val db: AppDatabase,
) : MemoryEvalPipeline {
    override fun buildDialogueText(turns: List<EvalDialogueTurn>): String = service.buildDialogueText(turns)

    override fun shouldExtractMemory(dialogueText: String): Boolean = service.shouldExtractMemory(dialogueText)

    override suspend fun buildMemoryExtractionPrompt(text: String): String = service.buildMemoryExtractionPrompt(text)

    override suspend fun parseAndValidateCandidates(raw: String): List<Parsed> = service.parseAndValidateCandidates(raw)

    override suspend fun saveCandidatesLocked(
        candidates: List<Parsed>,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
    ): MemoryExtractionService.SaveResult =
        // saveCandidatesLocked 的调用契约是"调用方已持会话锁"（与 ChatRepository.launchMemoryExtraction 一致）
        SessionMutationLocks.withSessionLock(sessionId) {
            service.saveCandidatesLocked(
                candidates = candidates,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageIds = messageIds,
            )
        }

    override suspend fun runVectorFollowUps(followUps: List<StructuredMemoryRepository.VectorFollowUp>) {
        repository.runVectorFollowUps(followUps)
    }

    override suspend fun memoriesInSession(
        ownerId: String,
        sessionId: String,
    ): List<StructuredMemory> = repository.getMemoriesByOwnerAndSession(ownerId, sessionId)

    override suspend fun resolveInjectedRow(
        source: String,
        id: String,
    ): InjectedMemoryRow? =
        when (source) {
            // 结构化记忆行：正文 + 类型一并带回（记忆评测按类型观察注入面）
            MemoryEvalDriver.SOURCE_STRUCTURED ->
                repository.getMemoryById(id.toLong())?.let { memory ->
                    InjectedMemoryRow(source, id, memory.content, memory.memoryType)
                }
            // 召回行：历史消息正文（注入投影为 150 字截断前的原文）
            else ->
                db.messageDao().getAnyById(id)?.let { message -> InjectedMemoryRow(source, id, message.content) }
        }

    override suspend fun deleteMemory(
        id: Long,
        ownerId: String,
        sessionId: String,
    ) {
        repository.deleteMemory(id, ownerId, sessionId)
    }

    override suspend fun insertMemory(memory: StructuredMemory): Long = repository.createMemory(memory)
}

/** JVM 回放执行器：替身库 + 真实抽取服务/记忆仓库 + 驱动器（每会话独立一套） */
internal object MemoryEvalReplayRunner {
    suspend fun replayOne(
        session: EvalSession,
        config: DriverConfig,
    ): ReplayWithFixture {
        val fakeDb = FakeTavernDb()
        val vectorMemoryService = EvalRuntime.installSession(fakeDb)
        val database = fakeDb.db()
        val repository =
            StructuredMemoryRepository(
                context = EvalContext(),
                resetGate = {},
                db = database,
                vectorMemoryService = vectorMemoryService,
            )
        val extractionService = MemoryExtractionService(EvalContext(), repository)
        val pipeline = JvmMemoryEvalPipeline(extractionService, repository, database)
        val driver = MemoryEvalDriver(pipeline)
        val replay = driver.replay(session, database, config)
        return ReplayWithFixture(replay, session)
    }

    suspend fun replayAll(
        sessions: List<EvalSession>,
        config: DriverConfig,
    ): List<ReplayWithFixture> = sessions.map { replayOne(it, config) }
}
