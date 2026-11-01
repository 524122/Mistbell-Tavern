package com.mistbell.tavern.android.ui.extensionpack

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.repository.ExtensionPackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("TooGenericExceptionCaught") // 界面层统一兜住异常转用户可读消息，与 RulePackManagerViewModel 同形态
class ExtensionPackManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ExtensionPackRepository.forApplication(application.filesDir)

    val packs: StateFlow<List<ExtensionPackEntity>> =
        repo.observePacks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 待确认导入的预览（声明权限或携带 JS 制品时 UI 弹权限清单确认框，确认后 persist） */
    private val _pendingImport = MutableStateFlow<ExtensionPackRepository.ExtensionPreview?>(null)
    val pendingImport: StateFlow<ExtensionPackRepository.ExtensionPreview?> = _pendingImport.asStateFlow()

    /** 两段式导入第一段：读 zip 字节 → 解析校验；需要确认则挂起等用户，否则直接落库 */
    fun importPack(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes =
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("扩展包导入失败：无法打开所选文件")
                val preview = repo.previewFromBytes(bytes)
                if (preview.needsConfirmation) {
                    _pendingImport.value = preview
                } else {
                    finishImport(repo.persist(preview, confirmedByUser = true))
                }
            } catch (e: Exception) {
                _message.value = "导入失败: ${e.message}"
            }
        }
    }

    /** 用户「信任并导入」：本轮已对完整新权限清单确认，扩权重置不变量由仓库层按此放行 */
    fun confirmImport() {
        viewModelScope.launch {
            val preview = _pendingImport.value
            _pendingImport.value = null
            if (preview != null) {
                try {
                    finishImport(repo.persist(preview, confirmedByUser = true))
                } catch (e: Exception) {
                    _message.value = "导入失败: ${e.message}"
                }
            }
        }
    }

    fun dismissImport() {
        _pendingImport.value = null
        _message.value = "已取消导入"
    }

    private fun finishImport(result: ExtensionPackRepository.ImportResult) {
        val prefix =
            when (result.mode) {
                ExtensionPackStore.PersistMode.UPDATE_CONTENT -> "已更新（覆盖安装）："
                ExtensionPackStore.PersistMode.REFRESH_METADATA -> "已刷新元数据（内容未变化）："
                ExtensionPackStore.PersistMode.FRESH -> "已导入并启用："
            } + result.name
        _message.value =
            if (result.warnings.isEmpty()) {
                prefix
            } else {
                "$prefix（${result.warnings.size} 条警告：${result.warnings.first()}）"
            }
    }

    /** 启停切换：未确认权限的包仓库层拒绝（UI 同时禁开关），异常转提示 */
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            try {
                repo.setEnabled(id, enabled)
            } catch (e: Exception) {
                _message.value = "切换启用状态失败: ${e.message}"
            }
        }
    }

    fun deletePack(id: String) {
        viewModelScope.launch {
            try {
                repo.deletePack(id)
                _message.value = "扩展包已删除"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
