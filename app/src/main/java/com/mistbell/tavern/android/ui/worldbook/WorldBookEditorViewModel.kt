package com.mistbell.tavern.android.ui.worldbook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.api.model.WorldBookEntry
import com.mistbell.tavern.android.data.prompt.WorldBookPlacement
import com.mistbell.tavern.android.data.repository.WorldBookEntryDraft
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class WorldBookEntryForm(
    val comment: String = "",
    val keys: String = "",
    val content: String = "",
    val constant: Boolean = false,
    val disable: Boolean = false,
    val insertPosition: String = "before_prompt",
    val depth: Int = 0,
    val probability: Double = 1.0,
    val depthRole: String = "system",
    // 次关键词（逗号分隔文本）；与 selectiveLogic 联动（酒馆四态）
    val secondaryKeys: String = "",
    // 次关键词逻辑（酒馆 selectiveLogic）：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL
    val selectiveLogic: Int = 0,
    // 大小写敏感 / 全字匹配（酒馆条目级开关；缺省关）
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    // 扫描深度文本：空=跟随全局（酒馆 scanDepth null 语义）
    val scanDepth: String = "",
)

/** 插入位置显示名：存储值 → UI 文案（6 锚点与酒馆对齐） */
fun worldBookPositionLabel(insertPosition: String): String =
    when (insertPosition) {
        WorldBookPlacement.POSITION_BEFORE_PROMPT -> "角色定义前"
        WorldBookPlacement.POSITION_AFTER_PROMPT -> "角色定义后"
        WorldBookPlacement.POSITION_EM_BEFORE -> "示例消息前"
        WorldBookPlacement.POSITION_EM_AFTER -> "示例消息后"
        WorldBookPlacement.POSITION_AN_BEFORE -> "作者注释前"
        WorldBookPlacement.POSITION_AN_AFTER -> "作者注释后"
        else -> "角色定义后"
    }

/** @D 插入角色显示名（仅 depth≥1 时生效） */
fun depthRoleLabel(depthRole: String): String =
    when (depthRole) {
        WorldBookPlacement.DEPTH_ROLE_USER -> "用户"
        WorldBookPlacement.DEPTH_ROLE_ASSISTANT -> "角色"
        else -> "系统"
    }

/** 条目行位置摘要：锚点名，或 @D 档的 "[角色] @D深度"（列表页免点开即可见） */
fun entryPlacementSummary(
    insertPosition: String,
    depth: Int,
    depthRole: String,
): String =
    if (depth > 0) {
        "[${depthRoleLabel(depthRole)}] @D$depth"
    } else {
        worldBookPositionLabel(insertPosition)
    }

// ===== 插入位置下拉（酒馆式 9 选项：6 锚点 + [系统/用户/AI] 插入深度 @D）=====
// 存储结构不变（insertPosition + depth + depthRole）：@D 三档 = depth≥1 的 (depth, role) 组合，
// 此时 insertPosition 不参与语义（规划器按深度分组）；锚点档 = depth 归 0、仅 insertPosition 生效。

/** @D 档下拉键前缀（键形如 "at_depth:system"） */
private const val AT_DEPTH_KEY_PREFIX = "at_depth:"

/** 进入 @D 档时的缺省深度（对齐酒馆 world-info.js DEFAULT_DEPTH = 4） */
const val ST_DEFAULT_DEPTH = 4

/** 下拉 9 选项：（键， 显示文案），顺序与酒馆一致 */
fun placementOptions(): List<Pair<String, String>> =
    listOf(
        WorldBookPlacement.POSITION_BEFORE_PROMPT to "角色定义前",
        WorldBookPlacement.POSITION_AFTER_PROMPT to "角色定义后",
        WorldBookPlacement.POSITION_EM_BEFORE to "示例消息前",
        WorldBookPlacement.POSITION_EM_AFTER to "示例消息后",
        WorldBookPlacement.POSITION_AN_BEFORE to "作者注释前",
        WorldBookPlacement.POSITION_AN_AFTER to "作者注释后",
        AT_DEPTH_KEY_PREFIX + WorldBookPlacement.DEPTH_ROLE_SYSTEM to "[系统] 插入深度 @D",
        AT_DEPTH_KEY_PREFIX + WorldBookPlacement.DEPTH_ROLE_USER to "[用户] 插入深度 @D",
        AT_DEPTH_KEY_PREFIX + WorldBookPlacement.DEPTH_ROLE_ASSISTANT to "[AI] 插入深度 @D",
    )

