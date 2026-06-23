package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.theme.*
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap
import com.mistbell.tavern.android.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit = {},
    onNavigateToProviderList: () -> Unit = {},
    onNavigateToWorldBookList: () -> Unit = {},
    onNavigateToMemoryList: () -> Unit = {},
    onNavigateToPromptPreview: () -> Unit = {},
    onNavigateToVersionChangelog: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val llmConfig by viewModel.llmConfig.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    var serverUrl by remember { mutableStateOf(ApiClient.getServerUrl(context)) }

    // Local state for editing LLM config
    var baseUrl by remember(llmConfig) { mutableStateOf(llmConfig.baseUrl) }
    var apiKey by remember(llmConfig) { mutableStateOf(llmConfig.apiKey) }
    var model by remember(llmConfig) { mutableStateOf(llmConfig.model) }
    var showApiKey by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .clearFocusOnTap(),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showBackButton) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "设置",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // === 快速设置 ===
            SectionHeader("快速设置")

            // 模型管理
            SettingsCard {
                SettingsNavItem(
                    title = "模型管理",
                    subtitle = "管理 LLM 提供商和模型",
                    onClick = onNavigateToProviderList
                )
            }

            // 深色模式
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("深色模式", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("跟随系统 / 手动切换", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var darkModeExpanded by remember { mutableStateOf(false) }
                    val currentMode = viewModel.darkMode.collectAsState()
                    ExposedDropdownMenuBox(expanded = darkModeExpanded, onExpandedChange = { darkModeExpanded = it }) {
                        Surface(
                            onClick = { darkModeExpanded = true },
                            modifier = Modifier.menuAnchor(),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder
                        ) {
                            Text(
                                text = when (currentMode.value) {
                                    "light" -> "浅色"
                                    "dark" -> "深色"
                                    else -> "跟随系统"
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        ExposedDropdownMenu(expanded = darkModeExpanded, onDismissRequest = { darkModeExpanded = false }) {
                            DropdownMenuItem(text = { Text("跟随系统") }, onClick = {
                                viewModel.setDarkMode("system"); darkModeExpanded = false
                            })
                            DropdownMenuItem(text = { Text("浅色") }, onClick = {
                                viewModel.setDarkMode("light"); darkModeExpanded = false
                            })
                            DropdownMenuItem(text = { Text("深色") }, onClick = {
                                viewModel.setDarkMode("dark"); darkModeExpanded = false
                            })
                        }
                    }
                }
            }

            // === 主要设置 ===
            SectionHeader("主要设置")

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsNavItem(
                        title = "模型与生成",
                        subtitle = "LLM配置、生成参数",
                        onClick = { /* TODO: 导航到模型设置页面 */ }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavItem(
                        title = "对话行为",
                        subtitle = "流式输出、自动保存、上下文管理",
                        onClick = { /* TODO: 导航到对话设置页面 */ }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavItem(
                        title = "外观与显示",
                        subtitle = "主题色、字体大小、消息样式",
                        onClick = { /* TODO: 导航到外观设置页面 */ }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavItem(
                        title = "通知与声音",
                        subtitle = "消息通知、震动反馈",
                        onClick = { /* TODO: 导航到通知设置页面 */ }
                    )
                }
            }

            // === 高级设置 ===
            SectionHeader("高级设置")

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsNavItem(
                        title = "提示词预览",
                        subtitle = "预览和调试提示词构建",
                        onClick = onNavigateToPromptPreview
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    var showMemoryPromptDialog by remember { mutableStateOf(false) }
                    SettingsNavItem(
                        title = "记忆提取提示词",
                        subtitle = "自定义长期记忆提取的提示词",
                        onClick = { showMemoryPromptDialog = true }
                    )

                    if (showMemoryPromptDialog) {
                        MemoryExtractionPromptDialog(
                            viewModel = viewModel,
                            onDismiss = { showMemoryPromptDialog = false }
                        )
                    }
                }
            }

            // === 其他 ===
            SectionHeader("其他")

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsNavItem(
                        title = "隐私与安全",
                        subtitle = "本地加密、历史清理",
                        onClick = { /* TODO: 导航到隐私设置页面 */ }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavItem(
                        title = "网络与性能",
                        subtitle = "代理设置、超时配置",
                        onClick = { /* TODO: 导航到网络设置页面 */ }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavItem(
                        title = "版本号",
                        subtitle = "v${BuildConfig.VERSION_NAME}",
                        onClick = {
                            android.util.Log.d("SettingsScreen", "版本号点击，准备导航")
                            onNavigateToVersionChangelog()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavItem(
                        title = "关于",
                        subtitle = "应用信息、帮助文档",
                        onClick = onNavigateToAbout
                    )
                }
            }

            // 底部留白
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsNavItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        letterSpacing = 0.02.sp
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = ButtonDefaults.outlinedButtonBorder
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryExtractionPromptDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val memoryPrompt by viewModel.memoryExtractionPrompt.collectAsState()
    var editedPrompt by remember(memoryPrompt) { mutableStateOf(memoryPrompt) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "记忆提取提示词",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "自定义 LLM 提取长期记忆时使用的提示词。提示词中必须包含 %s 占位符用于插入对话内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = editedPrompt,
                    onValueChange = { editedPrompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp, max = 500.dp),
                    placeholder = {
                        Text(
                            "输入记忆提取提示词...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 20.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            editedPrompt = viewModel.getDefaultMemoryExtractionPrompt()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("恢复默认")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        viewModel.saveMemoryExtractionPrompt(editedPrompt)
                        onDismiss()
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
