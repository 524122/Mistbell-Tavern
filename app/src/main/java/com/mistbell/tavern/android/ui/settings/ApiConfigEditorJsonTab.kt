package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.ApiConfigDocument
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonDiff
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult

@Composable
internal fun ApiConfigJsonTab(
    state: ApiConfigEditorState,
    draftInfo: ApiConfigDraftInfo,
) {
    ApiConfigEditorTabColumn {
        EditorSectionHeader(
            title = "请求参数 JSON",
            subtitle =
                "模板自带默认值；null = 未设置且不会发送；可直接修改或复制给 AI；" +
                    "API Key 不会进入 JSON。",
        )
        draftInfo.notice?.let { notice -> JsonDraftNotice(notice) }
        JsonDocumentEditor(state = state, initialDocument = draftInfo.document)
    }
}

@Composable
private fun JsonDraftNotice(notice: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = notice,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor(),
        )
    }
}

@Composable
private fun JsonDocumentEditor(
    state: ApiConfigEditorState,
    initialDocument: ApiConfigDocument,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.jsonValue,
            onValueChange = { value ->
                state.manualJsonEdited = true
                state.jsonValue = value
                state.updateFromJson(value.text)
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 14,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = state.jsonError != null,
            supportingText = { Text(state.jsonError ?: state.clipboardMessage.orEmpty()) },
        )
        JsonActionButtons(state)
        if (state.jsonError == null) {
            JsonChangePreview(
                initialDocument = initialDocument,
                document = state.document,
            )
        }
    }
}

@Composable
private fun JsonActionButtons(state: ApiConfigEditorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatJsonButton(state)
        CopyJsonButton(state)
        PasteJsonButton(state)
        ResetJsonMenu(state)
    }
}

@Composable
private fun FormatJsonButton(state: ApiConfigEditorState) {
    TextButton(
        onClick = {
            when (val result = ApiConfigJson.parse(state.jsonValue.text)) {
                is ApiConfigJsonResult.Valid -> {
                    state.updateFromJson(
                        ApiConfigEditorLogic.formatForEditor(
                            result.document,
                            state.apiKey.isNotBlank(),
                        ),
                    )
                    state.clipboardMessage = "已格式化"
                }
                is ApiConfigJsonResult.Invalid -> state.jsonError = result.message
            }
        },
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text("格式化")
    }
}

@Composable
private fun CopyJsonButton(state: ApiConfigEditorState) {
    val clipboard = LocalClipboardManager.current
    TextButton(
        onClick = {
            val safe =
                ApiConfigEditorLogic.safeJsonForClipboard(
                    state.jsonValue.text,
                    state.apiKey.isNotBlank(),
                )
            if (safe != null) {
                clipboard.setText(AnnotatedString(safe))
                state.clipboardMessage = "已复制脱敏 JSON"
            } else {
                state.jsonError =
                    (ApiConfigJson.parse(state.jsonValue.text) as? ApiConfigJsonResult.Invalid)
                        ?.message
                        ?: "配置 JSON 校验失败"
                state.clipboardMessage = "草稿未通过安全校验，已阻止复制（可能含凭据）"
            }
        },
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text("复制脱敏")
    }
}

@Composable
private fun PasteJsonButton(state: ApiConfigEditorState) {
    val clipboard = LocalClipboardManager.current
    TextButton(
        onClick = {
            clipboard.getText()?.text?.let { text ->
                state.manualJsonEdited = true
                state.updateFromJson(text)
            }
            state.clipboardMessage = "已粘贴，保存前仍需通过校验"
        },
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text("粘贴")
    }
}

@Composable
private fun ResetJsonMenu(state: ApiConfigEditorState) {
    var resetMenuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { resetMenuExpanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(
            expanded = resetMenuExpanded,
            onDismissRequest = { resetMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("重置为协议模板", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    resetMenuExpanded = false
                    state.requestReset()
                },
            )
        }
    }
}

@Composable
private fun JsonChangePreview(
    initialDocument: ApiConfigDocument,
    document: ApiConfigDocument,
) {
    val previewDiff = ApiConfigJson.diff(initialDocument, document)
    if (!previewDiff.isEmpty) {
        Text(
            text = changePreviewText(previewDiff),
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor(),
        )
    }
}

private fun changePreviewText(diff: ApiConfigJsonDiff): String {
    fun fields(values: List<String>): String = values.joinToString().ifBlank { "无" }
    val schema =
        if (diff.modified.contains("schemaVersion")) {
            "schemaVersion 已变化"
        } else {
            "schemaVersion 未变化"
        }
    return "变更预览：新增 ${fields(diff.added)}；删除 ${fields(diff.removed)}；" +
        "修改 ${fields(diff.modified)}；$schema"
}
