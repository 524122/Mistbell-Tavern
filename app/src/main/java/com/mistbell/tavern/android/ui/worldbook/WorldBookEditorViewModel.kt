package com.mistbell.tavern.android.ui.worldbook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.api.model.WorldBookEntry
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
    val depth: Int = 1
)

class WorldBookEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = WorldBookRepository(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _allWorldBooks: StateFlow<List<WorldBook>> = repo.observeWorldBooks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val worldBooks: StateFlow<List<WorldBook>> = combine(_allWorldBooks, _searchQuery) { books, query ->
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

    val entries: StateFlow<List<WorldBookEntry>> = _selectedBookId.filterNotNull().flatMapLatest { bookId ->
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
            if (book != null) _message.value = "世界书已创建"
            else _message.value = "创建失败"
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
        _entryForm.value = WorldBookEntryForm(
            comment = entry.comment,
            keys = entry.key.joinToString(", "),
            content = entry.content,
            constant = entry.constant,
            disable = entry.disable,
            insertPosition = entry.insertPosition,
            depth = entry.depth
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

        viewModelScope.launch {
            if (_editingEntryId.value != null) {
                val patch = kotlinx.serialization.json.buildJsonObject {
                    put("comment", kotlinx.serialization.json.JsonPrimitive(form.comment))
                    put("content", kotlinx.serialization.json.JsonPrimitive(form.content))
                    put("constant", kotlinx.serialization.json.JsonPrimitive(form.constant))
                    put("disable", kotlinx.serialization.json.JsonPrimitive(form.disable))
                    put("insertPosition", kotlinx.serialization.json.JsonPrimitive(form.insertPosition))
                    put("depth", kotlinx.serialization.json.JsonPrimitive(form.depth))
                }
                repo.updateEntry(_editingEntryId.value!!, patch)
            } else {
                repo.createEntry(
                    bookId = bookId,
                    comment = form.comment,
                    keys = keyList,
                    content = form.content,
                    constant = form.constant,
                    disable = form.disable,
                    insertPosition = form.insertPosition,
                    depth = form.depth
                )
            }
            _showEntryForm.value = false
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch { repo.deleteEntry(entryId) }
    }

    fun updateEntry(entryId: String, patch: kotlinx.serialization.json.JsonObject) {
        viewModelScope.launch { repo.updateEntry(entryId, patch) }
    }

    fun clearMessage() { _message.value = null }
}
