package com.mistbell.tavern.android.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.MemoryType
import com.mistbell.tavern.android.data.api.model.SourceType
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class StructuredMemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StructuredMemoryRepository(application)

    private val _ownerId = MutableStateFlow("local-user")
    private val _characterId = MutableStateFlow<String?>(null)
    private val _sessionId = MutableStateFlow<String?>(null)

    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val memories: StateFlow<List<StructuredMemory>> = combine(
        _ownerId,
        _characterId,
        _sessionId,
        _typeFilter,
        _searchQuery
    ) { ownerId, _, sessionId, typeFilter, query ->
        if (sessionId.isNullOrBlank()) {
            return@combine flowOf(emptyList())
        }

        when {
            query.isNotBlank() -> repository.searchMemoriesBySession(ownerId, sessionId, query)
            typeFilter != null -> repository.getMemoriesBySessionAndType(ownerId, sessionId, typeFilter)
            else -> repository.getMemoriesBySession(ownerId, sessionId)
        }
    }.flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _editingMemory = MutableStateFlow<StructuredMemory?>(null)
    val editingMemory: StateFlow<StructuredMemory?> = _editingMemory.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun init(ownerId: String, characterId: String? = null, sessionId: String? = null) {
        _ownerId.value = ownerId
        _characterId.value = characterId
        _sessionId.value = sessionId
    }

    fun setTypeFilter(type: String?) {
        _typeFilter.value = type
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun showCreateDialog() {
        if (_sessionId.value.isNullOrBlank()) {
            _message.value = "请从具体对话进入记忆管理"
            return
        }
        _showCreateDialog.value = true
    }

    fun hideCreateDialog() {
        _showCreateDialog.value = false
        _editingMemory.value = null
    }

    fun editMemory(memory: StructuredMemory) {
        _editingMemory.value = memory
        _showCreateDialog.value = true
    }

    fun createMemory(
        memoryType: String,
        title: String,
        content: String,
        importance: Int,
        tags: List<String>
    ) {
        viewModelScope.launch {
            try {
                val sessionId = _sessionId.value
                if (sessionId.isNullOrBlank()) {
                    _message.value = "请从具体对话进入记忆管理"
                    return@launch
                }

                val memory = StructuredMemory(
                    ownerId = _ownerId.value,
                    characterId = _characterId.value,
                    sessionId = sessionId,
                    memoryType = memoryType,
                    title = title,
                    content = content,
                    structuredData = null,
                    importance = importance,
                    tags = tags,
                    keywords = emptyList(),
                    createdAt = "",
                    updatedAt = "",
                    lastAccessedAt = null,
                    accessCount = 0,
                    relatedMessageIds = emptyList(),
                    sourceType = SourceType.MANUAL
                )
                repository.createMemory(memory)
                _message.value = "记忆创建成功"
                hideCreateDialog()
            } catch (e: Exception) {
                _message.value = "创建失败: ${e.message}"
            }
        }
    }

    fun updateMemory(
        id: Long,
        memoryType: String,
        title: String,
        content: String,
        importance: Int,
        tags: List<String>
    ) {
        viewModelScope.launch {
            try {
                val existing = _editingMemory.value ?: return@launch
                val updated = existing.copy(
                    memoryType = memoryType,
                    title = title,
                    content = content,
                    importance = importance,
                    tags = tags
                )
                repository.updateMemory(updated)
                _message.value = "记忆更新成功"
                hideCreateDialog()
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteMemory(id)
                _message.value = "记忆已删除"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
