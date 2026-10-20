package com.mistbell.tavern.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.data.vector.reconcileOrphanedSourcesWithDatabase
import com.mistbell.tavern.android.data.vector.reconcileWithDatabase
import com.mistbell.tavern.android.di.AppContainer
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TavernApplication : Application() {
    /**
     * 应用级 DI 容器：所有服务的唯一装配入口。
     *
     * 新代码请经 `instance.container.xxx` 取服务；本类上的同名委托属性仅为兼容存量调用，
     * 逐步迁移后移除。
     */
    val container: AppContainer by lazy { AppContainer(this) }

    val database: AppDatabase by lazy { container.database }

    val vectorStore: VectorStore by lazy { container.vectorStore }

    val embeddingService: EmbeddingService by lazy { container.embeddingService }

    val vectorMemoryService: VectorMemoryService by lazy { container.vectorMemoryService }

    /** 应用级后台作用域：只用于"尽力而为"的收尾工作（如退到后台时补齐向量索引落盘） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "TavernApplication"
        private const val PREFS_NAME = "tavern_settings"
        private const val KEY_EMBEDDING_API_KEY = "embedding_api_key"
        private const val KEY_EMBEDDING_BASE_URL = "embedding_base_url"

        lateinit var instance: TavernApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 移除同步初始化，改为完全延迟加载
        Log.d(TAG, "TavernApplication created (services will be initialized on demand)")

        // 启动对账（后台、尽力而为，不阻塞启动）：内存里的删除意图会随进程结束丢失，
        // 磁盘上却留着"已删会话"的向量，重启后它们照样能被召回——跨会话召回还会放大影响。
        // 以 Room 的会话表为准收敛一次（见 VectorMemoryService.reconcileWithDatabase）。
        appScope.launch {
            try {
                container.sessionMemoryResetGate()
                container.vectorIndexRecoveryReconciler.reconcileOnce()
                container.vectorSyncRecovery.recoverDue()
                container.vectorMemoryService.reconcileWithDatabase(database)
                // 消息级孤儿对账：删除路径"锁内删内存、锁外落盘"被强杀打断时，已删消息/记忆的
                // 向量会在磁盘上复活；会话级对账管不到这一层（会话还在、源没了），见函数注释
                container.vectorMemoryService.reconcileOrphanedSourcesWithDatabase(database)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "向量索引启动恢复失败：${e.message}", e)
            }
        }
    }

    /**
     * 进程退到后台 / 内存紧张：把向量索引的合并写入窗口补齐。
     *
     * 索引是"一个窗口内最多落盘一次"的（见 InMemoryVectorStore），这样高频写入不会每条消息
     * 都重写整份索引；代价是正常情况下落后磁盘一个窗口。这个钩子是零依赖的兜底
     * （TRIM_MEMORY_UI_HIDDEN 对应用户离开界面），把丢失窗口压到最小。
     *
     * **不能 runBlocking**：onTrimMemory 在主线程回调，而 flush 要整份重写索引（满容量时数 MB），
     * 阻塞等待就是一次 ANR。UI_HIDDEN 只表示"退到后台"，进程仍然活着，后台补齐来得及。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        appScope.launch {
            runCatching { container.vectorStore.flush() }
                .onFailure { Log.w(TAG, "向量索引落盘失败：${it.message}") }
        }
    }

    /**
     * 更新 Embedding API Key
     * 注意：由于服务使用 lazy 初始化，需要重启应用才能生效
     */
    fun updateEmbeddingApiKey(apiKey: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_EMBEDDING_API_KEY, SecureStore.wrap(apiKey)).apply()
        Log.d(TAG, "Embedding API key updated (restart required)")
    }

    /**
     * 更新 Embedding Base URL
     * 注意：由于服务使用 lazy 初始化，需要重启应用才能生效
     */
    fun updateEmbeddingBaseUrl(baseUrl: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_EMBEDDING_BASE_URL, baseUrl).apply()
        Log.d(TAG, "Embedding base URL updated (restart required)")
    }
}
