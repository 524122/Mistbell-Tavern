package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.model.ProviderConfig
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

class ProviderRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)

    fun observeProviders(): Flow<List<ProviderConfig>> {
        return db.settingsDao().getAll().map { entities ->
            val map = entities.associate { it.key to it.value }
            // Keystore 首次加载是磁盘 + 加密操作，切到 IO 线程，避免阻塞收集方（主线程）
            val json =
                withContext(Dispatchers.IO) {
                    SecureStore.unwrap(map["providers_json"] ?: "[]")
                }
            try {
                Json.decodeFromString(ListSerializer(ProviderConfig.serializer()), json)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun observeActiveProviderId(): Flow<String> {
        return db.settingsDao().getAll().map { entities ->
            val map = entities.associate { it.key to it.value }
            map["active_provider_id"] ?: ""
        }
    }

    fun observeActiveModelId(): Flow<String> {
        return db.settingsDao().getAll().map { entities ->
            val map = entities.associate { it.key to it.value }
            map["active_model_id"] ?: ""
        }
    }

    suspend fun saveProviders(providers: List<ProviderConfig>) {
        withContext(Dispatchers.IO) {
            val json = Json.encodeToString(ListSerializer(ProviderConfig.serializer()), providers)
            db.settingsDao().upsert(SettingsEntity("providers_json", SecureStore.wrap(json)))

            // 同步更新 LLM 配置（使用第一个提供商或当前激活的提供商）
            val activeProviderId = db.settingsDao().getValue("active_provider_id") ?: ""
            val activeProvider = providers.find { it.id == activeProviderId } ?: providers.firstOrNull()

            if (activeProvider != null) {
                db.settingsDao().upsert(SettingsEntity("llm_base_url", activeProvider.endpoint))
                db.settingsDao().upsert(SettingsEntity("llm_api_key", SecureStore.wrap(activeProvider.apiKey)))
                db.settingsDao().upsert(SettingsEntity("llm_model", activeProvider.selectedModel))

                // 平铺写入高级采样参数覆盖（null → 空白串 = 未设/清除）
                listOf(
                    "llm_temperature" to activeProvider.temperature,
                    "llm_top_p" to activeProvider.topP,
                    "llm_top_k" to activeProvider.topK,
                    "llm_frequency_penalty" to activeProvider.frequencyPenalty,
                    "llm_max_tokens" to activeProvider.maxTokens,
                ).forEach { (key, value) ->
                    db.settingsDao().upsert(SettingsEntity(key, value?.toString() ?: ""))
                }
            }
        }
    }

    suspend fun setActiveProvider(
        providerId: String,
        modelId: String,
    ) {
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(SettingsEntity("active_provider_id", providerId))
            db.settingsDao().upsert(SettingsEntity("active_model_id", modelId))
        }
    }

    suspend fun fetchModels(
        endpoint: String,
        apiKey: String,
        type: String,
    ): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用 LLM API 的 /models 端点
                val modelsUrl =
                    when {
                        endpoint.endsWith("/") -> "${endpoint}models"
                        else -> "$endpoint/models"
                    }

                val client =
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val request =
                    okhttp3.Request.Builder()
                        .url(modelsUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .get()
                        .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val responseBody = response.body?.string() ?: return@withContext emptyList()
                val json = Json { ignoreUnknownKeys = true }
                val result = json.parseToJsonElement(responseBody)

                // 解析 OpenAI 格式的响应
                if (result is JsonObject) {
                    val dataArray = result["data"] as? JsonArray
                    if (dataArray != null) {
                        return@withContext dataArray.mapNotNull { element ->
                            (element as? JsonObject)?.get("id")?.jsonPrimitive?.content
                        }
                    }
                }

                emptyList()
            } catch (e: Exception) {
                android.util.Log.e("ProviderRepository", "fetchModels error: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun testConnection(
        endpoint: String,
        apiKey: String,
        type: String,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用 LLM API 的 /models 端点来测试连接
                val modelsUrl =
                    when {
                        endpoint.endsWith("/") -> "${endpoint}models"
                        else -> "$endpoint/models"
                    }

                val client =
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val request =
                    okhttp3.Request.Builder()
                        .url(modelsUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .get()
                        .build()

                val response = client.newCall(request).execute()
                val success = response.isSuccessful

                android.util.Log.d("ProviderRepository", "testConnection: $modelsUrl -> ${response.code}")

                response.close()
                success
            } catch (e: Exception) {
                android.util.Log.e("ProviderRepository", "testConnection error: ${e.message}", e)
                false
            }
        }
    }

    suspend fun getActiveProvider(): ProviderConfig? {
        val providers = observeProviders().first()
        val activeId = observeActiveProviderId().first()
        return providers.find { it.id == activeId }
    }

    suspend fun getActiveModelId(): String {
        return observeActiveModelId().first()
    }
}
