package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.ApiConfigDocument
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiRequestFieldState
import com.mistbell.tavern.android.data.api.ApiRequestPreview
import com.mistbell.tavern.android.data.api.ApiRequestPreviewField
import com.mistbell.tavern.android.data.api.ApiRequestPreviewGroup

@Composable
internal fun ApiConfigRequestPreviewTab(state: ApiConfigEditorState) {
    ApiConfigEditorTabColumn {
        RequestPreviewBlock(
            document = state.document,
            state =
                RequestPreviewUiState(
                    isJsonValid = state.jsonError == null,
                    expandedGroups = state.previewExpandedGroups,
                    connectionExpanded = state.previewConnectionExpanded,
                    generatedExpanded = state.previewGeneratedExpanded,
                ),
            onToggleGroup = { groupKey ->
                state.previewExpandedGroups =
                    if (groupKey in state.previewExpandedGroups) {
                        state.previewExpandedGroups - groupKey
                    } else {
                        state.previewExpandedGroups + groupKey
                    }
            },
            onToggleConnection = {
                state.previewConnectionExpanded = !state.previewConnectionExpanded
            },
            onToggleGenerated = {
                state.previewGeneratedExpanded = !state.previewGeneratedExpanded
            },
        )
    }
}

@Composable
private fun RequestPreviewBlock(
    document: ApiConfigDocument,
    state: RequestPreviewUiState,
    onToggleGroup: (String) -> Unit,
    onToggleConnection: () -> Unit,
    onToggleGenerated: () -> Unit,
) {
    val preview = ApiRequestPreview.from(document)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EditorSectionHeader(
            title = "请求预览",
            subtitle = "配置合并结果 · 非实时请求",
        )
        if (!state.isJsonValid) {
            Text(
                text = "修正 JSON 后查看预览",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor(),
            )
        } else {
            Text(
                text =
                    "修改 ${preview.userFieldCount} · 默认 ${preview.defaultFieldCount} · " +
                        "保护忽略 ${preview.ignoredFields.size}",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor(),
            )
            RequestPreviewConnectionSection(
                document = document,
                expanded = state.connectionExpanded,
                onToggle = onToggleConnection,
            )
            CollapsiblePreviewGroup(
                title = "协议生成字段",
                summary = "${preview.generatedFields.size} 项",
                fields = preview.generatedFields,
                expanded = state.generatedExpanded,
                onToggle = onToggleGenerated,
            )
            preview.parameterGroups.forEach { group ->
                val stateKey = "${document.type}:${group.id}"
                RequestPreviewGroupBlock(
                    group = group,
                    expanded = stateKey in state.expandedGroups,
                    onToggle = { onToggleGroup(stateKey) },
                )
            }
            if (preview.ignoredFields.isNotEmpty()) {
                val stateKey = "${document.type}:ignored"
                CollapsiblePreviewGroup(
                    title = "保护忽略字段",
                    summary = "${preview.ignoredFields.size} 项",
                    fields = preview.ignoredFields,
                    expanded = stateKey in state.expandedGroups,
                    onToggle = { onToggleGroup(stateKey) },
                )
            }
            RequestPreviewFootnote()
        }
    }
}

@Composable
private fun RequestPreviewFootnote() {
    Text(
        text =
            "预览不含调用级覆盖；max_tokens / maxOutputTokens 是模型输出预算，" +
                "contextTokenLimit 是本地输入预算。",
        style = MaterialTheme.typography.bodySmall,
        color = secondaryTextColor(),
    )
}

@Composable
private fun RequestPreviewConnectionSection(
    document: ApiConfigDocument,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    CollapsibleSection(
        title = "连接与本地预算",
        subtitle = "地址、认证与 contextTokenLimit",
        expanded = expanded,
        onToggle = onToggle,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            previewLine("接口类型：${apiTypeLabel(document.type)}（${document.type}）")
            previewLine("地址：${ApiConfigJson.redactUrl(document.baseUrl)}")
            previewLine("认证：由 SecureStore 生成（已隐藏）")
            previewLine("contextTokenLimit=${document.contextTokenLimit}（本地 prompt 输入预算）")
        }
    }
}

@Composable
private fun RequestPreviewGroupBlock(
    group: ApiRequestPreviewGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val userCount = group.fields.count { it.state == ApiRequestFieldState.USER }
    CollapsiblePreviewGroup(
        title = group.title,
        summary = "修改 $userCount · 共 ${group.fields.size} 项",
        fields = group.fields,
        expanded = expanded,
        onToggle = onToggle,
    )
}

@Composable
private fun CollapsiblePreviewGroup(
    title: String,
    summary: String,
    fields: List<ApiRequestPreviewField>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor(),
                )
            }
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起" else "展开")
            }
        }
        if (expanded) {
            fields.forEach { field -> PreviewFieldRow(field) }
        }
    }
}

@Composable
private fun PreviewFieldRow(field: ApiRequestPreviewField) {
    val stateColor =
        when (field.state) {
            ApiRequestFieldState.USER -> MaterialTheme.colorScheme.primary
            ApiRequestFieldState.DEFAULT -> secondaryTextColor()
            ApiRequestFieldState.PROTECTED -> MaterialTheme.colorScheme.tertiary
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = field.path,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    when (field.state) {
                        ApiRequestFieldState.USER -> "用户修改"
                        ApiRequestFieldState.DEFAULT -> "模板默认/未设置"
                        ApiRequestFieldState.PROTECTED -> "协议保护/生成"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = stateColor,
            )
        }
        Text(
            text = field.value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = stateColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun previewLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = secondaryTextColor(),
    )
}
