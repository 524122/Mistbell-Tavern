package com.mistbell.tavern.android.di

import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.repository.MemoryResetPendingException
import com.mistbell.tavern.android.data.repository.PendingExtractionStore
import com.mistbell.tavern.android.data.repository.SessionMemoryReset
import com.mistbell.tavern.android.data.repository.VectorIndexRecoveryReconciler
import com.mistbell.tavern.android.data.repository.VectorRecallValidator
import com.mistbell.tavern.android.data.repository.VectorSyncRecovery
import com.mistbell.tavern.android.data.vector.CachedVectorStore
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.LocalEmbeddingService
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.OpenAIEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.util.SecureStore
import java.io.File

/**
 * 应用级 DI 容器：集中装配 database / vectorStore / embeddingService / vectorMemoryService。
 *
 * 各服务均延迟初始化；装配失败按原语义回退（向量服务回退内存实现，embedding 回退 Mock），
 * 绝不因装配崩溃阻塞应用启动。
 */
@Suppress("TooGenericExceptionCaught")
class AppContainer(private val appContext: Context) {
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(appContext)
    }

    /**
     * 记忆链闸门（fail-closed）：每次调用先确认一次性升级清零完成；
     * 未完成（失败/未跑过）即抛 [MemoryResetPendingException]，绝不放行旧数据。
     *
     * 调用方语义分级：
     * - 文件 Store / 写入 / 备份路径：异常原样上抛 = 拒绝加载/写入/导出；
     * - 召回路径（PromptBuilder）：捕获后返回空结果——不召回优于召错，绝不扩大召回范围。
     *
     * 直接调 [SessionMemoryReset.runIfNeeded] 而不是 await 缓存的 Deferred：
     * Deferred 会把 false 永久缓存成"已拒绝"，清零将永远无法重试；
     * runIfNeeded 内部只在成功时记完成态，失败后的下一次闸门调用会真正重试清零。
     */
    val sessionMemoryResetGate: suspend () -> Unit
        get() = {
            if (!SessionMemoryReset.runIfNeeded(appContext, database)) {
                throw MemoryResetPendingException()
            }
        }

    val vectorStore: VectorStore by lazy {
        try {
            // 生产索引不设 1000 条硬上限：向量是长期记忆的持久化派生索引，
            // 静默淘汰会让早期记忆永久退出召回。受限容量场景仍可在构造时传正数。
            //
            // 但**不设条数上限不等于不限容量**：整文件重写的索引会无界增长，低存储设备上
            // 先是写不进去、再是整份索引一起坏掉。所以改为按字节预算治理磁盘占用
            // （见 InMemoryVectorStore.DEFAULT_INDEX_BUDGET_BYTES），超预算时按价值淘汰并在
            // 设置页显示占用——淘汰是可观测的，不再是静默遗忘。
            val baseStore =
                InMemoryVectorStore(
                    appContext,
                    maxVectorsInMemory = 0,
                    loadGate = sessionMemoryResetGate,
                )
            CachedVectorStore(baseStore, cacheSize = 50).also {
                Log.d(TAG, "Vector store initialized with cache (lazy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vector store: ${e.message}", e)
            val baseStore =
                InMemoryVectorStore(
                    appContext,
                    maxVectorsInMemory = 0,
                    loadGate = sessionMemoryResetGate,
                )
            CachedVectorStore(baseStore, cacheSize = 50)
        }
    }

    /**
     * Embedding 服务（延迟初始化，源路由：settings `embedding_source`）
     *
     * - auto（默认）：有 embedding API key → OpenAI；无 key → Mock（词法回退）——兼容历史行为；
     * - api：强制 API（无 key 则 Mock）；
     * - local：本地 ONNX（bge-small-zh）；初始化失败回退 Mock 并记日志。
     */
    @Suppress("TooGenericExceptionCaught")
    val embeddingService: EmbeddingService by lazy {
        // runBlocking 限定在装配边界：lazy 首触发出自协程（PromptBuilder 等在 IO 线程），
        // 且整个装配只在应用生命周期内执行一次
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            assembleEmbeddingService()
        }
    }

    private suspend fun assembleEmbeddingService(): EmbeddingService {
        val source =
            runCatching { database.settingsDao().getValue("embedding_source") }.getOrNull()
        return try {
            when (source) {
                "local" -> {
                    Log.d(TAG, "Embedding source=local: loading bge-small-zh (ONNX, lazy)")
                    runCatching { LocalEmbeddingService.create(appContext) }
                        .onFailure { Log.e(TAG, "Local embedding init failed, fallback to mock", it) }
                        .getOrElse { MockEmbeddingService() }
                }
                "api" -> openAiEmbeddingOrMock()
                else ->
                    if (getEmbeddingApiKey().isNotBlank()) {
                        openAiEmbeddingOrMock()
                    } else {
                        MockEmbeddingService()
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding service: ${e.message}", e)
            MockEmbeddingService()
        }
    }

    private fun openAiEmbeddingOrMock(): EmbeddingService {
        val apiKey = getEmbeddingApiKey()
        val baseUrl = getEmbeddingBaseUrl()
        return if (apiKey.isNotBlank()) {
            Log.d(TAG, "Using OpenAI Embedding Service (lazy)")
            OpenAIEmbeddingService(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = "text-embedding-3-small",
            )
        } else {
            // 无 key：不用伪向量，注入 Mock 占位（available=false，调用方走词法回退）
            Log.d(TAG, "No embedding API key, vector memory unavailable (lexical fallback)")
            MockEmbeddingService()
        }
    }

    /**
     * 待抽取记忆缓冲（落盘）。
     *
     * 必须全进程唯一：ChatRepository 每个聊天页一个实例，缓冲若各自持有会各攒一半、谁也攒不满。
     */
    val pendingExtractionStore: PendingExtractionStore by lazy {
        PendingExtractionStore(
            File(appContext.filesDir, PendingExtractionStore.FILE_NAME),
            loadGate = sessionMemoryResetGate,
        )
    }

    val vectorMemoryService: VectorMemoryService by lazy {
        try {
            VectorMemoryService(
                vectorStore = vectorStore,
                embeddingService = embeddingService,
                recallValidator = VectorRecallValidator(database)::filterValid,
            ).also {
                Log.d(TAG, "Vector memory service initialized (lazy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vector memory service: ${e.message}", e)
            VectorMemoryService(
                vectorStore,
                embeddingService,
                VectorRecallValidator(database)::filterValid,
            )
        }
    }

    val vectorSyncRecovery: VectorSyncRecovery by lazy {
        VectorSyncRecovery(database, vectorMemoryService)
    }

    val vectorIndexRecoveryReconciler: VectorIndexRecoveryReconciler by lazy {
        VectorIndexRecoveryReconciler(database, vectorMemoryService)
    }

    private fun getEmbeddingApiKey(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SecureStore.unwrap(prefs.getString(KEY_EMBEDDING_API_KEY, "") ?: "")
    }

    private fun getEmbeddingBaseUrl(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EMBEDDING_BASE_URL, "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"
    }

    companion object {
        private const val TAG = "AppContainer"
        private const val PREFS_NAME = "tavern_settings"
        private const val KEY_EMBEDDING_API_KEY = "embedding_api_key"
        private const val KEY_EMBEDDING_BASE_URL = "embedding_base_url"
    }
}
