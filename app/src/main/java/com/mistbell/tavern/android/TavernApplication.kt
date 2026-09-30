package com.mistbell.tavern.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.di.AppContainer
import com.mistbell.tavern.android.util.SecureStore

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
