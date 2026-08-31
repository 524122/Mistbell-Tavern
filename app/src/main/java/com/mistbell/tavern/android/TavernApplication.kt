package com.mistbell.tavern.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.vector.*
import com.mistbell.tavern.android.util.SecureStore

class TavernApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    // 向量存储服务 - 延迟初始化
    val vectorStore: VectorStore by lazy {
        try {
            val baseStore = InMemoryVectorStore(this)
            CachedVectorStore(baseStore, cacheSize = 50).also {
                Log.d(TAG, "Vector store initialized with cache (lazy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vector store: ${e.message}", e)
            val baseStore = InMemoryVectorStore(this)
            CachedVectorStore(baseStore, cacheSize = 50)
        }
    }

    val embeddingService: EmbeddingService by lazy {
        try {
            val apiKey = getEmbeddingApiKey()
            val baseUrl = getEmbeddingBaseUrl()

            if (apiKey.isNotBlank()) {
                Log.d(TAG, "Using OpenAI Embedding Service (lazy)")
                OpenAIEmbeddingService(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = "text-embedding-3-small"
                )
            } else {
                Log.d(TAG, "Using BM25 Embedding Service (lazy)")
                BM25EmbeddingService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding service: ${e.message}", e)
            BM25EmbeddingService()
        }
    }

    val vectorMemoryService: VectorMemoryService by lazy {
        try {
            VectorMemoryService(
                vectorStore = vectorStore,
                embeddingService = embeddingService
            ).also {
                Log.d(TAG, "Vector memory service initialized (lazy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vector memory service: ${e.message}", e)
            VectorMemoryService(vectorStore, embeddingService)
        }
    }

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
     * 获取 Embedding API Key
     */
    private fun getEmbeddingApiKey(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SecureStore.unwrap(prefs.getString(KEY_EMBEDDING_API_KEY, "") ?: "")
    }

    /**
     * 获取 Embedding Base URL
     */
    private fun getEmbeddingBaseUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EMBEDDING_BASE_URL, "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"
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
