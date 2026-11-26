package com.mistbell.tavern.android.eval.memory

import android.app.Instrumentation
import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.dao.ApiConfigDao
import com.mistbell.tavern.android.data.local.dao.CharacterDao
import com.mistbell.tavern.android.data.local.dao.CustomPromptDao
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.dao.GlobalRegexDao
import com.mistbell.tavern.android.data.local.dao.MemoryDao
import com.mistbell.tavern.android.data.local.dao.MessageDao
import com.mistbell.tavern.android.data.local.dao.QuickReplyDao
import com.mistbell.tavern.android.data.local.dao.RulePackDao
import com.mistbell.tavern.android.data.local.dao.SessionDao
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.dao.StructuredMemoryDao
import com.mistbell.tavern.android.data.local.dao.ThemePackDao
import com.mistbell.tavern.android.data.local.dao.VectorMemoryDao
import com.mistbell.tavern.android.data.local.dao.VectorSyncOutboxDao
import com.mistbell.tavern.android.data.local.dao.WorldBookDao
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.VectorSyncOutboxEntity
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.data.repository.VectorRecallValidator
import com.mistbell.tavern.android.data.repository.VectorSyncRecovery
import com.mistbell.tavern.android.data.vector.CachedVectorStore
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.LocalEmbeddingService
import com.mistbell.tavern.android.data.vector.SqliteVecStore
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.service.MemoryExtractionService
import java.io.File
import java.io.IOException
import java.io.InputStream

/** 摘要副本向量在向量库里的 message_id 前缀（生产 VectorSyncSource.summaryMessageId 的同一式样） */
private const val SUMMARY_MESSAGE_ID_PREFIX = "structured_memory_"

/** 毫秒换算（纳秒计时的展示口径） */
private const val NANOS_PER_MILLISECOND = 1_000_000L

/**
 * 一次回放的向量化计数（设备侧证据）：
 * - 消息向量：每楼插入后按生产时序排空（hook 计次/计时）；
 * - 摘要向量：每次保存后 runVectorFollowUps 排空（pipeline 计次/计时）；
 * - 写入/检索：CountingVectorStore 在存储层计数（检索计时含查询向量化）。
 */
class DeviceSyncStats {
    var messageSyncCount = 0
        private set
    var messageSyncTotalMs = 0L
        private set
    var summarySyncCount = 0
        private set
    var summarySyncTotalMs = 0L
        private set

    fun recordMessage(
        count: Int,
        elapsedMs: Long,
    ) {
        messageSyncCount += count
        messageSyncTotalMs += elapsedMs
    }

    fun recordSummary(
        count: Int,
        elapsedMs: Long,
    ) {
        summarySyncCount += count
        summarySyncTotalMs += elapsedMs
    }
}

/**
 * 设备侧夹具读取：sharedTest/resources 挂在 androidTest 源集上，但「打进测试 APK 的哪种资源面」
 * 由设备实测决定。这里按优先级试三种读取方式，探测 `manifest.json` 成功的那种固定下来；
 * 全部失败则抛出异常并列出每种方式的失败原因（评测夹具是地基，读不到必须明确失败）。
 *
 * 1. `instrumentation.context.assets`：测试 APK 的 assets；
 * 2. `instrumentation.targetContext.assets`：被测 APK 的 assets（正常不应有，列为兜底诊断）；
 * 3. 类加载器（AGP 若把 Java 资源打进测试 APK， 则走这条路）。
 */
class DeviceEvalResourceAccess private constructor(
    private val opener: (String) -> InputStream,
    val via: String,
) {
    companion object {
        private const val PROBE_PATH = MemoryEvalFixtures.MANIFEST_PATH

        fun resolve(instrumentation: Instrumentation): DeviceEvalResourceAccess {
            val methodNames =
                listOf(
                    "instrumentation.context.assets（测试 APK assets）",
                    "instrumentation.targetContext.assets（被测 APK assets）",
                    "classloader（测试 APK Java 资源）",
                )
            val failures = mutableListOf<String>()
            for ((method, name) in methodNames.withIndex()) {
                try {
                    openByMethod(method, instrumentation, PROBE_PATH).use { stream ->
                        check(stream.readBytes().isNotEmpty())
                    }
                    return DeviceEvalResourceAccess({ path -> openByMethod(method, instrumentation, path) }, name)
                } catch (e: Exception) {
                    failures += "$name：${e.message}"
                }
            }
            error(
                "评测夹具在测试 APK 上读不到（探测 $PROBE_PATH 失败）。试过的读取方式：${failures.joinToString("；")}。" +
                    "请检查 androidTest 源集的资源挂载/打包方式。",
            )
        }

        /** 按方式序号读取：0 = 测试 APK assets；1 = 被测 APK assets；2 = 类加载器 */
        private fun openByMethod(
            method: Int,
            instrumentation: Instrumentation,
            path: String,
        ): InputStream =
            when (method) {
                0 -> instrumentation.context.assets.open(path)
                1 -> instrumentation.targetContext.assets.open(path)
                else ->
                    MemoryEvalFixtures::class.java.classLoader?.getResourceAsStream(path)
                        ?: throw IOException("classloader 未找到 $path")
            }
    }

    /** 打开夹具资源；失败返回 null（由 MemoryEvalFixtures 收集为该会话的加载失败） */
    fun openOrNull(path: String): InputStream? =
        try {
            opener(path)
        } catch (e: Exception) {
            null
        }

    fun readTextOrNull(path: String): String? = openOrNull(path)?.use { stream -> stream.readBytes().decodeToString() }
}

