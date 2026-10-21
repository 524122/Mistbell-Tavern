package com.mistbell.tavern.android.ui.quickreply

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.local.entity.QuickReplyEntity
import com.mistbell.tavern.android.data.repository.QuickReplyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QuickReplyManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = QuickReplyRepository()

    val items: StateFlow<List<QuickReplyEntity>> =
        repo.observeGlobal()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun save(
        id: String?,
        label: String,
        action: String,
        payload: String,
    ) {
        viewModelScope.launch {
            if (label.isBlank() || payload.isBlank()) {
                _message.value = "按钮文案与内容不能为空"
                return@launch
            }
            repo.upsert(
                QuickReplyEntity(
                    id = id ?: repo.newId(),
                    label = label.trim(),
                    action = action,
                    payload = payload,
                    orderIndex = items.value.size,
                ),
            )
            _message.value = "已保存"
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            _message.value = "已删除"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
