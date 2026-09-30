package com.mistbell.tavern.android.ui.character

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.repository.CharacterRepository
import com.mistbell.tavern.android.util.CharacterExportFormat
import com.mistbell.tavern.android.util.CharacterExportResult
import com.mistbell.tavern.android.util.CharacterExporter
import com.mistbell.tavern.android.util.StSyncImporter
import com.mistbell.tavern.android.util.SyncReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class CharacterListViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // 与 ChatListViewModel 等处保持一致的会话所有者
        private const val OWNER_ID = "local-user"
    }

    private val repository = CharacterRepository(application)
    private val db = TavernApplication.instance.container.database
    private val pinnedCharactersKey = "pinned_character_ids"

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val charactersFlow =
        repository.observeCharacters()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedCharacterIds: StateFlow<Set<String>> =
        db.settingsDao().getAll()
            .map { settings ->
                val json = settings.firstOrNull { it.key == pinnedCharactersKey }?.value ?: "[]"
                decodePinnedIds(json)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredCharacters: StateFlow<List<Character>> =
        combine(
            charactersFlow,
            _searchQuery,
            pinnedCharacterIds,
        ) { characters, query, pinnedIds ->
            val filtered =
                if (query.isBlank()) {
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
                    .thenBy { it.name },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // 批量导入：进行中标志 + 结果报告（null = 无待展示报告）
    private val _isBatchImporting = MutableStateFlow(false)
    val isBatchImporting: StateFlow<Boolean> = _isBatchImporting.asStateFlow()

    private val _batchImportReport = MutableStateFlow<SyncReport?>(null)
    val batchImportReport: StateFlow<SyncReport?> = _batchImportReport.asStateFlow()

    /**
     * 从酒馆数据文件夹批量导入角色卡 + 世界书（PC 侧文件为准）。
     * 已存在的角色/世界书按文件版更新并保留本地 id（聊天记录不断链），重复导入不产生重复数据。
     */
    fun importFromFolder(treeUri: android.net.Uri) {
        if (_isBatchImporting.value) return
        viewModelScope.launch {
            _isBatchImporting.value = true
            try {
                _batchImportReport.value =
                    withContext(Dispatchers.IO) {
                        StSyncImporter.syncFromFolder(getApplication(), treeUri, db)
                    }
            } catch (e: Exception) {
                android.util.Log.e("StSync", "批量导入失败", e)
                _message.value = "批量导入失败: ${e.message}"
            } finally {
                _isBatchImporting.value = false
            }
        }
    }

    fun clearBatchImportReport() {
        _batchImportReport.value = null
    }

    // 每个角色的真实会话数（ownerId 与其他会话查询保持一致）
    val sessionCounts: StateFlow<Map<String, Int>> =
        db.sessionDao()
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

    fun copyCharacter(
        context: Context,
        character: Character,
    ) {
        val content =
            buildString {
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
            val updated =
                if (current.contains(characterId)) {
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
        onComplete: (CharacterExportResult?) -> Unit,
    ) {
        viewModelScope.launch {
            // 世界书随卡导出（character_book）：解析角色挂接的书与条目，无书则不附带该键
            val bookId = character.worldBookId.takeIf { it.isNotBlank() }
            val book = bookId?.let { db.worldBookDao().getById(it) }
            val bookName = book?.name
            val bookEntries = book?.let { db.worldBookDao().getEntriesList(it.id) }.orEmpty()
            val result =
                when (format) {
                    CharacterExportFormat.JSON ->
                        CharacterExporter.exportToJson(context, character, fileName, bookName, bookEntries)
                    CharacterExportFormat.PNG ->
                        CharacterExporter.exportToPng(context, character, fileName, bookName, bookEntries)
                }
            _message.value = result?.let { "已保存到 ${it.location}" } ?: "导出失败"
            onComplete(result)
        }
    }

    fun importCharacter(
        context: android.content.Context,
        uri: android.net.Uri,
    ) {
        viewModelScope.launch {
            try {
                // 双路导入：PNG 埋卡优先（卡片 JSON 内嵌于 tEXt chunk），失败退回纯 JSON
                val importResult =
                    com.mistbell.tavern.android.util.CharacterImporter.importFromPng(context, uri)
                        ?: com.mistbell.tavern.android.util.CharacterImporter.importFromJson(context, uri)
                if (importResult != null) {
                    var characterEntity = importResult.character
                    // 注意：不要把角色描述/性格/开场白等全文打进 logcat（隐私）
                    android.util.Log.d("CharacterImport", "Parsed character: ${characterEntity.name}")

                    // 保存世界书（如果有）：按书名匹配——同名书原地更新（保 id、保既有链接），否则新建。
                    // 此前每次导入都新建副本，同名书会越堆越多，且旧副本仍是旧版映射的深度数据
                    var bookUpdatedExisting = false
                    if (importResult.worldBook != null) {
                        android.util.Log.d(
                            "CharacterImport",
                            "Saving world book: ${importResult.worldBook.name} with ${importResult.worldBookEntries.size} entries",
                        )
                        val parsedBook = importResult.worldBook
                        val existingBook =
                            db.worldBookDao().getAllBooksOnce()
                                .firstOrNull { it.name.trim() == parsedBook.name.trim() }
                        if (existingBook != null) {
                            db.withTransaction {
                                db.worldBookDao().upsertBook(existingBook.copy(name = parsedBook.name))
                                db.worldBookDao().deleteEntriesByBookId(existingBook.id)
                                if (importResult.worldBookEntries.isNotEmpty()) {
                                    db.worldBookDao()
                                        .upsertEntries(importResult.worldBookEntries.map { it.copy(bookId = existingBook.id) })
                                }
                            }
                            characterEntity = characterEntity.copy(worldBookId = existingBook.id)
                            bookUpdatedExisting = true
                        } else {
                            db.worldBookDao().upsertBook(parsedBook)
                            if (importResult.worldBookEntries.isNotEmpty()) {
                                db.worldBookDao().upsertEntries(importResult.worldBookEntries)
                            }
                        }
                    }

                    // 保存角色：按名匹配——同名角色原地更新（保 id=聊天记录不断、保本地颜色/主题），否则新建
                    val existingCharacter =
                        db.characterDao().getAllOnce()
                            .firstOrNull { it.name.trim() == characterEntity.name.trim() }
                    var updatedExisting = false
                    if (existingCharacter != null) {
                        val merged =
                            com.mistbell.tavern.android.util.applyCardToExisting(
                                existingCharacter,
                                importResult,
                                characterEntity.worldBookId,
                            )
                        repository.createCharacter(merged.toDomain())
                        updatedExisting = true
                    } else {
                        repository.createCharacter(characterEntity.toDomain())
                    }

                    val worldBookInfo =
                        if (importResult.worldBook != null) {
                            "（世界书${if (bookUpdatedExisting) "更新" else "新增"} ${importResult.worldBookEntries.size} 条）"
                        } else {
                            ""
                        }
                    // 导入诊断明细：仅记录提示条数与内容摘要到 logcat（不含卡片正文，隐私）
                    if (importResult.warnings.isNotEmpty()) {
                        importResult.warnings.forEach { warning ->
                            android.util.Log.i("CharacterImport", "导入提示: $warning")
                        }
                    }
                    // 提示消息升级：附加 k 条提示（不含卡内容）
                    val warningsInfo =
                        if (importResult.warnings.isNotEmpty()) {
                            "（${importResult.warnings.size} 条提示）"
                        } else {
                            ""
                        }
                    val action = if (updatedExisting) "已更新角色" else "成功导入角色"
                    _message.value = "$action：${characterEntity.name}$worldBookInfo$warningsInfo"
                } else {
                    _message.value = "导入失败：无法解析角色卡文件（支持 JSON 与 PNG 埋卡）"
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
