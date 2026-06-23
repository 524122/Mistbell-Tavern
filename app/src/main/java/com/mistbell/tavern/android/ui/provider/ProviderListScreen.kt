package com.mistbell.tavern.android.ui.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.components.ConfirmDeleteDialog
import com.mistbell.tavern.android.ui.components.EmptyStateView
import com.mistbell.tavern.android.ui.theme.AccentBlue

private val TYPE_LABELS = mapOf("openai" to "OpenAI", "anthropic" to "Anthropic", "google" to "Google", "custom" to "自定义")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onBack: () -> Unit,
    onEditProvider: (String) -> Unit,
    onNewProvider: () -> Unit,
    viewModel: ProviderViewModel = viewModel()
) {
    val providers by viewModel.providers.collectAsState()
    val activeProviderId by viewModel.activeProviderId.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var testingProviderId by remember { mutableStateOf<String?>(null) }
    val testResult by viewModel.testResult.collectAsState()

    Scaffold(
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
                        Text("提供商管理", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewProvider,
                containerColor = AccentBlue,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, "新增提供商") }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (providers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                EmptyStateView("🔌", "暂无提供商", "点击右下角 + 添加")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(providers, key = { it.id }) { provider ->
                    val isActive = provider.id == activeProviderId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = if (isActive) CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ) else CardDefaults.cardColors()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(provider.name, fontWeight = FontWeight.SemiBold)
                                    Text(TYPE_LABELS[provider.type] ?: provider.type,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (provider.selectedModel.isNotBlank()) {
                                        Text("模型: ${provider.selectedModel}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { onEditProvider(provider.id) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text("编辑") }

                                // 如果配置完整（有端点、密钥、模型），显示测试连接按钮
                                if (provider.endpoint.isNotBlank() && provider.apiKey.isNotBlank() && provider.selectedModel.isNotBlank()) {
                                    val isTesting = testingProviderId == provider.id
                                    TextButton(
                                        onClick = {
                                            testingProviderId = provider.id
                                            viewModel.testConnectionForProvider(provider.endpoint, provider.apiKey, provider.type)
                                        },
                                        enabled = !isTesting,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        if (isTesting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            when {
                                                isTesting -> "测试中"
                                                testingProviderId == provider.id && testResult != null -> if (testResult == true) "✓" else "✗"
                                                else -> "测试连接"
                                            },
                                            color = when {
                                                testingProviderId == provider.id && testResult == true -> MaterialTheme.colorScheme.primary
                                                testingProviderId == provider.id && testResult == false -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = { showDeleteDialog = provider.id },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    showDeleteDialog?.let { id ->
        ConfirmDeleteDialog(
            message = "确定要删除这个提供商吗？",
            onConfirm = { viewModel.deleteProvider(id); showDeleteDialog = null },
            onDismiss = { showDeleteDialog = null }
        )
    }
}
