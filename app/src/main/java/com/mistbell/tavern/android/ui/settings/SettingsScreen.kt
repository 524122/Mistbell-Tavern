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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.theme.*
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap
import com.mistbell.tavern.android.util.CrashLogger
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
    onNavigateToThemeManager: () -> Unit = {},
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
            // === 生成与采样 ===
            SectionHeader("生成与采样")

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val samplingPreset by viewModel.samplingPreset.collectAsState()
                    val requestTimeout by viewModel.requestTimeout.collectAsState()
                    val requestRetries by viewModel.requestRetries.collectAsState()
                    val streamingEnabled by viewModel.streamingEnabled.collectAsState()

                    // 采样预设三档 + 自定义：写入 sampling_preset，由 SettingsRepository 组装 LlmConfig 时解析
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("采样预设", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = samplingPreset == "creative",
                                onClick = { viewModel.setSamplingPreset("creative") },
                                label = { Text("创意") }
                            )
                            FilterChip(
                                selected = samplingPreset == "balanced",
                                onClick = { viewModel.setSamplingPreset("balanced") },
                                label = { Text("平衡") }
                            )
                            FilterChip(
                                selected = samplingPreset == "precise",
                                onClick = { viewModel.setSamplingPreset("precise") },
                                label = { Text("精确") }
                            )
                            FilterChip(
                                selected = samplingPreset == "custom",
                                onClick = { viewModel.setSamplingPreset("custom") },
                                label = { Text("自定义") }
                            )
                        }
                        if (samplingPreset == "custom") {
                            Text(
                                "自定义：请前往「提供商管理」编辑页的高级参数区逐项调参",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 流式输出开关：关闭后回复整包返回，适用于不支持 SSE 的网关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("流式输出", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("关闭后回复整包返回，适用于不支持 SSE 的网关",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = streamingEnabled,
                            onCheckedChange = { viewModel.setStreamingEnabled(it) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 请求超时：数值输入，范围 15..600 秒（越界自动收敛）
                    var timeoutText by remember(requestTimeout) { mutableStateOf(requestTimeout.toString()) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("请求超时", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("单次请求最长等待时间（15–600 秒）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(
                            value = timeoutText,
                            onValueChange = { input ->
                                // 只允许数字；可解析且在范围内时立即落盘
                                if (input.all { it.isDigit() }) {
                                    timeoutText = input.take(4)
                                    input.toIntOrNull()?.let { if (it in 15..600) viewModel.setRequestTimeout(it) }
                                }
                            },
                            modifier = Modifier.width(96.dp),
                            singleLine = true,
                            trailingIcon = { Text("秒", style = MaterialTheme.typography.labelMedium) },
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 重试次数：失败后自动重试，范围 0..5
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("重试次数", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("请求失败后的自动重试上限",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = requestRetries.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = requestRetries.toFloat(),
                            onValueChange = { viewModel.setRequestRetries(it.toInt()) },
                            valueRange = 0f..5f,
                            steps = 4
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 提供商管理：从原"快速设置"移入本组（提供商级细参数在编辑页）
                    SettingsNavItem(
                        title = "提供商管理",
                        subtitle = "管理 LLM 提供商、模型与高级参数",
                        onClick = onNavigateToProviderList
                    )
                }
            }

            // === 对话 ===
            SectionHeader("对话")

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val defaultContextTokens by viewModel.defaultContextTokens.collectAsState()
                    val defaultLtmEnabled by viewModel.defaultLtmEnabled.collectAsState()

                    // 上下文长度：新会话的默认上下文 token 预算（1024..32768，步进 512）
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("默认上下文长度", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("新会话的默认上下文 token 预算",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = defaultContextTokens.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = defaultContextTokens.toFloat(),
                            onValueChange = { viewModel.setDefaultContextTokens((it / 512).toInt() * 512) },
                            valueRange = 1024f..32768f,
                            steps = (32768 - 1024) / 512 - 1
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 长期记忆（实验性）：新会话默认开启记忆抽取
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("默认长期记忆（实验性）", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("新会话默认开启记忆抽取；向量记忆处于实验阶段",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = defaultLtmEnabled,
                            onCheckedChange = { viewModel.setDefaultLtmEnabled(it) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 提示词预览：保留原有调试入口（归入"对话"组）
                    SettingsNavItem(
                        title = "提示词预览",
                        subtitle = "预览和调试提示词构建",
                        onClick = onNavigateToPromptPreview
                    )
                }
            }

            // === 外观 ===
            SectionHeader("外观")

            SettingsCard {
                SettingsNavItem(
                    title = "主题管理",
                    subtitle = "主题色、字体大小、消息样式",
                    onClick = onNavigateToThemeManager
                )
            }

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

            // === 记忆 ===
            SectionHeader("记忆")

            SettingsCard {
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

            // === 关于 ===
            SectionHeader("关于")

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    var showCrashLogDialog by remember { mutableStateOf(false) }
                    SettingsNavItem(
                        title = "版本日志",
                        subtitle = "v${BuildConfig.VERSION_NAME}",
                        onClick = {
                            android.util.Log.d("SettingsScreen", "版本号点击，准备导航")
                            onNavigateToVersionChangelog()
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavItem(
                        title = "反馈日志",
                        subtitle = "查看崩溃日志、导出反馈给开发者",
                        onClick = { showCrashLogDialog = true }
                    )

                    if (showCrashLogDialog) {
                        CrashLogDialog(onDismiss = { showCrashLogDialog = false })
                    }

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

@Composable
private fun CrashLogDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // null = 正在收集（抓取 logcat 可能阻塞，必须在后台线程构建）
    var report by remember { mutableStateOf<String?>(null) }

    // 打开对话框时在 IO 线程构建诊断报告：崩溃记录 + 过滤后的运行日志
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            CrashLogger.buildDiagnosticReport(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "问题反馈与日志",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val currentReport = report
                if (currentReport == null) {
                    Text(
                        text = "正在收集诊断信息…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "以下为崩溃记录与本次运行的最近日志（含导航/点击轨迹，可定位「点击失效、页面未升起」等问题）。已自动过滤聊天内容与 API Key，可导出后通过 Issue 反馈给开发者。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = ButtonDefaults.outlinedButtonBorder
                    ) {
                        Text(
                            text = currentReport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 18.sp
                            )
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val uri = CrashLogger.exportReport(context, currentReport)
                                if (uri != null) {
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            CrashLogger.createShareIntent(uri),
                                            "导出诊断日志"
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("导出日志")
                        }
                        OutlinedButton(
                            onClick = {
                                // 仅能清除落盘的崩溃记录；logcat 属系统缓冲区无法清。清后重建报告。
                                CrashLogger.clearLogs(context)
                                report = null
                                coroutineScope.launch {
                                    report = withContext(Dispatchers.IO) {
                                        CrashLogger.buildDiagnosticReport(context)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("清除崩溃记录")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
