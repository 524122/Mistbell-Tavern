package com.mistbell.tavern.android.ui.character

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.regex.CardRegexDocument
import com.mistbell.tavern.android.data.regex.RegexPreviewRequest
import com.mistbell.tavern.android.data.regex.RegexRuleDraft
import com.mistbell.tavern.android.data.repository.CharacterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_RULE_NAME = "未命名"

data class CharacterRegexUiState(
    val characterName: String = "",
    val document: CardRegexDocument = CardRegexDocument(),
    val selectedRuleId: Int? = null,
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val message: String? = null,
    val preview: RegexPreviewRequest = RegexPreviewRequest(),
)

class CharacterRegexViewModel(application: Application) :
    AndroidViewModel(application),
    RegexEditingContract {
    private val characterRepo = CharacterRepository(application)
    private val _state = MutableStateFlow(CharacterRegexUiState())
    val state: StateFlow<CharacterRegexUiState> = _state.asStateFlow()

    private var loadedCharacterId: String? = null
    private var savedDocument = CardRegexDocument()
    private val nextRuleId: Int
        get() = (_state.value.document.rules.maxOfOrNull { it.id } ?: -1) + 1

    fun loadCharacter(id: String) {
        if (loadedCharacterId == id) return
        loadedCharacterId = id
        viewModelScope.launch {
            runCatching {
                characterRepo.observeCharacters().first().firstOrNull { it.id == id }
            }.onSuccess { character ->
                if (character == null) {
                    _state.update { it.copy(isLoaded = true, notFound = true) }
                } else {
                    savedDocument = CardRegexDocument.fromExtensions(character.data?.extensions)
                    _state.value =
                        CharacterRegexUiState(
                            characterName = character.name,
                            document = savedDocument,
                            selectedRuleId = savedDocument.rules.firstOrNull()?.id,
                            isLoaded = true,
                        )
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(isLoaded = true, message = "加载正则失败：${error.message}") }
            }
        }
    }

    override fun save() {
        val current = _state.value
        if (current.isSaving) return
        val error = current.document.validationErrors().firstOrNull()
        if (error != null) {
            _state.update { it.copy(message = error) }
            return
        }
        val document = current.document
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            runCatching {
                val id = requireNotNull(loadedCharacterId) { "角色不存在" }
                val character =
                    characterRepo.observeCharacters().first().firstOrNull { it.id == id }
                        ?: error("角色不存在或已删除")
                val data =
                    character.data?.copy(extensions = document.updatedExtensions(character.data.extensions))
                        ?: CharacterData(extensions = document.updatedExtensions(null))
                characterRepo.createCharacter(character.copy(data = data))
                character.name
            }.onSuccess { name ->
                savedDocument = document
                _state.update { state ->
                    val remaining = state.document != savedDocument
                    state.copy(
                        characterName = name,
                        isSaving = false,
                        isDirty = remaining,
                        message =
                            if (remaining) {
                                "已保存，当前还有未保存修改"
                            } else {
                                "已保存 ${document.rules.size} 条正则"
                            },
                    )
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(isSaving = false, message = "保存失败：${error.message}") }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun selectRule(id: Int) {
        if (_state.value.document.rules.any { it.id == id }) {
            _state.update { it.copy(selectedRuleId = id) }
        }
    }

    override fun addRule(sourceId: Int?) {
        val source = _state.value.document.rules.firstOrNull { it.id == sourceId }
        if (sourceId != null && source == null) return
        val id = nextRuleId
        val rule =
            source?.copy(
                id = id,
                name = "${source.name.ifBlank { DEFAULT_RULE_NAME }} 副本",
                original = null,
            ) ?: RegexRuleDraft(id = id, name = "规则 ${id + 1}", findRegex = "")
        mutateDocument { it.insert(rule, _state.value.selectedRuleId) }
        _state.update { it.copy(selectedRuleId = id) }
    }

    override fun deleteRule(id: Int) {
        val rules = _state.value.document.rules
        val index = rules.indexOfFirst { it.id == id }
        if (index < 0) return
        mutateDocument { document ->
            document.copy(rules = rules.filterNot { it.id == id })
        }
        _state.update { state ->
            state.copy(selectedRuleId = state.document.rules.getOrNull(index.coerceAtMost(rules.lastIndex))?.id)
        }
    }

    override fun moveRule(
        id: Int,
        offset: Int,
    ) {
        val rules = _state.value.document.rules
        val index = rules.indexOfFirst { it.id == id }
        val destination = index + offset
        if (index < 0 || destination !in rules.indices) return
        mutateDocument { document ->
            document.copy(rules = rules.toMutableList().apply { add(destination, removeAt(index)) })
        }
    }

    override fun updateRule(
        id: Int,
        transform: RegexRuleDraft.() -> RegexRuleDraft,
    ) {
        mutateDocument { document ->
            document.copy(rules = document.rules.map { if (it.id == id) it.transform() else it })
        }
    }

    override fun updatePreview(transform: RegexPreviewRequest.() -> RegexPreviewRequest) {
        _state.update { it.copy(preview = it.preview.transform()) }
    }

    private fun mutateDocument(transform: (CardRegexDocument) -> CardRegexDocument) {
        _state.update { state ->
            val updated = transform(state.document)
            state.copy(document = updated, isDirty = updated != savedDocument)
        }
    }
}
