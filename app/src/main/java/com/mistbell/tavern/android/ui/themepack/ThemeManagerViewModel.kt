package com.mistbell.tavern.android.ui.themepack

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.repository.ThemePackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThemeManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val themeRepo = ThemePackRepository(application)

    val packs: StateFlow<List<ThemePackEntity>> =
        themeRepo.observePacks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeThemeId: StateFlow<String?> =
        themeRepo.observeActiveThemeId()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun importPack(uri: Uri) {
        viewModelScope.launch {
            try {
                val id = themeRepo.importFromUri(uri)
                // 导入后设为激活主题
                themeRepo.setActiveTheme(id)
                val name = themeRepo.observePacks().first().firstOrNull { it.id == id }?.name ?: id
                _message.value = "已导入并启用：$name"
            } catch (e: Exception) {
                _message.value = "导入失败: ${e.message}"
            }
        }
    }

    fun setActive(id: String?) {
        viewModelScope.launch {
            try {
                themeRepo.setActiveTheme(id)
            } catch (e: Exception) {
                _message.value = "切换主题失败: ${e.message}"
            }
        }
    }

    fun deletePack(id: String) {
        viewModelScope.launch {
            try {
                themeRepo.deletePack(id)
                // 若删除的是当前激活主题，同时恢复默认
                if (activeThemeId.value == id) {
                    themeRepo.setActiveTheme(null)
                }
                _message.value = "主题已删除"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    fun exportPack(id: String) {
        viewModelScope.launch {
            try {
                val file = themeRepo.exportPack(id)
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
                // Application context 启动 Activity 必须加 NEW_TASK，否则抛 SecurityException；
                // 授权标志同时加在 chooser 上（部分实现要求在最终启动的 intent 上）
                val chooser =
                    Intent.createChooser(intent, "分享主题包")
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
}
