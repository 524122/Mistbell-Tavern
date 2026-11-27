package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.RequestSamplingParams
import com.mistbell.tavern.android.data.api.normalizeApiType
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Suppress("TooManyFunctions") // KV 仓库按域分组（LLM/生成/主题渲染/界面模式），读写实为同一表的三态存取
class SettingsRepository {
    private val db get() = TavernApplication.instance.container.database

    // --- LLM Config (local) ---

    suspend fun getLlmConfig(): LlmConfig =
        withContext(Dispatchers.IO) {
            val dao = db.settingsDao()
            // 三元组真相源：api_configs 默认配置（卡片式配置页写入）；表为空时回退
            // llm_* 平铺键（历史遗留，仅老版本数据库中存在）
            buildBaseConfig(normalizeApiConfig(db.apiConfigDao().getDefault()), dao)
        }

    /** Resolve a specific session/provider configuration through the same document authority. */
    suspend fun getLlmConfig(apiConfig: com.mistbell.tavern.android.data.local.entity.ApiConfigEntity?): LlmConfig =
        withContext(Dispatchers.IO) {
            buildBaseConfig(normalizeApiConfig(apiConfig), db.settingsDao())
        }

    private suspend fun normalizeApiConfig(
        entity: com.mistbell.tavern.android.data.local.entity.ApiConfigEntity?,
    ): com.mistbell.tavern.android.data.local.entity.ApiConfigEntity? {
        if (entity == null) return null
        // 非空但不可解析/未来版本的 configJson 必须保留原文，不能自动覆盖未知 request。
        val document = ApiConfigJson.fromEntityForBackfill(entity)
        val normalized =
            if (document == null) {
                // 独立列仍可安全处理：apiKey 加密、可清理的 apiUrl 去凭据；configJson 原文不动。
                entity.copy(
                    apiKey = SecureStore.wrap(entity.apiKey),
                    apiUrl = ApiConfigJson.sanitizeBaseUrl(entity.apiUrl) ?: entity.apiUrl,
                )
            } else {
                entity.copy(
                    apiKey = SecureStore.wrap(entity.apiKey),
                    apiUrl = document.baseUrl,
                    configJson =
                        ApiConfigJson.format(
                            document.copy(
                                apiKeyRef = if (entity.apiKey.isBlank()) "" else "secure:${entity.id}",
                            ),
                        ),
                )
            }
        if (normalized.apiKey != entity.apiKey ||
            normalized.configJson != entity.configJson ||
            normalized.apiUrl != entity.apiUrl
        ) {
            db.apiConfigDao().update(normalized)
        }
        return normalized
    }

    // 基础 LlmConfig 组装：优先取 api_configs 默认配置的 url/key/model，缺省回退 llm_* 平铺键
    private suspend fun buildBaseConfig(
        defaultApiConfig: com.mistbell.tavern.android.data.local.entity.ApiConfigEntity?,
        dao: SettingsDao,
    ): LlmConfig {
        val document = defaultApiConfig?.let(ApiConfigJson::fromEntity)
        val request = document?.request
        val globalStreamingEnabled = dao.getValue("streaming_enabled") != "0"
        val type = normalizeApiType(document?.type ?: defaultApiConfig?.type ?: ApiType.OPENAI)
        // 不同协议的采样键位置不同：Gemini 在 generationConfig 内，其余在顶层。
        // 这里统一解析成 LlmConfig，预览/诊断/请求体才能与实际发送值同源。
        val sampling = RequestSamplingParams.read(request, type)
        val thinkingDisabled =
            ((request?.get("thinking") as? JsonObject)?.get("type") as? JsonPrimitive)
                ?.content
                ?.equals("disabled", ignoreCase = true) == true
        val runtime = document?.runtime
        val baseValues = resolveBaseConfigValues(document, defaultApiConfig, dao)
        return LlmConfig(
            baseUrl = baseValues.baseUrl,
            apiKey = baseValues.apiKey,
            model = baseValues.model,
            type = type,
            temperature = sampling.temperature ?: LlmConfig.DEFAULT_TEMPERATURE,
            maxTokens = sampling.maxTokens ?: LlmConfig.DEFAULT_MAX_TOKENS,
            topP = sampling.topP,
            topK = sampling.topK,
            frequencyPenalty = sampling.frequencyPenalty,
            timeoutSeconds = runtime?.timeoutSeconds ?: LlmConfig.DEFAULT_TIMEOUT_SECONDS,
            retries = runtime?.retries ?: LlmConfig.DEFAULT_RETRIES,
            disableThinking = thinkingDisabled,
            streamingEnabled =
                document?.streamingEnabled
                    ?: defaultApiConfig?.streamingEnabled
                    ?: globalStreamingEnabled,
            request = request ?: kotlinx.serialization.json.buildJsonObject { },
            contextTokenLimit = document?.contextTokenLimit ?: ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
        )
    }

