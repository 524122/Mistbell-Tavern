// 下划线前缀私有 StateFlow（_ownerId 等）是 Compose/Flow 的背压私有态约定，
// 不做重命名（波及全文件引用且无行为收益），对本文件豁免 property-naming。
@file:Suppress("ktlint:standard:property-naming")

package com.mistbell.tavern.android.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.SourceType
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 新建/编辑记忆时用户可改的字段。
 *
 * 收成一个对象：字段再长（比如以后加"重要度来源"），ViewModel 与编辑对话框的签名都不用跟着变。
 * 会话内长期记忆：一切记忆都归属当前会话（owner + session），不再有跨会话档位。
 */
data class MemoryDraft(
    val memoryType: String,
    val title: String,
    val content: String,
    val importance: Int,
    val tags: List<String>,
)

class StructuredMemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StructuredMemoryRepository(application)

    private val _ownerId = MutableStateFlow("local-user")
    private val _characterId = MutableStateFlow<String?>(null)
    private val _sessionId = MutableStateFlow<String?>(null)

    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 可见记忆 = 当前会话的记忆（owner + session）。
     *
     * 类型/关键词筛选在**这一份集合上**做，而不是各走一条 SQL：否则多条查询的口径得各自维护，
     * 很容易出现"按类型一筛，某些记忆就消失了"这类不一致。
     */
    val memories: StateFlow<List<StructuredMemory>> =
        combine(
            _ownerId,
            _characterId,
            _sessionId,
            _typeFilter,
            _searchQuery,
        ) { ownerId, characterId, sessionId, typeFilter, query ->
            MemoryQuery(ownerId, characterId, sessionId, typeFilter, query)
        }.flatMapLatest { q ->
            if (q.sessionId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                repository.getMemoriesVisibleInSession(q.ownerId, q.characterId.orEmpty(), q.sessionId)
                    .map { all -> all.filter { q.matches(it) } }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 列表筛选条件：类型精确匹配 + 标题/正文关键词（大小写不敏感） */
    private data class MemoryQuery(
        val ownerId: String,
        val characterId: String?,
        val sessionId: String?,
        val typeFilter: String?,
        val query: String,
    ) {
        fun matches(memory: StructuredMemory): Boolean {
            val typeMatches = typeFilter == null || memory.memoryType == typeFilter
            val queryMatches =
                query.isBlank() ||
                    memory.title?.contains(query, ignoreCase = true) == true ||
                    memory.content.contains(query, ignoreCase = true)
            return typeMatches && queryMatches
        }
    }

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _editingMemory = MutableStateFlow<StructuredMemory?>(null)
    val editingMemory: StateFlow<StructuredMemory?> = _editingMemory.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun init(
        ownerId: String,
        characterId: String? = null,
        sessionId: String? = null,
    ) {
        _ownerId.value = ownerId
        // characterId 仅作展示归因保留，不再参与查询与召回（可见集合只看 owner + session）
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

    fun createMemory(draft: MemoryDraft) {
        viewModelScope.launch {
            try {
                val sessionId = _sessionId.value
                if (sessionId.isNullOrBlank()) {
                    _message.value = "请从具体对话进入记忆管理"
                    return@launch
                }

                val memory =
                    StructuredMemory(
                        ownerId = _ownerId.value,
                        characterId = _characterId.value,
                        // 会话内长期记忆：一切记忆都归属当前会话
                        sessionId = sessionId,
                        memoryType = draft.memoryType,
                        title = draft.title,
                        content = draft.content,
                        structuredData = null,
                        importance = draft.importance,
                        tags = draft.tags,
                        keywords = emptyList(),
                        createdAt = "",
                        updatedAt = "",
                        lastAccessedAt = null,
                        accessCount = 0,
                        relatedMessageIds = emptyList(),
                        sourceType = SourceType.MANUAL,
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
        draft: MemoryDraft,
    ) {
        viewModelScope.launch {
            try {
                val existing = _editingMemory.value ?: return@launch
                // 归属创建时确定、之后不可变：编辑只改内容，记忆始终留在当前会话
                val targetSessionId =
                    _sessionId.value?.takeIf { it.isNotBlank() } ?: run {
                        _message.value = "请从具体对话进入记忆管理"
                        return@launch
                    }
                val updated =
                    existing.copy(
                        memoryType = draft.memoryType,
                        title = draft.title,
                        content = draft.content,
                        importance = draft.importance,
                        tags = draft.tags,
                        sessionId = targetSessionId,
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
                val sessionId = _sessionId.value
                if (sessionId.isNullOrBlank()) {
                    _message.value = "请从具体对话进入记忆管理"
                    return@launch
                }
                // 归属校验在仓库层（owner + session 必须与被删记忆一致），这里按当前会话声明
                repository.deleteMemory(id, _ownerId.value, sessionId)
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
