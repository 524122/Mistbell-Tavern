package com.mistbell.tavern.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiConfigDocument
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult
import com.mistbell.tavern.android.data.api.ApiRequestTemplate
import com.mistbell.tavern.android.data.api.normalizeApiType
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.repository.ApiConfigMigration
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

/**
 * API 配置管理 ViewModel
 */
@Suppress("TooManyFunctions") // CRUD + 测试连接 + 模型拉取的聚合 VM
class ApiConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.container.database
    private val apiConfigDao = db.apiConfigDao()
    private val apiTestService = com.mistbell.tavern.android.data.api.ApiTestService()

    // 所有 API 配置
    val apiConfigs: StateFlow<List<ApiConfig>> =
        apiConfigDao.observeAll()
            .map { entities -> entities.map { it.toApiConfig() } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    // 当前选中的 API 索引
    private val _currentApiIndex = MutableStateFlow(0)
    val currentApiIndex: StateFlow<Int> = _currentApiIndex.asStateFlow()

    // 当前 API 配置
    val currentApiConfig: StateFlow<ApiConfig?> =
        combine(
            apiConfigs,
            currentApiIndex,
        ) { configs, index ->
            configs.getOrNull(index)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    // 默认 API 配置
    val defaultApiConfig: StateFlow<ApiConfig?> =
        apiConfigDao.observeDefault()
            .map { it?.toApiConfig() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null,
            )

    // API 配置数量
    val apiConfigCount: StateFlow<Int> =
        apiConfigDao.observeCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0,
            )

    // 消息状态
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 旧 providers_json 链路 → api_configs 的一次性迁移（幂等，先于默认配置创建）
        viewModelScope.launch {
            ApiConfigMigration.migrateIfNeeded(db)
            // v25→v26 rows have an empty document column. Backfill every legacy row through
            // the same pure mapper used by the editor; this keeps config_json authoritative.
            apiConfigDao.getAll().forEach { entity ->
                // 非空但不可解析/未来版本的 configJson 必须保留原文，不能回填成旧字段后覆盖。
                val document = ApiConfigJson.fromEntityForBackfill(entity)
                if (document == null) {
                    // configJson 原文不动；apiKey 加密、可清理的 apiUrl 去凭据仍可独立处理。
                    val normalized =
                        entity.copy(
                            apiKey = SecureStore.wrap(entity.apiKey),
                            apiUrl = ApiConfigJson.sanitizeBaseUrl(entity.apiUrl) ?: entity.apiUrl,
                        )
                    if (normalized.apiKey != entity.apiKey || normalized.apiUrl != entity.apiUrl) {
                        apiConfigDao.update(normalized)
                    }
                    return@forEach
                }
                val normalized =
                    entity.copy(
                        apiKey = SecureStore.wrap(entity.apiKey),
                        // URL 列与 document 同步；否则 api_url 会继续保留旧 query 凭据/旧地址
                        apiUrl = document.baseUrl,
                        configJson =
                            ApiConfigJson.format(
                                document.copy(apiKeyRef = if (entity.apiKey.isBlank()) "" else "secure:${entity.id}"),
                            ),
                    )
                if (normalized.apiKey != entity.apiKey ||
                    normalized.configJson != entity.configJson ||
                    normalized.apiUrl != entity.apiUrl
                ) {
                    apiConfigDao.update(normalized)
                }
            }
        }
        // 初始化：如果没有配置，创建默认配置
        viewModelScope.launch {
            if (apiConfigDao.getCount() == 0) {
                createDefaultApiConfig()
            }
            // 设置当前索引为默认配置
            val defaultConfig = apiConfigDao.getDefault()
            if (defaultConfig != null) {
                val configs = apiConfigDao.getAll()
                _currentApiIndex.value = configs.indexOfFirst { it.id == defaultConfig.id }.coerceAtLeast(0)
            }
        }
    }

    /**
     * 创建默认 API 配置
     */
    private suspend fun createDefaultApiConfig() {
        val defaultConfig =
            ApiConfigEntity(
                id = UUID.randomUUID().toString(),
                name = "OpenAI API",
                apiUrl = "https://api.openai.com/v1",
                apiKey = "",
                model = "gpt-4o",
                isDefault = true,
                streamingEnabled = true,
                sortOrder = 0,
                configJson =
                    ApiConfigJson.format(
                        ApiConfigJson.fromFields(
                            id = "",
                            name = "OpenAI API",
                            type = "openai",
                            baseUrl = "https://api.openai.com/v1",
                            model = "gpt-4o",
                            hasApiKey = false,
                            streamingEnabled = true,
                            contextTokenLimit = ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
                            request = ApiRequestTemplate.templateFor("openai"),
                        ).copy(id = ""),
                    ),
            )
        apiConfigDao.insert(defaultConfig.copy(configJson = ApiConfigJson.format(ApiConfigJson.fromEntity(defaultConfig))))
    }

    /**
     * 添加 API 配置
     */
    @Suppress("LongParameterList") // 编辑对话框表单字段直传
    fun addApiConfig(
        name: String,
        apiUrl: String,
        apiKey: String,
        model: String,
        setAsDefault: Boolean = false,
        type: String = "openai",
        context1M: Boolean = false,
        streamingEnabled: Boolean = true,
        contextTokenLimit: Int = if (context1M) ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT else ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
        configJson: String = "",
    ) {
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val document =
                    parseOrBuildDocument(
                        configJson = configJson,
                        id = id,
                        name = name,
                        type = type,
                        apiUrl = apiUrl,
                        model = model,
                        streamingEnabled = streamingEnabled,
                        contextTokenLimit = contextTokenLimit,
                        hasApiKey = apiKey.isNotBlank(),
                    )
                val newConfig =
                    ApiConfigEntity(
                        id = id,
                        name = document.name,
                        // 只持久化清理后的 baseUrl：query 里的 ?key=/token= 属于凭据，真实密钥存 SecureStore
                        apiUrl = document.baseUrl,
                        apiKey = SecureStore.wrap(apiKey),
                        model = document.model,
                        isDefault = false,
                        sortOrder = apiConfigDao.getCount(),
                        type = document.type,
                        context1m = context1M,
                        streamingEnabled = streamingEnabled,
                        configJson = ApiConfigJson.format(document),
                    )
                apiConfigDao.insert(newConfig)

                if (setAsDefault) {
                    setDefaultApiConfig(newConfig.id)
                }

                _message.value = "已添加 API 配置"
            } catch (e: Exception) {
                _message.value = "添加失败: ${e.message}"
            }
        }
    }

    /**
     * 更新 API 配置
     */
    fun updateApiConfig(apiConfig: ApiConfig) {
        viewModelScope.launch {
            try {
                if (apiConfig.configJson.isNotBlank() && ApiConfigJson.parse(apiConfig.configJson) is ApiConfigJsonResult.Invalid) {
                    throw IllegalArgumentException("配置 JSON 校验失败，未保存")
                }
                val entity = apiConfig.toEntity()
                apiConfigDao.update(entity.copy(updatedAt = System.currentTimeMillis()))
                _message.value = "已更新 API 配置"
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }

    /**
     * 静默更新默认配置的文档（模型参数区块专用）：只改 configJson/updatedAt 两列，
     * 不动 apiKey；成功不写 _message（滑块连续调整不触发 snackbar），失败才提示。
     */
    fun updateDefaultConfigDocument(transform: (ApiConfigDocument) -> ApiConfigDocument) {
        viewModelScope.launch {
            try {
                val entity = apiConfigDao.getDefault() ?: return@launch
                val next =
                    transform(ApiConfigJson.fromEntity(entity)).copy(
                        apiKeyRef = if (entity.apiKey.isBlank()) "" else "secure:${entity.id}",
                    )
                ApiConfigJson.validate(next)?.let {
                    _message.value = "参数未保存: $it"
                    return@launch
                }
                apiConfigDao.update(
                    entity.copy(
                        configJson = ApiConfigJson.format(next),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                _message.value = "参数保存失败: ${e.message}"
            }
        }
    }

    /**
     * 删除 API 配置
     */
    fun deleteApiConfig(id: String) {
        viewModelScope.launch {
            try {
                val config = apiConfigDao.getById(id)
                if (config?.isDefault == true && apiConfigDao.getCount() > 1) {
                    // 如果删除的是默认配置，自动设置第一个为默认
                    val allConfigs = apiConfigDao.getAll()
                    val nextDefault = allConfigs.firstOrNull { it.id != id }
                    nextDefault?.let {
                        apiConfigDao.setDefault(it.id)
                    }
                }
                apiConfigDao.deleteById(id)
                _message.value = "已删除 API 配置"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    /**
     * 设置默认 API 配置
     */
    fun setDefaultApiConfig(id: String) {
        viewModelScope.launch {
            try {
                apiConfigDao.setDefault(id)
                _message.value = "已设置为默认配置"
            } catch (e: Exception) {
                _message.value = "设置失败: ${e.message}"
            }
        }
    }

    /**
     * 测试 API 连接
     */
    fun testConnection(apiConfig: ApiConfig) {
        viewModelScope.launch {
            try {
                // 更新状态为测试中
                apiConfigDao.updateTestStatus(
                    id = apiConfig.id,
                    status = "TESTING",
                    time = System.currentTimeMillis(),
                )

                // 执行真实的 API 测试（按配置的接口类型走对应协议）
                val result =
                    apiTestService.testConnection(
                        apiUrl = apiConfig.apiUrl,
                        apiKey = apiConfig.apiKey,
                        model = apiConfig.model,
                        type = apiConfig.type,
                    )

                // 根据测试结果更新状态
                when (result) {
                    is com.mistbell.tavern.android.data.api.ApiTestResult.Success -> {
                        apiConfigDao.updateTestStatus(
                            id = apiConfig.id,
                            status = "SUCCESS",
                            time = System.currentTimeMillis(),
                        )
                        _message.value = "✅ ${result.message}"
                    }
                    is com.mistbell.tavern.android.data.api.ApiTestResult.Error -> {
                        apiConfigDao.updateTestStatus(
                            id = apiConfig.id,
                            status = "FAILED",
                            time = System.currentTimeMillis(),
                        )
                        _message.value = "❌ ${result.message}"
                    }
                }
            } catch (e: Exception) {
                apiConfigDao.updateTestStatus(
                    id = apiConfig.id,
                    status = "FAILED",
                    time = System.currentTimeMillis(),
                )
                _message.value = "测试失败: ${ApiConfigJson.redactUrl(e.message.orEmpty())}"
            }
        }
    }

    // ---- 编辑对话框：获取模型 / 对话框内连接测试 ----

    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels.asStateFlow()

    private val _fetchingModels = MutableStateFlow(false)
    val fetchingModels: StateFlow<Boolean> = _fetchingModels.asStateFlow()

    private val _fetchModelsError = MutableStateFlow<String?>(null)
    val fetchModelsError: StateFlow<String?> = _fetchModelsError.asStateFlow()

    /**
     * 从网关拉取模型列表（按接口类型适配端点与认证方式）
     */
    fun fetchModels(
        apiUrl: String,
        apiKey: String,
        type: String,
    ) {
        viewModelScope.launch {
            _fetchingModels.value = true
            _fetchModelsError.value = null
            _fetchedModels.value = emptyList()
            try {
                when (
                    val result =
                        apiTestService.fetchModels(
                            apiUrl = apiUrl,
                            apiKey = apiKey,
                            type = type,
                        )
                ) {
                    is com.mistbell.tavern.android.data.api.FetchModelsResult.Success -> {
                        _fetchedModels.value = result.models
                        if (result.models.isEmpty()) {
                            _fetchModelsError.value = "网关未返回模型列表"
                        }
                    }
                    is com.mistbell.tavern.android.data.api.FetchModelsResult.Error -> {
                        _fetchModelsError.value = result.message
                    }
                }
            } catch (e: Exception) {
                _fetchModelsError.value = "获取模型失败: ${e.message}"
            } finally {
                _fetchingModels.value = false
            }
        }
    }

    /**
     * 编辑对话框内的连接测试（不落库状态，结果经回调交对话框行内展示）
     */
    fun testConnectionInDialog(
        apiUrl: String,
        apiKey: String,
        model: String,
        type: String,
        onResult: (com.mistbell.tavern.android.data.api.ApiTestResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result =
                apiTestService.testConnection(
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    model = model,
                    type = type,
                )
            onResult(result)
        }
    }

    /**
     * 设置当前选中的 API 索引
     */
    fun setCurrentApiIndex(index: Int) {
        _currentApiIndex.value = index
    }

    /**
     * 清除消息
     */
    fun clearMessage() {
        _message.value = null
    }

    /**
     * 切换到上一张卡片
     */
    fun previousCard() {
        val configs = apiConfigs.value
        if (configs.isNotEmpty() && _currentApiIndex.value > 0) {
            _currentApiIndex.value--
        }
    }

    /**
     * 切换到下一张卡片
     */
    fun nextCard() {
        val configs = apiConfigs.value
        if (_currentApiIndex.value < configs.size - 1) {
            _currentApiIndex.value++
        }
    }
}

/**
 * 实体转换为领域模型
 */
private fun ApiConfigEntity.toApiConfig(): ApiConfig {
    val document = ApiConfigJson.fromEntity(this)
    return ApiConfig(
        id = id,
        name = document.name,
        apiUrl = document.baseUrl,
        apiKey = SecureStore.unwrap(apiKey),
        model = document.model,
        isDefault = isDefault,
        lastTestStatus =
            when (lastTestStatus) {
                "TESTING" -> ConnectionStatus.TESTING
                "SUCCESS" -> ConnectionStatus.SUCCESS
                "FAILED" -> ConnectionStatus.FAILED
                else -> ConnectionStatus.UNKNOWN
            },
        lastTestTime = lastTestTime,
        type = document.type,
        context1M = document.contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT,
        streamingEnabled = document.streamingEnabled,
        contextTokenLimit = document.contextTokenLimit,
        configJson = ApiConfigJson.format(ApiConfigJson.redacted(document, apiKey.isNotBlank())),
    )
}

/**
 * 领域模型转换为实体
 */
private fun ApiConfig.toEntity(): ApiConfigEntity {
    val document =
        parseOrBuildDocument(
            configJson = configJson,
            id = id,
            name = name,
            type = type,
            apiUrl = apiUrl,
            model = model,
            streamingEnabled = streamingEnabled,
            contextTokenLimit = contextTokenLimit,
            hasApiKey = apiKey.isNotBlank(),
        )
    return ApiConfigEntity(
        id = id,
        name = document.name,
        apiUrl = document.baseUrl,
        apiKey = SecureStore.wrap(apiKey),
        model = document.model,
        isDefault = isDefault,
        lastTestStatus =
            when (lastTestStatus) {
                ConnectionStatus.TESTING -> "TESTING"
                ConnectionStatus.SUCCESS -> "SUCCESS"
                ConnectionStatus.FAILED -> "FAILED"
                ConnectionStatus.UNKNOWN -> "UNKNOWN"
            },
        lastTestTime = lastTestTime,
        type = document.type,
        context1m = document.contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT,
        streamingEnabled = document.streamingEnabled,
        configJson = ApiConfigJson.format(document.copy(apiKeyRef = if (apiKey.isBlank()) "" else "secure:$id")),
        // 待实现：实现排序逻辑，目前默认 sortOrder = 0
        sortOrder = 0,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )
}

private fun parseOrBuildDocument(
    configJson: String,
    id: String,
    name: String,
    type: String,
    apiUrl: String,
    model: String,
    streamingEnabled: Boolean,
    contextTokenLimit: Int,
    hasApiKey: Boolean,
): com.mistbell.tavern.android.data.api.ApiConfigDocument {
    val sanitizedBaseUrl =
        ApiConfigJson.sanitizeBaseUrl(apiUrl)
            ?: throw IllegalArgumentException("API 地址包含无法安全清理的凭据或格式错误，请修正后再保存")
    val parsed = (ApiConfigJson.parse(configJson) as? ApiConfigJsonResult.Valid)?.document
    val base =
        parsed ?: ApiConfigJson.fromFields(
            id = id,
            name = name,
            type = type,
            baseUrl = apiUrl,
            model = model,
            hasApiKey = hasApiKey,
            streamingEnabled = streamingEnabled,
            contextTokenLimit = contextTokenLimit,
            request = ApiRequestTemplate.templateFor(type),
        )
    return base.copy(
        id = id,
        name = name,
        type = normalizeApiType(type),
        baseUrl = sanitizedBaseUrl,
        model = model,
        streamingEnabled = streamingEnabled,
        contextTokenLimit = contextTokenLimit.coerceIn(ApiConfigJson.MIN_CONTEXT_TOKEN_LIMIT, ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT),
        apiKeyRef = if (hasApiKey) "secure:$id" else "",
    )
}
