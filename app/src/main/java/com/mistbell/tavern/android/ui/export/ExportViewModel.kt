package com.mistbell.tavern.android.ui.export

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.data.api.ApiClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExportViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val api get() = ApiClient.getApi(context)

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun exportCharacters() = exportData("characters") { api.exportCharacters() }

    fun exportWorldBook() = exportData("worldbook") { api.exportWorldBook() }

    fun exportMemories() = exportData("memories") { api.exportMemories() }

    fun exportConversations() = exportData("conversations") { api.exportConversations() }

    private fun exportData(
        name: String,
        fetch: suspend () -> JsonElement,
    ) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val data = fetch()
                val json = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), data)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "${name}_$timestamp.json"

                val exportDir = File(context.cacheDir, "exports")
                exportDir.mkdirs()
                val file = File(exportDir, fileName)
                file.writeText(json)

                val uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )

                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, fileName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                context.startActivity(Intent.createChooser(intent, "导出 $name"))

                _message.value = "已导出 $fileName"
            } catch (e: Exception) {
                _message.value = "导出失败: ${e.message}"
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
