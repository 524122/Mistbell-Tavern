package com.mistbell.tavern.android.ui.regex

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.regex.CardRegexDocument
import com.mistbell.tavern.android.data.regex.GlobalRegexStore
import com.mistbell.tavern.android.data.regex.RegexPreviewRequest
import com.mistbell.tavern.android.data.regex.RegexRuleDraft
import com.mistbell.tavern.android.ui.character.CharacterRegexUiState
import com.mistbell.tavern.android.ui.character.RegexEditingContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_RULE_NAME = "未命名"

/**
 * 全局正则编辑宿主：与卡级管理共用编辑器组件（RegexEditingContract），
 * 持久化目标是 global_regex_rules 整表替换，作用域为全部会话。
 */
class GlobalRegexViewModel(application: Application) :
    AndroidViewModel(application),
    RegexEditingContract {
    private val db = (application as TavernApplication).container.database
    private val _state = MutableStateFlow(CharacterRegexUiState(characterName = SCOPE_LABEL))
    val state: StateFlow<CharacterRegexUiState> = _state.asStateFlow()

    private var savedDocument = CardRegexDocument()
    private var isLoaded = false
    private val nextRuleId: Int
        get() = (_state.value.document.rules.maxOfOrNull { it.id } ?: -1) + 1

    fun load() {
        if (isLoaded) return
        isLoaded = true
        viewModelScope.launch {
            runCatching { db.globalRegexDao().getAll() }
                .onSuccess { rules ->
                    savedDocument = GlobalRegexStore.toDocument(rules)
                    _state.value =
                        CharacterRegexUiState(
                            characterName = SCOPE_LABEL,
                            document = savedDocument,
                            selectedRuleId = savedDocument.rules.firstOrNull()?.id,
                            isLoaded = true,
                        )
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _state.update { it.copy(isLoaded = true, message = "加载全局正则失败：${error.message}") }
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
                db.globalRegexDao().replaceAll(GlobalRegexStore.fromDrafts(document.rules, System.currentTimeMillis()))
            }.onSuccess {
                savedDocument = document
                _state.update { state ->
                    state.copy(isSaving = false, isDirty = false, message = "已保存 ${document.rules.size} 条全局正则")
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
        mutateDocument { document -> document.copy(rules = rules.filterNot { it.id == id }) }
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

    private companion object {
        const val SCOPE_LABEL = "全局 · 所有会话"
    }
}
