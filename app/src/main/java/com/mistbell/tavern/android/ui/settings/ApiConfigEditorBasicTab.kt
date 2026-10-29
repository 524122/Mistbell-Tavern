package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.defaultBaseUrlForType
import kotlin.math.roundToInt

private const val CONTEXT_4K_TOKENS = 4096
private const val CONTEXT_8K_TOKENS = 8192
private const val CONTEXT_16K_TOKENS = 16384
private const val CONTEXT_32K_TOKENS = 32768
private const val CONTEXT_64K_TOKENS = 65536
private const val CONTEXT_128K_TOKENS = 131072
private const val CONTEXT_256K_TOKENS = 262144
private const val CONTEXT_512K_TOKENS = 524288
private const val CONTEXT_1M_TOKENS = 1_000_000
private const val TOKENS_PER_KIB = 1024

private val CONTEXT_LIMIT_STEPS =
    listOf(
        CONTEXT_4K_TOKENS,
        CONTEXT_8K_TOKENS,
        CONTEXT_16K_TOKENS,
        CONTEXT_32K_TOKENS,
        CONTEXT_64K_TOKENS,
        CONTEXT_128K_TOKENS,
        CONTEXT_256K_TOKENS,
        CONTEXT_512K_TOKENS,
        CONTEXT_1M_TOKENS,
    )

@Composable
internal fun ApiConfigBasicTab(
    state: ApiConfigEditorState,
    viewModel: ApiConfigViewModel?,
    fetchState: ApiFetchState,
) {
    ApiConfigEditorTabColumn {
        EditorSectionHeader(
            title = "基础配置",
            subtitle = "连接信息与默认模型",
        )
        BasicConnectionFields(state = state, fetchedModels = fetchState.models)
        DialogApiToolsRow(
            input =
                ApiConnectionInput(
                    apiUrl = state.apiUrl,
                    apiKey = state.apiKey,
                    model = state.model,
                    type = state.type,
                ),
            viewModel = viewModel,
            fetchState = fetchState,
        )
        ResponseBudgetSection(state)
    }
}

@Composable
private fun BasicConnectionFields(
    state: ApiConfigEditorState,
    fetchedModels: List<String>,
) {
    ApiNameField(
        name = state.name,
        onNameChange = {
            state.name = it
            state.editBase(state.document.copy(name = it))
        },
    )
    ApiTypeSelectorField(type = state.type, onTypeChange = state::changeType)
    state.protocolNotice?.let { notice ->
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    ApiUrlField(
        apiUrl = state.apiUrl,
        type = state.type,
        error = state.apiUrlError,
        onApiUrlChange = {
            state.apiUrl = it
            state.apiUrlError = null
            state.editBase(state.document.copy(baseUrl = ApiConfigJson.stripUrlCredentials(it)))
        },
    )
    ApiKeyField(apiKey = state.apiKey, onApiKeyChange = state::changeApiKey)
    ApiModelField(
        model = state.model,
        onModelChange = {
            state.model = it
            state.editBase(state.document.copy(model = it))
        },
        fetchedModels = fetchedModels,
    )
}

@Composable
private fun ApiNameField(
    name: String,
    onNameChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("配置名称") },
        placeholder = { Text("例如：OpenAI API") },
        leadingIcon = { Icon(Icons.Default.Label, null) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun ApiUrlField(
    apiUrl: String,
    type: String,
    error: String?,
    onApiUrlChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = apiUrl,
        onValueChange = onApiUrlChange,
        label = { Text("API 地址") },
        placeholder = {
            Text(defaultBaseUrlForType(type).ifBlank { "https://your-gateway.example.com/v1" })
        },
        leadingIcon = { Icon(Icons.Default.Link, null) },
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

@Composable
private fun ResponseBudgetSection(state: ApiConfigEditorState) {
    CollapsibleSection(
        title = "响应与本地预算",
        subtitle = "SSE、上下文长度与默认配置",
        expanded = state.responseBudgetExpanded,
        onToggle = { state.responseBudgetExpanded = !state.responseBudgetExpanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSwitchRow(
                title = "SSE 流式传输",
                subtitle = "逐步显示模型回复；关闭后等待整包返回",
                checked = state.streamingEnabled,
                onCheckedChange = {
                    state.streamingEnabled = it
                    state.editBase(state.document.copy(streamingEnabled = it))
                },
            )
            ContextLimitSlider(state)
            SettingsSwitchRow(
                title = "设为默认配置",
                subtitle = "作为新会话的默认 API",
                icon = Icons.Default.Star,
                checked = state.setAsDefault,
                onCheckedChange = { state.setAsDefault = it },
            )
        }
    }
}

@Composable
private fun ContextLimitSlider(state: ApiConfigEditorState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "上下文长度：${formatContextLimit(state.contextTokenLimit)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = contextSliderValue(state.contextTokenLimit),
            onValueChange = { value ->
                state.contextTokenLimit = contextLimitFromSlider(value)
                state.context1M =
                    state.contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT
                state.editBase(state.document.copy(contextTokenLimit = state.contextTokenLimit))
            },
            valueRange = 0f..8f,
            steps = 7,
        )
        Text(
            "只影响本地 PromptBuilder 的历史裁剪和记忆预算",
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor(),
        )
    }
}

private fun contextSliderValue(limit: Int): Float =
    CONTEXT_LIMIT_STEPS
        .indexOfFirst { it >= limit }
        .coerceAtLeast(0)
        .toFloat()

private fun contextLimitFromSlider(value: Float): Int {
    return CONTEXT_LIMIT_STEPS[value.roundToInt().coerceIn(0, CONTEXT_LIMIT_STEPS.lastIndex)]
}

private fun formatContextLimit(limit: Int): String =
    when {
        limit >= CONTEXT_1M_TOKENS -> "1M tokens"
        limit >= TOKENS_PER_KIB -> "${limit / TOKENS_PER_KIB}K tokens"
        else -> "$limit tokens"
    }
