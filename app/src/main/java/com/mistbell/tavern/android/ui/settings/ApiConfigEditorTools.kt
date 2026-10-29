package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.ApiTestResult

private const val FETCHED_MODELS_DISPLAY_LIMIT = 12

internal data class ApiFetchState(
    val models: List<String>,
    val fetching: Boolean,
    val error: String?,
)

internal data class ApiConnectionInput(
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    val type: String,
)

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor(),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun ApiKeyField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("API 密钥") },
        placeholder = { Text("sk-...") },
        leadingIcon = { Icon(Icons.Default.Key, null) },
        trailingIcon = {
            IconButton(onClick = { showApiKey = !showApiKey }) {
                Icon(
                    imageVector = if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (showApiKey) "隐藏" else "显示",
                )
            }
        },
        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
internal fun ApiModelField(
    model: String,
    onModelChange: (String) -> Unit,
    fetchedModels: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text("模型") },
            placeholder = { Text("gpt-4o") },
            leadingIcon = { Icon(Icons.Default.SmartToy, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        if (fetchedModels.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fetchedModels.take(FETCHED_MODELS_DISPLAY_LIMIT).forEach { modelName ->
                    FilterChip(
                        selected = model == modelName,
                        onClick = { onModelChange(modelName) },
                        label = { Text(modelName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApiTypeSelectorField(
    type: String,
    onTypeChange: (String) -> Unit,
) {
    var typeExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = typeExpanded,
        onExpandedChange = { typeExpanded = it },
    ) {
        OutlinedTextField(
            value = apiTypeLabel(type),
            onValueChange = {},
            readOnly = true,
            label = { Text("接口类型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(16.dp),
        )
        ExposedDropdownMenu(
            expanded = typeExpanded,
            onDismissRequest = { typeExpanded = false },
        ) {
            API_TYPE_OPTIONS.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTypeChange(value)
                        typeExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun DialogApiToolsRow(
    input: ApiConnectionInput,
    viewModel: ApiConfigViewModel?,
    fetchState: ApiFetchState,
) {
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ApiTestResult?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FetchModelsButton(
                input = input,
                viewModel = viewModel,
                fetchingModels = fetchState.fetching,
            )
            TestConnectionButton(
                input = input,
                viewModel = viewModel,
                testing = testing,
                onStart = {
                    testing = true
                    testResult = null
                },
                onFinish = { result ->
                    testing = false
                    testResult = result
                },
            )
        }
        fetchState.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        testResult?.let { result -> ApiTestResultText(result) }
        Text(
            text = "仅验证地址、密钥、模型与协议连通性，不校验当前 request 参数。",
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor(),
        )
    }
}

@Composable
private fun FetchModelsButton(
    input: ApiConnectionInput,
    viewModel: ApiConfigViewModel?,
    fetchingModels: Boolean,
) {
    OutlinedButton(
        onClick = { viewModel?.fetchModels(input.apiUrl, input.apiKey, input.type) },
        enabled =
            viewModel != null && input.apiUrl.isNotBlank() && input.apiKey.isNotBlank() &&
                !fetchingModels,
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.outlinedButtonColors(
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            ),
    ) {
        if (fetchingModels) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text("获取模型")
    }
}

@Composable
private fun TestConnectionButton(
    input: ApiConnectionInput,
    viewModel: ApiConfigViewModel?,
    testing: Boolean,
    onStart: () -> Unit,
    onFinish: (ApiTestResult) -> Unit,
) {
    OutlinedButton(
        onClick = {
            onStart()
            viewModel?.testConnectionInDialog(
                input.apiUrl,
                input.apiKey,
                input.model,
                input.type,
                onResult = onFinish,
            )
        },
        enabled =
            viewModel != null && input.apiUrl.isNotBlank() && input.apiKey.isNotBlank() &&
                input.model.isNotBlank() && !testing,
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.outlinedButtonColors(
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            ),
    ) {
        if (testing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text("连接测试")
    }
}

@Composable
private fun ApiTestResultText(result: ApiTestResult) {
    Text(
        text =
            when (result) {
                is ApiTestResult.Success -> "✅ ${result.message}"
                is ApiTestResult.Error -> "❌ ${result.message}"
            },
        style = MaterialTheme.typography.bodySmall,
        color =
            when (result) {
                is ApiTestResult.Success -> MaterialTheme.colorScheme.primary
                is ApiTestResult.Error -> MaterialTheme.colorScheme.error
            },
    )
}