/** 表单状态 → 下拉选中键：depth≥1 视为 @D 档（与规划器语义一致） */
fun placementSelectedKey(
    insertPosition: String,
    depth: Int,
    depthRole: String,
): String = if (depth > 0) AT_DEPTH_KEY_PREFIX + depthRole else insertPosition

/**
 * 下拉选择 → 表单状态 (insertPosition, depth, depthRole)。
 * 锚点档：depth 归 0；@D 档：保留已有深度，否则取酒馆缺省 4，insertPosition 保持原值（@D 下不参与语义）。
 */
fun placementFromSelection(
    key: String,
    currentInsertPosition: String,
    currentDepth: Int,
    currentDepthRole: String,
): Triple<String, Int, String> =
    if (key.startsWith(AT_DEPTH_KEY_PREFIX)) {
        Triple(
            currentInsertPosition,
            if (currentDepth > 0) currentDepth else ST_DEFAULT_DEPTH,
            key.removePrefix(AT_DEPTH_KEY_PREFIX),
        )
    } else {
        Triple(key, 0, currentDepthRole)
    }

/** 触发概率输入范围（应用内 0-1 小数；酒馆生态为 0-100 百分数，导入导出时经 StInterop 换算） */
const val MIN_PROBABILITY = 0.0

const val MAX_PROBABILITY = 1.0

class WorldBookEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = WorldBookRepository(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val allWorldBooks: StateFlow<List<WorldBook>> =
        repo.observeWorldBooks()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val worldBooks: StateFlow<List<WorldBook>> =
        combine(allWorldBooks, _searchQuery) { books, query ->
            if (query.isBlank()) {
                books
            } else {
                books.filter { book ->
                    book.name.contains(query, ignoreCase = true) ||
                        book.entries.any { entry ->
                            entry.comment.contains(query, ignoreCase = true) ||
                                entry.content.contains(query, ignoreCase = true) ||
                                entry.key.any { it.contains(query, ignoreCase = true) }
                        }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedBookId = MutableStateFlow<String?>(null)
    val selectedBookId: StateFlow<String?> = _selectedBookId

    val entries: StateFlow<List<WorldBookEntry>> =
        _selectedBookId.filterNotNull().flatMapLatest { bookId ->
            repo.observeEntries(bookId)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _showEntryForm = MutableStateFlow(false)
    val showEntryForm: StateFlow<Boolean> = _showEntryForm

    private val _editingEntryId = MutableStateFlow<String?>(null)
    val editingEntryId: StateFlow<String?> = _editingEntryId

    private val _entryForm = MutableStateFlow(WorldBookEntryForm())
    val entryForm: StateFlow<WorldBookEntryForm> = _entryForm

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _isEditingBook = MutableStateFlow(false)
    val isEditingBook: StateFlow<Boolean> = _isEditingBook

    fun selectBook(bookId: String) {
        _selectedBookId.value = bookId
    }

    fun clearSelectedBook() {
        _selectedBookId.value = null
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadFromServer() {
        viewModelScope.launch { repo.loadFromServer() }
    }

    fun createWorldBook(name: String) {
        viewModelScope.launch {
            val book = repo.createWorldBook(name)
            if (book != null) {
                _message.value = "世界书已创建"
            } else {
                _message.value = "创建失败"
            }
        }
    }

    fun deleteWorldBook(id: String) {
        viewModelScope.launch {
            repo.deleteWorldBook(id)
            if (_selectedBookId.value == id) _selectedBookId.value = null
        }
    }

    fun showNewEntryForm() {
        _editingEntryId.value = null
        _entryForm.value = WorldBookEntryForm()
        _showEntryForm.value = true
    }

    fun showEditEntryForm(entry: WorldBookEntry) {
        _editingEntryId.value = entry.id
        _entryForm.value =
            WorldBookEntryForm(
                comment = entry.comment,
                keys = entry.key.joinToString(", "),
                content = entry.content,
                constant = entry.constant,
                disable = entry.disable,
                insertPosition = entry.insertPosition,
                depth = entry.depth,
                probability = entry.probability,
                depthRole = entry.depthRole,
                secondaryKeys = entry.secondaryKeys.joinToString(", "),
                selectiveLogic = entry.selectiveLogic,
                caseSensitive = entry.caseSensitive ?: false,
                matchWholeWords = entry.matchWholeWords ?: false,
                scanDepth = entry.scanDepth?.toString() ?: "",
            )
        _showEntryForm.value = true
    }

    fun updateEntryForm(transform: WorldBookEntryForm.() -> WorldBookEntryForm) {
        _entryForm.value = _entryForm.value.transform()
    }

    fun hideEntryForm() {
        _showEntryForm.value = false
        _editingEntryId.value = null
        _entryForm.value = WorldBookEntryForm()
    }

    fun saveEntry() {
        val form = _entryForm.value
        val bookId = _selectedBookId.value ?: return
        val keyList = form.keys.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val secondaryKeyList = form.secondaryKeys.split(",").map { it.trim() }.filter { it.isNotBlank() }
        // 空串 = 跟随全局（patch 写 JsonNull 清空）；非法输入保持原值（不写入 patch）
        val scanDepthValue = form.scanDepth.trim().toIntOrNull()
        val scanDepthCleared = form.scanDepth.isBlank()

        viewModelScope.launch {
            if (_editingEntryId.value != null) {
                val patch = buildEntryPatch(form, secondaryKeyList, scanDepthValue, scanDepthCleared)
                repo.updateEntry(_editingEntryId.value!!, patch)
            } else {
                repo.createEntry(
                    bookId = bookId,
                    draft =
                        WorldBookEntryDraft(
                            comment = form.comment,
                            keys = keyList,
                            content = form.content,
                            constant = form.constant,
                            disable = form.disable,
                            insertPosition = form.insertPosition,
                            depth = form.depth,
                            probability = form.probability,
                            depthRole = form.depthRole,
                            secondaryKeys = secondaryKeyList,
                            selectiveLogic = form.selectiveLogic,
                            caseSensitive = form.caseSensitive,
                            matchWholeWords = form.matchWholeWords,
                            scanDepth = scanDepthValue.takeIf { !scanDepthCleared },
                        ),
                )
            }
            _showEntryForm.value = false
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch { repo.deleteEntry(entryId) }
    }

    fun updateEntry(
        entryId: String,
        patch: kotlinx.serialization.json.JsonObject,
    ) {
        viewModelScope.launch { repo.updateEntry(entryId, patch) }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** 表单 → 编辑 patch（只含表单覆盖的字段；scanDepth 空=JsonNull 清空=跟随全局） */
    @Suppress("LongParameterList") // 四个参数即表单语义平铺
    private fun buildEntryPatch(
        form: WorldBookEntryForm,
        secondaryKeyList: List<String>,
        scanDepthValue: Int?,
        scanDepthCleared: Boolean,
    ) = kotlinx.serialization.json.buildJsonObject {
        put("comment", kotlinx.serialization.json.JsonPrimitive(form.comment))
        put("content", kotlinx.serialization.json.JsonPrimitive(form.content))
        put("constant", kotlinx.serialization.json.JsonPrimitive(form.constant))
        put("disable", kotlinx.serialization.json.JsonPrimitive(form.disable))
        put("insertPosition", kotlinx.serialization.json.JsonPrimitive(form.insertPosition))
        put("depth", kotlinx.serialization.json.JsonPrimitive(form.depth))
        put("probability", kotlinx.serialization.json.JsonPrimitive(form.probability))
        put("depthRole", kotlinx.serialization.json.JsonPrimitive(form.depthRole))
        put(
            "secondaryKeys",
            kotlinx.serialization.json.JsonArray(secondaryKeyList.map { kotlinx.serialization.json.JsonPrimitive(it) }),
        )
        put("selectiveLogic", kotlinx.serialization.json.JsonPrimitive(form.selectiveLogic))
        put("caseSensitive", kotlinx.serialization.json.JsonPrimitive(form.caseSensitive))
        put("matchWholeWords", kotlinx.serialization.json.JsonPrimitive(form.matchWholeWords))
        when {
            scanDepthCleared -> put("scanDepth", kotlinx.serialization.json.JsonNull)
            scanDepthValue != null -> put("scanDepth", kotlinx.serialization.json.JsonPrimitive(scanDepthValue))
        }
    }
}
