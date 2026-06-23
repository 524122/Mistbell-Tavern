package com.mistbell.tavern.android.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.Memory
import com.mistbell.tavern.android.data.repository.MemoryRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class MemoryForm(
    val content: String = "",
    val layer: String = "episodic",
    val type: String = "note",
    val importance: Double = 0.5,
    val stability: Double = 1.0,
    val subject: String = "",
    val relation: String = "",
    val objectValue: String = "",
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val emotionalAtmosphere: String = ""
)

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = MemoryRepository(application)

    private val _ownerId = MutableStateFlow("local-user")
    private val _characterId = MutableStateFlow("")

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories

    private val _layerFilter = MutableStateFlow<String?>(null)
    val layerFilter: StateFlow<String?> = _layerFilter

    private val _showForm = MutableStateFlow(false)
    val showForm: StateFlow<Boolean> = _showForm

    private val _editingMemoryId = MutableStateFlow<String?>(null)
    val editingMemoryId: StateFlow<String?> = _editingMemoryId

    private val _form = MutableStateFlow(MemoryForm())
    val form: StateFlow<MemoryForm> = _form

    private val _isBackfilling = MutableStateFlow(false)
    val isBackfilling: StateFlow<Boolean> = _isBackfilling

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val filteredMemories: StateFlow<List<Memory>> = combine(_memories, _layerFilter) { memories, layer ->
        if (layer != null) memories.filter { it.layer == layer } else memories
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun init(ownerId: String, characterId: String) {
        _ownerId.value = ownerId
        _characterId.value = characterId
        loadFromServer()
    }

    fun loadFromServer() {
        viewModelScope.launch {
            repo.loadFromServer(_ownerId.value, _characterId.value)
            repo.observeMemories(_ownerId.value, _characterId.value).first().let {
                _memories.value = it
            }
        }
    }

    fun setLayerFilter(layer: String?) {
        _layerFilter.value = layer
    }

    fun showNewForm() {
        _editingMemoryId.value = null
        _form.value = MemoryForm()
        _showForm.value = true
    }

    fun showEditForm(memory: Memory) {
        _editingMemoryId.value = memory.id
        _form.value = MemoryForm(
            content = memory.content,
            layer = memory.layer,
            type = memory.type,
            importance = memory.importance,
            stability = memory.stability,
            subject = memory.subject,
            relation = memory.relation,
            objectValue = memory.`object`,
            tags = memory.tags,
            aliases = memory.aliases
        )
        _showForm.value = true
    }

    fun updateForm(transform: MemoryForm.() -> MemoryForm) {
        _form.value = _form.value.transform()
    }

    fun saveMemory() {
        val f = _form.value
        if (f.content.isBlank()) {
            _message.value = "内容不能为空"
            return
        }

        viewModelScope.launch {
            if (_editingMemoryId.value != null) {
                val patch = buildJsonObject {
                    put("content", f.content)
                    put("layer", f.layer)
                    put("type", f.type)
                    put("importance", f.importance)
                    put("stability", f.stability)
                    put("subject", f.subject)
                    put("relation", f.relation)
                    put("object", f.objectValue)
                    put("tags", JsonArray(f.tags.map { JsonPrimitive(it) }))
                    put("aliases", JsonArray(f.aliases.map { JsonPrimitive(it) }))
                }
                repo.updateMemory(_editingMemoryId.value!!, patch)
            } else {
                val body = buildJsonObject {
                    put("ownerId", _ownerId.value)
                    put("characterId", _characterId.value)
                    put("content", f.content)
                    put("layer", f.layer)
                    put("type", f.type)
                    put("importance", f.importance)
                    put("stability", f.stability)
                    put("subject", f.subject)
                    put("relation", f.relation)
                    put("object", f.objectValue)
                    put("tags", JsonArray(f.tags.map { JsonPrimitive(it) }))
                    put("aliases", JsonArray(f.aliases.map { JsonPrimitive(it) }))
                    put("emotionalAtmosphere", f.emotionalAtmosphere)
                }
                repo.createMemory(body)
            }
            _showForm.value = false
            loadFromServer()
        }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            repo.deleteMemory(memoryId, _ownerId.value, _characterId.value)
            loadFromServer()
        }
    }

    fun backfill() {
        viewModelScope.launch {
            _isBackfilling.value = true
            val result = repo.backfillMemories(_ownerId.value, _characterId.value)
            _isBackfilling.value = false
            if (result != null) {
                _message.value = "回填完成"
                loadFromServer()
            } else {
                _message.value = "回填失败"
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