    private data class BaseConfigValues(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private suspend fun resolveBaseConfigValues(
        document: com.mistbell.tavern.android.data.api.ApiConfigDocument?,
        defaultApiConfig: com.mistbell.tavern.android.data.local.entity.ApiConfigEntity?,
        dao: SettingsDao,
    ): BaseConfigValues =
        BaseConfigValues(
            baseUrl =
                document?.baseUrl
                    ?: defaultApiConfig?.apiUrl
                    ?: dao.getValue("llm_base_url")
                    ?: "",
            apiKey =
                defaultApiConfig?.let { SecureStore.unwrap(it.apiKey) }
                    ?: SecureStore.unwrap(dao.getValue("llm_api_key") ?: ""),
            model =
                document?.model
                    ?: defaultApiConfig?.model
                    ?: dao.getValue("llm_model")
                    ?: "",
        )

    // --- 生成与记忆默认值（settings KV）---

    // 流式输出开关：缺省视为开启（仅显式写 "0" 才关闭）
    suspend fun isStreamingEnabled(): Boolean =
        withContext(Dispatchers.IO) {
            db.settingsDao().getValue("streaming_enabled") != "0"
        }

    // 新会话默认上下文 token 预算：非法或缺省回退 4096（解析实现收敛到 ChatSettingsResolver）
    suspend fun defaultContextTokens(): Int =
        withContext(Dispatchers.IO) {
            ChatSettingsResolver.globalDefaultContextTokens(db.settingsDao().getValue("default_context_tokens"))
        }

    // --- 主题渲染引擎（M4/M6，THEME_FRAMEWORK_DESIGN.md §7 总开关）---

    /**
     * 原生渲染引擎开关（聊天设置页）：缺省开启（仅显式写 "0" 才关闭）。默认 true 安全——
     * 无 layout 能力的主题包时开关无任何效果（降级路由"无包即 legacy"）。
     */
    fun observeLayoutRendererEnabled(): Flow<Boolean> {
        val stored = db.settingsDao().observeValue(KEY_LAYOUT_RENDERER_ENABLED)
        return stored.map { it != "0" }
    }

    suspend fun setLayoutRendererEnabled(enabled: Boolean) =
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(
                    KEY_LAYOUT_RENDERER_ENABLED,
                    if (enabled) "1" else "0",
                ),
            )
        }

    // --- 界面模式（P3「简洁模式是执行边界」，docs/UI_MODE.md）---

    /**
     * 界面模式（缺省 [UiMode.ADVANCED]）：本项只落设置项与执行闸门，缺省行为与历史
     * 版本完全一致；全新安装默认简洁、升级首启弹选择是 P3 第 2 项。
     */
    fun observeUiMode(): Flow<UiMode> = db.settingsDao().observeValue(KEY_UI_MODE).map { UiMode.fromSetting(it) }

    suspend fun setUiMode(mode: UiMode) =
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(KEY_UI_MODE, mode.storedValue),
            )
        }

    /**
     * 升级首启模式选择弹窗的一次性标记（P3 第 2 项）：确认与关掉都写——
     * 关掉弹窗的语义是「保持预选项」（预选项先经 [setUiMode] 落库），此后不再弹。
     */
    suspend fun markUiModePrompted() =
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(KEY_UI_MODE_PROMPTED, "1"),
            )
        }

    companion object {
        const val KEY_LAYOUT_RENDERER_ENABLED = "layout_renderer_enabled"

        /** 界面模式（"simple" / "advanced"；缺省与无法识别一律按高级执行） */
        const val KEY_UI_MODE = "ui_mode"

        /** 升级首启模式选择弹窗已弹出标记（一次性；"1" = 已弹过，无论确认还是关掉） */
        const val KEY_UI_MODE_PROMPTED = "ui_mode_prompted"
    }
}
