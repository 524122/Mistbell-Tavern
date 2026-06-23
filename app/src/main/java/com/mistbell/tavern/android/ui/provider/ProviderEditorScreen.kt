package com.mistbell.tavern.android.ui.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.theme.AccentBlue
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap

private val TYPE_OPTIONS = listOf("openai" to "OpenAI", "anthropic" to "Anthropic", "google" to "Google", "custom" to "自定义")
private val ENDPOINT_PLACEHOLDERS = mapOf(
    "openai" to "https://api.openai.com/v1",
    "anthropic" to "https://api.anthropic.com",
    "google" to "https://generativelanguage.googleapis.com/v1",
    "custom" to "自定义端点地址"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorScreen(
    providerId: String?,
    onBack: () -> Unit,
    viewModel: ProviderViewModel = viewModel()
) {
    val form by viewModel.form.collectAsState()
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(providerId) {
        if (providerId != null && providerId != "new") {
            viewModel.loadProvider(providerId)
        } else {
            viewModel.resetForm()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Back handler with unsaved changes check
    BackHandler {
        if (form.name.isNotBlank() || form.endpoint.isNotBlank() || form.apiKey.isNotBlank()) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .clearFocusOnTap(),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(22.dp))
                        }
                        Text(
                            if (providerId != null && providerId != "new") "编辑提供商" else "新增提供商",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.saveProvider(); onBack() },
                            enabled = form.name.isNotBlank(),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("保存") }
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Name
            item {
                SectionHeader("名称")
                Spacer(modifier = Modifier.height(8.dp))
                FormTextField(
                    value = form.name,
                    onValueChange = { viewModel.updateForm { copy(name = it) } },
                    label = "提供商名称",
                    placeholder = "我的 OpenAI"
                )
            }

            // Type
            item {
                SectionHeader("类型")
                Spacer(modifier = Modifier.height(8.dp))
                var typeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = TYPE_OPTIONS.find { it.first == form.type }?.second ?: form.type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("接口类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        TYPE_OPTIONS.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                viewModel.updateForm { copy(type = key) }; typeExpanded = false
                            })
                        }
                    }
                }
            }

            // Endpoint
            item {
                SectionHeader("端点")
                Spacer(modifier = Modifier.height(8.dp))
                FormTextField(
                    value = form.endpoint,
                    onValueChange = { viewModel.updateForm { copy(endpoint = it) } },
                    label = "API 端点",
                    placeholder = ENDPOINT_PLACEHOLDERS[form.type] ?: ""
                )
            }

            // API Key
            item {
                SectionHeader("API Key")
                Spacer(modifier = Modifier.height(8.dp))
                var showKey by remember { mutableStateOf(false) }
                FormTextField(
                    value = form.apiKey,
                    onValueChange = { viewModel.updateForm { copy(apiKey = it) } },
                    label = "API Key",
                    placeholder = "sk-...",
                    isPassword = !showKey,
                    keyboardType = KeyboardType.Password,
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }

            // Model section
            item {
                SectionHeader("模型")
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.fetchModels() },
                        enabled = !isFetchingModels && form.endpoint.isNotBlank() && form.apiKey.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("获取模型列表")
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        enabled = form.endpoint.isNotBlank() && form.apiKey.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("测试连接")
                    }
                }
                testResult?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (it) "✓ 连接成功" else "✗ 连接失败",
                        color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Model selector (manual or from fetched)
            item {
                if (fetchedModels.isNotEmpty()) {
                    var modelExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                        OutlinedTextField(
                            value = form.selectedModel,
                            onValueChange = { viewModel.updateForm { copy(selectedModel = it) } },
                            label = { Text("模型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = RoundedCornerShape(8.dp)
                        )
                        ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                            fetchedModels.forEach { model ->
                                DropdownMenuItem(text = { Text(model, style = MaterialTheme.typography.bodySmall) }, onClick = {
                                    viewModel.updateForm { copy(selectedModel = model) }; modelExpanded = false
                                })
                            }
                        }
                    }
                } else {
                    FormTextField(
                        value = form.selectedModel,
                        onValueChange = { viewModel.updateForm { copy(selectedModel = it) } },
                        label = "模型名称",
                        placeholder = "gpt-4o"
                    )
                }
            }

            // Advanced: context 1M
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1M 上下文", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = form.context1M,
                        onCheckedChange = { viewModel.updateForm { copy(context1M = it) } }
                    )
                }
            }

            // Advanced models section
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader("高级模型设置")
                Spacer(modifier = Modifier.height(8.dp))
                Text("为不同功能配置独立的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                FormTextField(
                    value = form.embeddingModel,
                    onValueChange = { viewModel.updateForm { copy(embeddingModel = it) } },
                    label = "嵌入模型",
                    placeholder = "text-embedding-3-small"
                )
            }
            item {
                FormTextField(
                    value = form.summaryModel,
                    onValueChange = { viewModel.updateForm { copy(summaryModel = it) } },
                    label = "摘要模型",
                    placeholder = "gpt-4o-mini"
                )
            }
            item {
                FormTextField(
                    value = form.memoryModel,
                    onValueChange = { viewModel.updateForm { copy(memoryModel = it) } },
                    label = "记忆模型",
                    placeholder = "gpt-4o-mini"
                )
            }
        }
    }

    // Discard changes confirmation
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("丢弃更改？") },
            text = { Text("你有未保存的更改，确定要离开吗？") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    Text("丢弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            }
        )
    }
}
