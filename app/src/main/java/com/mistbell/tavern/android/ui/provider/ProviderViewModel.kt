package com.mistbell.tavern.android.ui.provider

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.ProviderConfig
import com.mistbell.tavern.android.data.repository.ProviderRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ProviderForm(
    val id: String = "",
    val name: String = "",
    val type: String = "openai",
    val endpoint: String = "",
    val apiKey: String = "",
    val selectedModel: String = "",
    val embeddingModel: String = "",
    val summaryModel: String = "",
    val memoryModel: String = "",
    val context1M: Boolean = false,
    // 高级采样参数覆盖（null = 不覆盖，交由全局预设）
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
    val maxTokens: Int? = null
)

class ProviderViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ProviderRepository(application)

    val providers: StateFlow<List<ProviderConfig>> = repo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activeProviderId: StateFlow<String> = repo.observeActiveProviderId()
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    val activeModelId: StateFlow<String> = repo.observeActiveModelId()
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    private val _form = MutableStateFlow(ProviderForm())
    val form: StateFlow<ProviderForm> = _form

    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels

    private val _testResult = MutableStateFlow<Boolean?>(null)
    val testResult: StateFlow<Boolean?> = _testResult

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun loadProvider(id: String) {
        android.util.Log.d("ProviderViewModel", "loadProvider called with id: $id")

        viewModelScope.launch {
            // 先从当前状态尝试加载
            val currentProviders = providers.value
            android.util.Log.d("ProviderViewModel", "current providers count: ${currentProviders.size}")

            var provider = currentProviders.find { it.id == id }

            // 如果当前状态没有，等待加载
            if (provider == null) {
                android.util.Log.d("ProviderViewModel", "waiting for providers to load...")
                val loadedProviders = providers.first { it.isNotEmpty() }
                provider = loadedProviders.find { it.id == id }
            }

            if (provider != null) {
                android.util.Log.d("ProviderViewModel", "found provider: ${provider.name}")
                _form.value = ProviderForm(
                    id = provider.id,
                    name = provider.name,
                    type = provider.type,
                    endpoint = provider.endpoint,
                    apiKey = provider.apiKey,
                    selectedModel = provider.selectedModel,
                    embeddingModel = provider.embeddingModel,
                    summaryModel = provider.summaryModel,
                    memoryModel = provider.memoryModel,
                    context1M = provider.context1M,
                    temperature = provider.temperature,
                    topP = provider.topP,
                    topK = provider.topK,
                    frequencyPenalty = provider.frequencyPenalty,
                    maxTokens = provider.maxTokens
                )
                android.util.Log.d("ProviderViewModel", "form updated with name: ${_form.value.name}")
            } else {
                android.util.Log.e("ProviderViewModel", "provider not found with id: $id")
            }
        }
    }

    fun resetForm() {
        _form.value = ProviderForm()
        _fetchedModels.value = emptyList()
        _testResult.value = null
    }

    fun updateForm(transform: ProviderForm.() -> ProviderForm) {
        _form.value = _form.value.transform()
    }

    fun fetchModels() {
        val f = _form.value
        viewModelScope.launch {
            _isFetchingModels.value = true
            val models = repo.fetchModels(f.endpoint, f.apiKey, f.type)
            _fetchedModels.value = models
            _isFetchingModels.value = false
            if (models.isEmpty()) _message.value = "未获取到模型列表"
        }
    }

    fun testConnection() {
        val f = _form.value
        viewModelScope.launch {
            _testResult.value = null
            val ok = repo.testConnection(f.endpoint, f.apiKey, f.type)
            _testResult.value = ok
            _message.value = if (ok) "连接成功" else "连接失败"
        }
    }

    fun testConnectionForProvider(endpoint: String, apiKey: String, type: String) {
        viewModelScope.launch {
            _testResult.value = null
            val ok = repo.testConnection(endpoint, apiKey, type)
            _testResult.value = ok
        }
    }

    fun saveProvider() {
        val f = _form.value
        if (f.name.isBlank()) { _message.value = "名称不能为空"; return }

        viewModelScope.launch {
            val current = providers.value.toMutableList()
            val existing = current.find { it.id == f.id }
            val config = ProviderConfig(
                id = f.id.ifBlank { UUID.randomUUID().toString() },
                name = f.name, type = f.type, endpoint = f.endpoint, apiKey = f.apiKey,
                selectedModel = f.selectedModel, embeddingModel = f.embeddingModel,
                summaryModel = f.summaryModel, memoryModel = f.memoryModel, context1M = f.context1M,
                temperature = f.temperature, topP = f.topP, topK = f.topK,
                frequencyPenalty = f.frequencyPenalty, maxTokens = f.maxTokens
            )

            if (existing != null) {
                val idx = current.indexOf(existing)
                current[idx] = config
            } else {
                current.add(config)
            }
            repo.saveProviders(current)
            _message.value = "提供商已保存"
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            val current = providers.value.filter { it.id != id }
            repo.saveProviders(current)
            if (activeProviderId.value == id) {
                repo.setActiveProvider("", "")
            }
        }
    }

    fun setActiveProvider(providerId: String, modelId: String) {
        viewModelScope.launch { repo.setActiveProvider(providerId, modelId) }
    }

    fun clearMessage() { _message.value = null }
}
