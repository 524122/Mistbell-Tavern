package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 探活结果：detail 是面向用户的原因说明（成功也解释验证到了什么），供 Snackbar / 行内提示展示。
 */
data class ConnectionTestResult(
    val success: Boolean,
    val detail: String,
)

// 探活专用短超时（秒）：分支一与分支二共用，避免用户长时间等待才拿到反馈
private const val PROBE_TIMEOUT_SECONDS = 20

// 分支一走聊天链路时的最小请求参数：只要求模型吐 1 个 token、不重试
private const val TEST_MAX_TOKENS = 1
private const val TEST_RETRIES = 0
private const val TEST_PROMPT = "Hi"

// HTTP 鉴权失败状态码（detekt 数字需提常量）
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

// HTTP 成功状态码区间（/models 探测判成功的前提）
private const val HTTP_OK_MIN = 200
private const val HTTP_OK_MAX = 299

// 展示给用户的异常 detail 最大长度，防止网关错误页刷屏
private const val DETAIL_MAX_LENGTH = 120

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

    /**
     * 探活，按是否已选模型走两条分支：
     * 分支一（model 非空）：与聊天完全同链路（LlmClient.chat）发最小真实请求，保证"测通即聊天可用"，
     * 旧的 GET /models 只能证明端点活着，无法证明模型名与密钥真的能用；
     * 分支二（model 为空）：退化为加固过的 /models 探测，让 Ollama 等本地无 Key / 未选模型提供商也能测试。
     */
    suspend fun testConnection(
        endpoint: String,
        apiKey: String,
        model: String,
    ): ConnectionTestResult =
        withContext(Dispatchers.IO) {
            if (model.isNotBlank()) testWithModel(endpoint, apiKey, model) else probeModelsEndpoint(endpoint, apiKey)
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

/**
 * 分支一：与聊天同链路的最小请求（maxTokens=1、不重试、短超时），异常映射成用户可读的原因。
 * （顶层私有函数而非类成员：避免类函数数超 detekt 阈值，且它们不依赖任何实例状态。）
 */
private suspend fun testWithModel(
    endpoint: String,
    apiKey: String,
    model: String,
): ConnectionTestResult {
    val config =
        LlmConfig(
            baseUrl = endpoint,
            apiKey = apiKey,
            model = model,
            maxTokens = TEST_MAX_TOKENS,
            // 探活不需要重试与长超时：尽快把结果反馈给用户
            retries = TEST_RETRIES,
            timeoutSeconds = PROBE_TIMEOUT_SECONDS,
        )
    return runCatching {
        LlmClient.chat(config, listOf(ChatMessage(role = "user", content = TEST_PROMPT)))
        ConnectionTestResult(true, "端点、密钥与模型均可用")
    }.getOrElse { e ->
        when (e) {
            is SocketTimeoutException -> {
                android.util.Log.w("ProviderRepository", "testWithModel 超时: ${e.message}")
                ConnectionTestResult(false, "连接超时")
            }
            else -> ConnectionTestResult(false, describeChatFailure(e))
        }
    }
}

/**
 * 把聊天链路的异常映射成用户可读的原因。
 * LlmClient 对非 2xx 抛 Exception("LLM API error: <code> <message> - <body>")——
 * 用前缀正则精确取状态码，避免对含响应体的全文做子串匹配而误判（如 400 响应体里出现 "401"）。
 */
private val LLM_ERROR_CODE_REGEX = Regex("^LLM API error: (\\d{3})")

private fun describeChatFailure(e: Throwable): String {
    val code = LLM_ERROR_CODE_REGEX.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "鉴权失败，请检查 API Key"
        else -> "失败：" + (e.message ?: e.javaClass.simpleName).take(DETAIL_MAX_LENGTH)
    }
}

/**
 * 分支二：未选模型时仅探测 GET {endpoint}/models。
 * 加固点：apiKey 非空才带 Authorization（不发 "Bearer " 空头）；除 isSuccessful 外再校验响应形如
 * 模型列表 JSON，避免 200 的 HTML 错误页被误判成功。
 */
private fun probeModelsEndpoint(
    endpoint: String,
    apiKey: String,
): ConnectionTestResult {
    // 先校验 URL 可解析再构造 Request：无 scheme 的端点（保存时无校验）会让 Request.Builder.url 抛
    // IllegalArgumentException——必须转成可读结果而不是让调用方崩
    val httpUrl =
        buildModelsUrl(endpoint).toHttpUrlOrNull()
            ?: return ConnectionTestResult(false, "端点格式无效，需以 http(s):// 开头")
    val client =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
    val requestBuilder = okhttp3.Request.Builder().url(httpUrl).get()
    // 空密钥不发鉴权头，兼容本地 Ollama 等无需鉴权的服务
    if (apiKey.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer $apiKey")

    return runCatching {
        client.newCall(requestBuilder.build()).execute().use { response ->
            classifyModelsProbeResponse(response.code, response.body?.string().orEmpty())
        }
    }.getOrElse { e ->
        when (e) {
            is SocketTimeoutException -> {
                android.util.Log.w("ProviderRepository", "probeModelsEndpoint 超时: ${e.message}")
                ConnectionTestResult(false, "连接超时")
            }
            else -> {
                android.util.Log.e("ProviderRepository", "probeModelsEndpoint error: ${e.message}", e)
                ConnectionTestResult(false, "失败：" + (e.message ?: e.javaClass.simpleName).take(DETAIL_MAX_LENGTH))
            }
        }
    }
}

/** {endpoint}/models 拼接：容忍用户填的尾斜杠差异。 */
private fun buildModelsUrl(endpoint: String): String {
    return if (endpoint.endsWith("/")) "${endpoint}models" else "$endpoint/models"
}

/** 把 /models 探测响应分类成用户可读结果（拆出以控制 probeModelsEndpoint 复杂度）。 */
private fun classifyModelsProbeResponse(
    code: Int,
    body: String,
): ConnectionTestResult =
    when {
        code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
            ConnectionTestResult(false, "鉴权失败，请检查 API Key")
        // 只有 2xx 且形如 JSON（含 "id" 字段或以 '{' 开头）的响应才判成功，防 HTML/JSON 错误体误报
        code in HTTP_OK_MIN..HTTP_OK_MAX && looksLikeModelsJson(body) ->
            ConnectionTestResult(true, "端点与密钥可用（未选模型，仅探测模型列表）")
        code in HTTP_OK_MIN..HTTP_OK_MAX ->
            ConnectionTestResult(false, "端点返回了非模型列表响应（HTTP $code）")
        else ->
            ConnectionTestResult(false, "端点不可用（HTTP $code）")
    }

private fun looksLikeModelsJson(body: String): Boolean = body.contains("\"id\"") || body.trimStart().startsWith("{")
