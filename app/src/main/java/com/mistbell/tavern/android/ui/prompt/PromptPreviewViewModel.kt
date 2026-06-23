package com.mistbell.tavern.android.ui.prompt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.SessionSummary
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.repository.CharacterRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class PromptPreviewViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val api get() = ApiClient.getApi(context)
    private val characterRepo = CharacterRepository(context)
    private val worldBookRepo = WorldBookRepository(context)

    val characters: StateFlow<List<Character>> = characterRepo.observeCharacters()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val worldBooks: StateFlow<List<WorldBook>> = worldBookRepo.observeWorldBooks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedCharacterId = MutableStateFlow("")
    val selectedCharacterId: StateFlow<String> = _selectedCharacterId

    private val _selectedWorldBookId = MutableStateFlow("")
    val selectedWorldBookId: StateFlow<String> = _selectedWorldBookId

    private val _testMessage = MutableStateFlow("")
    val testMessage: StateFlow<String> = _testMessage

    private val _previewResult = MutableStateFlow<JsonObject?>(null)
    val previewResult: StateFlow<JsonObject?> = _previewResult

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        viewModelScope.launch {
            characterRepo.loadCharactersFromServer()
            worldBookRepo.loadFromServer()
        }
    }

    fun setSelectedCharacterId(id: String) { _selectedCharacterId.value = id }
    fun setSelectedWorldBookId(id: String) { _selectedWorldBookId.value = id }
    fun setTestMessage(msg: String) { _testMessage.value = msg }

    fun preview() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val body = buildJsonObject {
                    put("ownerId", "local-user")
                    put("characterId", _selectedCharacterId.value)
                    if (_selectedWorldBookId.value.isNotBlank()) put("worldBookId", _selectedWorldBookId.value)
                    put("message", _testMessage.value)
                }
                val result = api.promptPreview(body)
                _previewResult.value = result as? JsonObject
            } catch (e: Exception) {
                _message.value = "预览失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
