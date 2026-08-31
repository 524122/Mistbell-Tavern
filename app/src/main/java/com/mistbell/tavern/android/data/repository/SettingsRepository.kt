package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class SettingsRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)

    // --- LLM Config (local) ---

    suspend fun getLlmConfig(): LlmConfig = withContext(Dispatchers.IO) {
        val dao = db.settingsDao()
        LlmConfig(
            baseUrl = dao.getValue("llm_base_url") ?: "",
            apiKey = SecureStore.unwrap(dao.getValue("llm_api_key") ?: ""),
            model = dao.getValue("llm_model") ?: "",
            temperature = dao.getValue("temperature")?.toDoubleOrNull() ?: 0.8,
            maxTokens = dao.getValue("max_tokens")?.toIntOrNull() ?: 1024
        )
    }

    suspend fun saveLlmConfig(config: LlmConfig) = withContext(Dispatchers.IO) {
        val dao = db.settingsDao()
        dao.upsert(SettingsEntity("llm_base_url", config.baseUrl))
        dao.upsert(SettingsEntity("llm_api_key", SecureStore.wrap(config.apiKey)))
        dao.upsert(SettingsEntity("llm_model", config.model))
        dao.upsert(SettingsEntity("temperature", config.temperature.toString()))
        dao.upsert(SettingsEntity("max_tokens", config.maxTokens.toString()))
    }

    // --- 生成与记忆默认值（settings KV）---

    // 流式输出开关：缺省视为开启（仅显式写 "0" 才关闭）
    suspend fun isStreamingEnabled(): Boolean = withContext(Dispatchers.IO) {
        db.settingsDao().getValue("streaming_enabled") != "0"
    }

    // 新会话默认上下文 token 预算：非法或缺省回退 4096
    suspend fun defaultContextTokens(): Int = withContext(Dispatchers.IO) {
        db.settingsDao().getValue("default_context_tokens")?.toIntOrNull() ?: 4096
    }

    // 新会话默认长期记忆开关：缺省关闭（仅显式写 "1" 才开启）
    suspend fun defaultLtmEnabled(): Boolean = withContext(Dispatchers.IO) {
        db.settingsDao().getValue("default_ltm_enabled") == "1"
    }

    // --- Server settings (sync from API) ---

    fun observeSettings(): Flow<JsonObject?> {
        return db.settingsDao().getAll().map { entities ->
            val map = entities.associate { it.key to it.value }
            buildJsonObject {
                map.forEach { (k, v) ->
                    put(k, JsonPrimitive(v))
                }
            }
        }
    }

    suspend fun loadAndCacheSettings() {
        try {
            val result = api.getSettings()
            if (result is JsonObject) {
                val dao = db.settingsDao()
                result.forEach { (key, value) ->
                    val strValue = when (value) {
                        is JsonPrimitive -> value.content
                        else -> value.toString()
                    }
                    // 敏感 key 同样走加密写入，避免服务器同步把已加密值降级为明文落盘
                    val stored = if (key == "llm_api_key") SecureStore.wrap(strValue) else strValue
                    dao.upsert(SettingsEntity(key, stored))
                }
            }
        } catch (_: Exception) {
            // Server unreachable, local cache remains valid
        }
    }

    suspend fun updateTemperature(temperature: Double) {
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(SettingsEntity("temperature", temperature.toString()))
            try {
                val body = buildJsonObject { put("temperature", temperature) }
                api.updateSettings(body)
            } catch (_: Exception) {}
        }
    }

    suspend fun updateMaxTokens(maxTokens: Int) {
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(SettingsEntity("max_tokens", maxTokens.toString()))
            try {
                val body = buildJsonObject { put("maxTokens", maxTokens) }
                api.updateSettings(body)
            } catch (_: Exception) {}
        }
    }
}
