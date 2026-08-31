package com.mistbell.tavern.android.ui.character

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.repository.CharacterRepository
import com.mistbell.tavern.android.data.repository.ThemePackRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

data class CharacterForm(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val color: String = "#6C5CE7",
    val avatarData: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val creator: String = "",
    val characterVersion: String = "1.0",
    val worldBookId: String = "",
    val themeId: String = "",
    val customGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)

class CharacterEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val characterRepo = CharacterRepository(application)
    private val worldBookRepo = WorldBookRepository(application)

    private val _form = MutableStateFlow(CharacterForm())
    val form: StateFlow<CharacterForm> = _form

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val worldBooks: StateFlow<List<WorldBook>> = worldBookRepo.observeWorldBooks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val themePackRepo = ThemePackRepository(application)

    val availableThemes: StateFlow<List<ThemePackEntity>> = themePackRepo.observePacks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var editingCharacterId: String? = null

    fun loadCharacter(id: String) {
        viewModelScope.launch {
            // 直接从本地数据库加载角色
            characterRepo.observeCharacters().first().find { it.id == id }?.let { char ->
                editingCharacterId = char.id
                _isEditing.value = true
                _form.value = CharacterForm(
                    name = char.name,
                    description = char.description,
                    personality = char.personality,
                    scenario = char.scenario,
                    firstMes = char.firstMes,
                    mesExample = char.mesExample,
                    color = char.color.ifBlank { "#6C5CE7" },
                    avatarData = char.avatarData,
                    systemPrompt = char.data?.systemPrompt ?: "",
                    postHistoryInstructions = char.data?.postHistoryInstructions ?: "",
                    creatorNotes = char.data?.creatorNotes ?: "",
                    creator = char.data?.creator ?: "",
                    characterVersion = char.data?.characterVersion ?: "1.0",
                    worldBookId = char.worldBookId,
                    themeId = char.themeId
                )
            }
        }
    }

    fun updateForm(transform: CharacterForm.() -> CharacterForm) {
        _form.value = _form.value.transform()
    }

    fun saveCharacter() {
        val f = _form.value
        if (f.name.isBlank()) {
            _message.value = "名称不能为空"
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val characterData = CharacterData(
                    systemPrompt = f.systemPrompt,
                    postHistoryInstructions = f.postHistoryInstructions,
                    creatorNotes = f.creatorNotes,
                    creator = f.creator,
                    characterVersion = f.characterVersion
                )

                val character = Character(
                    id = editingCharacterId ?: UUID.randomUUID().toString(),
                    name = f.name,
                    role = "assistant",
                    description = f.description,
                    personality = f.personality,
                    scenario = f.scenario,
                    firstMes = f.firstMes,
                    mesExample = f.mesExample,
                    color = f.color,
                    avatarData = f.avatarData,
                    worldBookId = f.worldBookId,
                    themeId = f.themeId,
                    data = characterData
                )

                // 直接保存到本地数据库
                characterRepo.createCharacter(character)

                _saved.value = true
                _message.value = "保存成功"
            } catch (e: Exception) {
                android.util.Log.e("CharacterEditor", "Save error", e)
                _message.value = "保存失败: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