/**
 * 存储层计数（设备侧证据）：VectorMemoryService 的每次向量写入与检索都经过它。
 * 写入按 messageId 前缀分类（生产摘要副本的 messageId 固定为 structured_memory_<id>）。
 */
internal class CountingVectorStore(
    private val delegate: VectorStore,
) : VectorStore by delegate {
    var messageWriteCount = 0
        private set
    var summaryWriteCount = 0
        private set
    var suppressedWriteCount = 0
        private set
    var searchCount = 0
        private set
    var searchTotalMs = 0L
        private set

    override suspend fun addIfAllowed(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
        messageId: String,
        token: VectorStore.MessageWriteToken,
    ): Boolean {
        val added = delegate.addIfAllowed(id, vector, metadata, messageId, token)
        if (added) {
            if (messageId.startsWith(SUMMARY_MESSAGE_ID_PREFIX)) summaryWriteCount++ else messageWriteCount++
        } else {
            suppressedWriteCount++
        }
        return added
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> {
        val startedAt = System.nanoTime()
        val results = delegate.search(queryVector, topK, filters)
        searchCount++
        searchTotalMs += (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        return results
    }
}

/** 消息 DAO 委托：upsert 之后按生产时序触发该消息的向量排空（ChatRepository.storeUserMessageVector 同位） */
private class HookedMessageDao(
    private val real: MessageDao,
    private val onUpserted: suspend (MessageEntity) -> Unit,
) : MessageDao by real {
    override suspend fun upsert(msg: MessageEntity) {
        real.upsert(msg)
        onUpserted(msg)
    }
}

/**
 * 驱动器用的数据库包装：只有 messageDao().upsert 带"插入后向量排空"钩子，其余 16 个 DAO
 * 全部逐字段委托给真 Room 库。评测不在此包装上跑 `db.withTransaction`（落库路径用的是
 * 真库本身），事务原语与 InvalidationTracker 一律不可用——被触到即抛（评测链路漂移的显式信号）。
 */
private class ReplayDb(
    private val real: AppDatabase,
    hookedMessageDao: MessageDao,
) : AppDatabase() {
    private val messageDaoOverride: MessageDao = hookedMessageDao

    override fun characterDao(): CharacterDao = real.characterDao()

    override fun sessionDao(): SessionDao = real.sessionDao()

    override fun messageDao(): MessageDao = messageDaoOverride

    override fun worldBookDao(): WorldBookDao = real.worldBookDao()

    override fun settingsDao(): SettingsDao = real.settingsDao()

    override fun memoryDao(): MemoryDao = real.memoryDao()

    override fun structuredMemoryDao(): StructuredMemoryDao = real.structuredMemoryDao()

    override fun vectorMemoryDao(): VectorMemoryDao = real.vectorMemoryDao()

    override fun vectorSyncOutboxDao(): VectorSyncOutboxDao = real.vectorSyncOutboxDao()

    override fun themePackDao(): ThemePackDao = real.themePackDao()

    override fun apiConfigDao(): ApiConfigDao = real.apiConfigDao()

    override fun customPromptDao(): CustomPromptDao = real.customPromptDao()

    override fun rulePackDao(): RulePackDao = real.rulePackDao()

    override fun quickReplyDao(): QuickReplyDao = real.quickReplyDao()

    override fun globalRegexDao(): GlobalRegexDao = real.globalRegexDao()

    override fun extensionPackDao(): ExtensionPackDao = real.extensionPackDao()

    override fun createInvalidationTracker(): InvalidationTracker =
        throw UnsupportedOperationException("ReplayDb 不支持 InvalidationTracker（评测链路不应触达）")

    override fun clearAllTables() = throw UnsupportedOperationException("ReplayDb 不支持 clearAllTables（评测链路不应触达）")
}

/** 一个会话回放用的完整真栈（真 Room + 真 embedding + sqlite-vec/内存索引 + 生产仓库/抽取服务） */
internal class DeviceSessionStack private constructor(
    val targetContext: Context,
    val roomDb: AppDatabase,
    val driverDb: AppDatabase,
    val embedding: EmbeddingService,
    val sqliteStore: SqliteVecStore?,
    /** sqlite-vec 探测结果；非 sqlite 栈（反向验证）为 null */
    val sqliteProbeOk: Boolean?,
    val countingStore: CountingVectorStore,
    val vectorMemoryService: VectorMemoryService,
    val repository: StructuredMemoryRepository,
    val extractionService: MemoryExtractionService,
    val pipeline: DeviceMemoryEvalPipeline,
    val syncStats: DeviceSyncStats,
    private val ownedFiles: List<File>,
) {
    suspend fun close() {
        roomDb.close()
        ownedFiles.forEach { file ->
            file.delete()
            File(file.parentFile, "${file.name}-wal").delete()
            File(file.parentFile, "${file.name}-shm").delete()
        }
    }

    companion object {
        /**
         * 装配一个会话的真栈：
         * - Room：内存库 + [AppDatabase.vectorSyncCallback]（生产同一回调，onOpen 建 6 个向量同步触发器）；
         * - 向量库：[SqliteVecStore]（vec0 路径取应用原生库目录，索引预算 = 生产默认值）+ 生产同款
         *   [CachedVectorStore]（cacheSize 50）；[sqlite] = false 时换 [InMemoryVectorStore]（反向验证用）；
         * - embedding：调用方提供（真栈 = [LocalEmbeddingService.create] 产物；反向验证 = Mock）；
         * - 召回校验/向量服务/仓库/抽取服务：与生产装配同构，全部显式传参，不触真实 AppContainer。
         */
        suspend fun create(
            targetContext: Context,
            instrumentationCacheDir: File,
            embedding: EmbeddingService,
            label: String,
            sqlite: Boolean,
        ): DeviceSessionStack {
            val roomDb =
                Room
                    .inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
                    .allowMainThreadQueries()
                    .addCallback(AppDatabase.vectorSyncCallback)
                    .build()
            val extensionPath =
                File(targetContext.applicationInfo.nativeLibraryDir, SqliteVecStore.EXTENSION_NAME).absolutePath
            val ownedFiles = mutableListOf<File>()
            val sqliteStore =
                if (sqlite) {
                    val dbFile = File(instrumentationCacheDir, "device-eval-vectors-$label-${System.nanoTime()}.db")
                    ownedFiles += dbFile
                    SqliteVecStore(
                        dbFile,
                        extensionPath,
                        indexBudgetBytes = InMemoryVectorStore.DEFAULT_INDEX_BUDGET_BYTES,
                        loadGate = {},
                    )
                } else {
                    val jsonFile = File(instrumentationCacheDir, "device-eval-vectors-$label-${System.nanoTime()}.json")
                    ownedFiles += jsonFile
                    InMemoryVectorStore(jsonFile, maxVectorsInMemory = 1000, loadGate = {})
                }
            val probeOk = (sqliteStore as? SqliteVecStore)?.probe()
            val countingStore = CountingVectorStore(sqliteStore)
            // cacheSize = 生产 AppContainer.vectorStore 同款（50）
            val cachedStore = CachedVectorStore(countingStore, cacheSize = 50)
            val vectorMemoryService =
                VectorMemoryService(
                    vectorStore = cachedStore,
                    embeddingService = embedding,
                    recallValidator = VectorRecallValidator(roomDb)::filterValid,
                )
            val recovery = VectorSyncRecovery(roomDb, vectorMemoryService)
            val syncStats = DeviceSyncStats()
            val repository =
                StructuredMemoryRepository(
                    context = targetContext,
                    resetGate = {},
                    db = roomDb,
                    vectorMemoryService = vectorMemoryService,
                )
            val extractionService = MemoryExtractionService(targetContext, repository)
            val pipeline = DeviceMemoryEvalPipeline(extractionService, repository, roomDb, syncStats)
            val driverDb =
                ReplayDb(
                    roomDb,
                    HookedMessageDao(roomDb.messageDao()) { msg ->
                        val startedAt = System.nanoTime()
                        recovery.recoverSource(
                            sourceType = VectorSyncOutboxEntity.SOURCE_MESSAGE,
                            ownerId = msg.ownerId,
                            sessionId = msg.sessionId,
                            sourceId = msg.id,
                        )
                        syncStats.recordMessage(1, (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
                    },
                )
            return DeviceSessionStack(
                targetContext = targetContext,
                roomDb = roomDb,
                driverDb = driverDb,
                embedding = embedding,
                sqliteStore = sqliteStore as? SqliteVecStore,
                sqliteProbeOk = probeOk,
                countingStore = countingStore,
                vectorMemoryService = vectorMemoryService,
                repository = repository,
                extractionService = extractionService,
                pipeline = pipeline,
                syncStats = syncStats,
                ownedFiles = ownedFiles,
            )
        }
    }
}

/** 向量库里两个口径的条数（设备侧证据）：按来源身份去重的 id 集合 + 存储层总行数 */
class DeviceVectorCounts(
    val messageIdCount: Int,
    val summaryIdCount: Int,
    val storeRowCount: Int,
)
