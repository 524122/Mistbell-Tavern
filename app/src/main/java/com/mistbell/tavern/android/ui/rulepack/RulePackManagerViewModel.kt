package com.mistbell.tavern.android.ui.rulepack

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.local.entity.RulePackEntity
import com.mistbell.tavern.android.data.repository.RulePackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("TooGenericExceptionCaught") // 界面层统一兜住异常转用户可读消息，与 ThemeManagerViewModel 同形态
class RulePackManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = RulePackRepository(application)

    val packs: StateFlow<List<RulePackEntity>> =
        repo.observePacks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 含可执行脚本的待确认导入（UI 弹确认框；确认后 confirmScriptImport 落库） */
    private val _pendingScriptImport =
        MutableStateFlow<com.mistbell.tavern.android.data.rulepack.RulePackParser.Bundle?>(null)
    val pendingScriptImport: StateFlow<com.mistbell.tavern.android.data.rulepack.RulePackParser.Bundle?> =
        _pendingScriptImport.asStateFlow()

    fun importPack(uri: Uri) {
        viewModelScope.launch {
            try {
                val bundle = repo.previewFromUri(uri)
                if (bundle.content.scriptSource != null) {
                    // 含可执行脚本：先弹确认（代码执行风险），确认后才落库
                    _pendingScriptImport.value = bundle
                } else {
                    finishImport(repo.persist(bundle))
                }
            } catch (e: Exception) {
                _message.value = "导入失败: ${e.message}"
            }
        }
    }

    fun confirmScriptImport() {
        viewModelScope.launch {
            val bundle = _pendingScriptImport.value
            _pendingScriptImport.value = null
            if (bundle != null) {
                try {
                    finishImport(repo.persist(bundle))
                } catch (e: Exception) {
                    _message.value = "导入失败: ${e.message}"
                }
            }
        }
    }

    fun dismissScriptImport() {
        _pendingScriptImport.value = null
        _message.value = "已取消导入"
    }

    private fun finishImport(result: RulePackRepository.ImportResult) {
        _message.value =
            if (result.warnings.isEmpty()) {
                "已导入并启用：${result.name}"
            } else {
                "已导入：${result.name}（${result.warnings.size} 条警告：${result.warnings.first()}）"
            }
    }

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
                _message.value = "规则包已删除"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    fun exportPack(id: String) {
        viewModelScope.launch {
            try {
                val file = repo.exportPack(id)
                val context = getApplication<Application>()
                val uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, file.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                val chooser =
                    Intent.createChooser(intent, "分享规则包")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(chooser)
            } catch (e: Exception) {
                _message.value = "分享失败: ${e.message}"
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
