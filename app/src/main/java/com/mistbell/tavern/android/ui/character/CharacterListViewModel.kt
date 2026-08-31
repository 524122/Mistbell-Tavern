package com.mistbell.tavern.android.ui.character

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.repository.CharacterRepository
import com.mistbell.tavern.android.util.CharacterExportFormat
import com.mistbell.tavern.android.util.CharacterExportResult
import com.mistbell.tavern.android.util.CharacterExporter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class CharacterListViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // 与 ChatListViewModel 等处保持一致的会话所有者
        private const val OWNER_ID = "local-user"
    }

    private val repository = CharacterRepository(application)
    private val db = TavernApplication.instance.database
    private val pinnedCharactersKey = "pinned_character_ids"

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _characters = repository.observeCharacters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedCharacterIds: StateFlow<Set<String>> = db.settingsDao().getAll()
        .map { settings ->
            val json = settings.firstOrNull { it.key == pinnedCharactersKey }?.value ?: "[]"
            decodePinnedIds(json)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredCharacters: StateFlow<List<Character>> = combine(
        _characters,
        _searchQuery,
        pinnedCharacterIds
    ) { characters, query, pinnedIds ->
        val filtered = if (query.isBlank()) {
            characters
        } else {
            characters.filter { character ->
                character.name.contains(query, ignoreCase = true) ||
                character.description.contains(query, ignoreCase = true) ||
                character.personality.contains(query, ignoreCase = true)
            }
        }
        filtered.sortedWith(
            compareByDescending<Character> { pinnedIds.contains(it.id) }
                .thenBy { it.name }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // 每个角色的真实会话数（ownerId 与其他会话查询保持一致）
    val sessionCounts: StateFlow<Map<String, Int>> = db.sessionDao()
        .observeSessionCounts(OWNER_ID)
        .map { counts -> counts.associate { it.characterId to it.sessionCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteCharacter(characterId: String) {
        viewModelScope.launch {
            try {
                repository.deleteCharacter(characterId)
                _message.value = "角色已删除"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    fun copyCharacter(context: Context, character: Character) {
        val content = buildString {
            appendLine(character.name.ifBlank { "未命名角色" })
            if (character.description.isNotBlank()) appendLine("描述：${character.description}")
            if (character.personality.isNotBlank()) appendLine("性格：${character.personality}")
            if (character.scenario.isNotBlank()) appendLine("场景：${character.scenario}")
            if (character.firstMes.isNotBlank()) appendLine("开场白：${character.firstMes}")
        }.trim()

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(character.name.ifBlank { "角色" }, content))
        _message.value = "角色信息已复制"
    }

    fun togglePin(characterId: String) {
        viewModelScope.launch {
            val current = loadPinnedIds()
            val updated = if (current.contains(characterId)) {
                current - characterId
            } else {
                current + characterId
            }
            savePinnedIds(updated)
            _message.value = if (updated.contains(characterId)) "角色已置顶" else "已取消置顶"
        }
    }

    fun exportCharacter(
        context: Context,
        character: Character,
        format: CharacterExportFormat,
        fileName: String,
        onComplete: (CharacterExportResult?) -> Unit
    ) {
        viewModelScope.launch {
            val result = when (format) {
                CharacterExportFormat.JSON -> CharacterExporter.exportToJson(context, character, fileName)
                CharacterExportFormat.PNG -> CharacterExporter.exportToPng(context, character, fileName)
            }
            _message.value = result?.let { "已保存到 ${it.location}" } ?: "导出失败"
            onComplete(result)
        }
    }

    fun importCharacter(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val importResult = com.mistbell.tavern.android.util.CharacterImporter.importFromJson(context, uri)
                if (importResult != null) {
                    val characterEntity = importResult.character
                    // 注意：不要把角色描述/性格/开场白等全文打进 logcat（隐私）
                    android.util.Log.d("CharacterImport", "Parsed character: ${characterEntity.name}")

                    // 保存世界书（如果有）
                    if (importResult.worldBook != null) {
                        android.util.Log.d("CharacterImport", "Saving world book: ${importResult.worldBook.name} with ${importResult.worldBookEntries.size} entries")
                        val db = com.mistbell.tavern.android.TavernApplication.instance.database
                        db.worldBookDao().upsertBook(importResult.worldBook)
                        if (importResult.worldBookEntries.isNotEmpty()) {
                            db.worldBookDao().upsertEntries(importResult.worldBookEntries)
                        }
                    }

                    // 保存角色到本地数据库
                    repository.createCharacter(characterEntity.toDomain())

                    val worldBookInfo = if (importResult.worldBook != null) {
                        "（含 ${importResult.worldBookEntries.size} 条世界书条目）"
                    } else ""
                    _message.value = "成功导入角色：${characterEntity.name}$worldBookInfo"
                } else {
                    _message.value = "导入失败：无法解析 JSON 文件"
                }
            } catch (e: Exception) {
                android.util.Log.e("CharacterImport", "Import error", e)
                _message.value = "导入失败: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private suspend fun loadPinnedIds(): Set<String> {
        return try {
            val json = db.settingsDao().getValue(pinnedCharactersKey) ?: "[]"
            decodePinnedIds(json)
        } catch (_: Exception) {
            emptySet()
        }
    }

    private suspend fun savePinnedIds(ids: Set<String>) {
        val json = Json.encodeToString(ListSerializer(serializer<String>()), ids.toList())
        db.settingsDao().upsert(SettingsEntity(pinnedCharactersKey, json))
    }

    private fun decodePinnedIds(json: String): Set<String> {
        return try {
            Json.decodeFromString(ListSerializer(serializer<String>()), json).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
