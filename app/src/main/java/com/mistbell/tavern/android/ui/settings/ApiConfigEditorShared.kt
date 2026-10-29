package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.defaultBaseUrlForType
import com.mistbell.tavern.android.data.api.normalizeApiType

internal val API_TYPE_OPTIONS =
    listOf(
        ApiType.OPENAI to "OpenAI 兼容",
        ApiType.ANTHROPIC to "Anthropic",
        ApiType.GEMINI to "Gemini",
        ApiType.CUSTOM to "自定义接口",
    )

internal fun apiTypeLabel(type: String): String {
    val normalized = normalizeApiType(type)
    return API_TYPE_OPTIONS.find { it.first == normalized }?.second ?: type
}

internal fun defaultPreviewGroupKey(type: String): String {
    val normalized = normalizeApiType(type)
    val group = if (normalized == ApiType.GEMINI) "generationConfig" else "sampling"
    return "$normalized:$group"
}

/** 用户未填写地址，或当前值仍是另一类型的预设值时，回填选中类型的预设地址。 */
internal fun resolvePresetUrl(
    type: String,
    current: String,
): String {
    val preset = defaultBaseUrlForType(type)
    return if (preset.isNotBlank() &&
        (current.isBlank() || API_TYPE_OPTIONS.any { defaultBaseUrlForType(it.first) == current })
    ) {
        preset
    } else {
        current
    }
}

@Composable
internal fun EditorSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor(),
        )
    }
}

@Composable
internal fun CollapsibleSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor(),
                )
            }
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起" else "展开")
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (expanded) {
            content()
        }
    }
}

@Composable
internal fun ApiConfigEditorTabColumn(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .padding(bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = { content() },
    )
}

@Composable
internal fun secondaryTextColor() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
