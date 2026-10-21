package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mistbell.tavern.android.data.api.ApiConfigDocument
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonDiff
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult
import com.mistbell.tavern.android.data.api.ApiRequestFieldState
import com.mistbell.tavern.android.data.api.ApiRequestPreview
import com.mistbell.tavern.android.data.api.ApiRequestPreviewField
import com.mistbell.tavern.android.data.api.ApiRequestPreviewGroup
import com.mistbell.tavern.android.data.api.ApiRequestTemplate
import com.mistbell.tavern.android.data.api.ApiTestResult
import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.defaultBaseUrlForType
import com.mistbell.tavern.android.data.api.normalizeApiType
import java.util.UUID
import kotlin.math.roundToInt

/** 接口类型选项（value → 显示名） */
private val API_TYPE_OPTIONS =
    listOf(
        ApiType.OPENAI to "OpenAI 兼容",
        ApiType.ANTHROPIC to "Anthropic",
        ApiType.GEMINI to "Gemini",
        ApiType.CUSTOM to "自定义接口",
    )

// 获取模型后展示的可选芯片数量上限
private const val FETCHED_MODELS_DISPLAY_LIMIT = 12

private fun apiTypeLabel(type: String): String {
    val normalized = normalizeApiType(type)
    return API_TYPE_OPTIONS.find { it.first == normalized }?.second ?: type
}

private fun defaultPreviewGroupKey(type: String): String {
    val normalized = normalizeApiType(type)
    val group = if (normalized == ApiType.GEMINI) "generationConfig" else "sampling"
    return "$normalized:$group"
}

/**
 * 选中类型后的预设地址回填：用户未填写，或当前值仍是另一类型的预设值时才覆盖。
 * 纯函数（detekt 复杂度独立于调用方）。
 */
private fun resolvePresetUrl(
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

/** 标题 + 说明 + 开关的设置行（编辑对话框内复用） */
@Suppress("FunctionNaming")
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
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

/** API 密钥输入（带显示/隐藏切换） */
@Suppress("FunctionNaming")
@Composable
private fun ApiKeyField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("API 密钥") },
        placeholder = { Text("sk-...") },
        leadingIcon = {
            Icon(Icons.Default.Key, null)
        },
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

/** 编辑页顶部：退出 + 标题 + 保存；缺失必填项时仅禁用保存。 */
@Suppress("FunctionNaming", "LongParameterList") // 表单字段直传
@Composable
private fun DialogTopActions(
    title: String,
    name: String,
    apiUrl: String,
    apiKey: String,
    model: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val missingHint = ApiConfigEditorLogic.firstMissingRequiredHint(name, apiUrl, apiKey, model)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "退出编辑")
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = onConfirm,
                enabled = missingHint == null,
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("保存")
            }
        }
        missingHint?.let { hint ->
            Text(
                text = hint,
                modifier = Modifier.padding(start = 48.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 模型输入 + 拉取结果芯片；获取/测试工具单独紧凑排列。
 */
@Suppress("FunctionNaming")
@Composable
private fun ApiModelField(
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
            leadingIcon = {
                Icon(Icons.Default.SmartToy, null)
            },
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

/**
 * 获取模型 + 连接测试的紧凑次级工具行（异步逻辑不变）。
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun DialogApiToolsRow(
    apiUrl: String,
    apiKey: String,
    model: String,
    type: String,
    viewModel: ApiConfigViewModel?,
    fetchingModels: Boolean,
    fetchModelsError: String?,
) {
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ApiTestResult?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { viewModel?.fetchModels(apiUrl, apiKey, type) },
                enabled = viewModel != null && apiUrl.isNotBlank() && apiKey.isNotBlank() && !fetchingModels,
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
            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = null
                    viewModel?.testConnectionInDialog(apiUrl, apiKey, model, type) { result ->
                        testing = false
                        testResult = result
                    }
                },
                enabled =
                    viewModel != null && apiUrl.isNotBlank() && apiKey.isNotBlank() &&
                        model.isNotBlank() && !testing,
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
        fetchModelsError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        testResult?.let { result ->
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
        Text(
            text = "仅验证地址、密钥、模型与协议连通性，不校验当前 request 参数。",
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor(),
        )
    }
}

/**
 * 接口类型下拉（OpenAI 兼容 / Anthropic / Gemini / 自定义）。
 * 对话、连接测试、模型列表均按所选类型走对应协议（见 LlmClient 的 ChatProtocol 分发）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun ApiTypeSelectorField(
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
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

/**
 * API 配置编辑对话框。
 *
 * 功能：接口类型选择（OpenAI 兼容 / Anthropic / Gemini / 自定义）、获取模型列表、
 * 连接测试（按类型走对应协议）、完整配置 JSON 编辑/校验/脱敏复制、上下文预算、设为默认。
 *
 * 对话、连接测试与模型列表均按接口类型走对应协议；request 扩展字段与预览
 * 共用协议的合并和保护规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
fun ApiConfigEditorDialog(
    apiConfig: ApiConfig? = null,
    viewModel: ApiConfigViewModel? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        apiUrl: String,
        apiKey: String,
        model: String,
        setAsDefault: Boolean,
        type: String,
        context1M: Boolean,
        streamingEnabled: Boolean,
        contextTokenLimit: Int,
        configJson: String,
    ) -> Unit,
) {
    val initialDraft =
        remember(apiConfig?.id) {
            val base =
                (apiConfig?.configJson?.let { ApiConfigJson.parse(it) } as? ApiConfigJsonResult.Valid)?.document?.copy(
                    id = apiConfig.id,
                    name = apiConfig.name,
                    type = normalizeApiType(apiConfig.type),
                    baseUrl = ApiConfigJson.stripUrlCredentials(apiConfig.apiUrl),
                    model = apiConfig.model,
                    streamingEnabled = apiConfig.streamingEnabled,
                    contextTokenLimit = apiConfig.contextTokenLimit,
                )
                    ?: ApiConfigJson.fromFields(
                        id = apiConfig?.id ?: UUID.randomUUID().toString(),
                        name = apiConfig?.name.orEmpty(),
                        type = normalizeApiType(apiConfig?.type ?: ApiType.OPENAI),
                        baseUrl = ApiConfigJson.stripUrlCredentials(apiConfig?.apiUrl.orEmpty()),
                        model = apiConfig?.model.orEmpty(),
                        hasApiKey = apiConfig?.apiKey?.isNotBlank() == true,
                        streamingEnabled = apiConfig?.streamingEnabled ?: true,
                        contextTokenLimit = apiConfig?.contextTokenLimit ?: ApiConfigJson.DEFAULT_CONTEXT_TOKEN_LIMIT,
                    )
            // 打开即物化完整模板（含 null 占位），但不写库；已有 request 的显式值和未知字段原样保留。
            val materialized = base.copy(request = ApiRequestTemplate.materialize(base.request, base.type))
            materialized to (materialized.request != base.request)
        }
    val initialDocument = initialDraft.first
    val draftNotice =
        when {
            apiConfig == null -> "新配置草稿：保存后才会成为此 API 的独立配置。"
            initialDraft.second -> "已补齐协议默认参数，保存后成为此 API 的独立配置。"
            else -> null
        }
    var document by remember { mutableStateOf(initialDocument) }
    var jsonValue by remember {
        mutableStateOf(TextFieldValue(ApiConfigEditorLogic.formatForEditor(initialDocument, apiConfig?.apiKey?.isNotBlank() == true)))
    }
    var jsonError by remember { mutableStateOf<String?>(null) }
    // 用户是否手动改过 JSON 文本：只有手改坏的草稿才阻止表单字段重新生成 JSON；
    // 表单自动草稿在填写过程中暂时不完整（model/name 为空）不属于受保护对象。
    var manualJsonEdited by remember { mutableStateOf(false) }
    var apiUrlError by remember { mutableStateOf<String?>(null) }
    var protocolNotice by remember { mutableStateOf<String?>(null) }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(initialDocument.name) }
    var type by remember { mutableStateOf(initialDocument.type) }
    var apiUrl by remember { mutableStateOf(initialDocument.baseUrl) }
    var apiKey by remember { mutableStateOf(apiConfig?.apiKey ?: "") }
    var model by remember { mutableStateOf(initialDocument.model) }
    var contextTokenLimit by remember { mutableStateOf(initialDocument.contextTokenLimit) }
    var context1M by remember { mutableStateOf(initialDocument.contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT) }
    var streamingEnabled by remember { mutableStateOf(initialDocument.streamingEnabled) }
    var setAsDefault by remember { mutableStateOf(apiConfig?.isDefault ?: false) }
    var showApiKey by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var responseBudgetExpanded by remember { mutableStateOf(false) }
    var previewConnectionExpanded by remember { mutableStateOf(false) }
    var previewGeneratedExpanded by remember { mutableStateOf(false) }
    var previewExpandedGroups by remember { mutableStateOf(setOf(defaultPreviewGroupKey(initialDocument.type))) }

    val fetchedModels by viewModel?.fetchedModels?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
    val fetchingModels by viewModel?.fetchingModels?.collectAsState() ?: remember { mutableStateOf(false) }
    val fetchModelsError by viewModel?.fetchModelsError?.collectAsState() ?: remember { mutableStateOf<String?>(null) }

    /** 程序化替换 JSON 文本时把光标放到末尾；文本未变时不打断用户当前选区。 */
    fun setJsonText(text: String) {
        if (jsonValue.text != text) {
            jsonValue = TextFieldValue(text, selection = TextRange(text.length))
        }
    }

    /**
     * 基础字段修改：用户手改坏的 JSON 草稿优先，保留原文与错误；
     * 否则（表单自动草稿）总是重新生成并清除自动草稿上的暂时性校验错误。
     */
    fun editBase(next: ApiConfigDocument) {
        document = next
        if (!manualJsonEdited) jsonError = null
        setJsonText(
            ApiConfigEditorLogic.afterBaseEdit(
                jsonValue.text,
                jsonError,
                next,
                apiKey.isNotBlank(),
                protectInvalidDraft = manualJsonEdited,
            ),
        )
    }

    /** “重置为协议模板”恢复当前协议的完整参数模板（不是清空 request），允许覆盖草稿。 */
    fun resetJson(next: ApiConfigDocument) {
        document = next
        setJsonText(ApiConfigEditorLogic.formatForEditor(next, apiKey.isNotBlank()))
        jsonError = null
        manualJsonEdited = false
    }

    fun updateFromJson(text: String) {
        val edit = ApiConfigEditorLogic.afterJsonEdit(text)
        setJsonText(edit.text)
        jsonError = edit.error
        val parsed = edit.parsed ?: return
        val imported =
            parsed.copy(
                id = apiConfig?.id ?: parsed.id,
                baseUrl =
                    if (parsed.baseUrl == ApiConfigJson.redactUrl(document.baseUrl)) {
                        document.baseUrl
                    } else {
                        parsed.baseUrl
                    },
            )
        document = imported
        name = imported.name
        type = imported.type
        apiUrl = imported.baseUrl
        model = imported.model
        contextTokenLimit = imported.contextTokenLimit
        context1M = imported.contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT
        streamingEnabled = imported.streamingEnabled
    }

    fun performReset() {
        resetJson(
            ApiConfigJson.fromFields(
                id = apiConfig?.id ?: document.id,
                name = name,
                type = type,
                baseUrl = apiUrl,
                model = model,
                hasApiKey = apiKey.isNotBlank(),
                streamingEnabled = streamingEnabled,
                contextTokenLimit = contextTokenLimit,
                request = ApiRequestTemplate.templateFor(type),
            ),
        )
        protocolNotice = null
        clipboardMessage = "已重置为当前协议模板"
        showResetConfirm = false
    }

    fun requestReset() {
        if (ApiConfigEditorLogic.requiresResetConfirmation(jsonValue.text, type)) {
            showResetConfirm = true
        } else {
            performReset()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding(),
            ) {
                DialogTopActions(
                    title = if (apiConfig == null) "添加 API 配置" else "编辑 API 配置",
                    name = name,
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    model = model,
                    onDismiss = onDismiss,
                    onConfirm = {
                        when (val parsed = ApiConfigJson.parse(jsonValue.text)) {
                            is ApiConfigJsonResult.Invalid -> {
                                jsonError = parsed.message
                                // 切到请求参数页展示错误：JSON 输入框可能从未组合，
                                // 直接 requestFocus 未 attach 的 FocusRequester 会崩溃
                                selectedTab = 1
                            }
                            is ApiConfigJsonResult.Valid -> {
                                if (ApiConfigJson.sanitizeBaseUrl(apiUrl) == null) {
                                    apiUrlError = "API 地址包含无法安全清理的凭据或格式错误，请修正后再保存"
                                    selectedTab = 0
                                } else {
                                    jsonError = null
                                    val finalDocument =
                                        ApiConfigEditorLogic.finalDocument(
                                            parsed = parsed.document,
                                            id = apiConfig?.id ?: parsed.document.id,
                                            name = name,
                                            type = type,
                                            apiUrl = apiUrl,
                                            model = model,
                                            streamingEnabled = streamingEnabled,
                                            contextTokenLimit = contextTokenLimit,
                                        )
                                    onConfirm(
                                        finalDocument.name,
                                        finalDocument.baseUrl,
                                        apiKey,
                                        finalDocument.model,
                                        setAsDefault,
                                        finalDocument.type,
                                        context1M,
                                        finalDocument.streamingEnabled,
                                        finalDocument.contextTokenLimit,
                                        ApiConfigJson.format(ApiConfigJson.redacted(finalDocument, apiKey.isNotBlank())),
                                    )
                                }
                            }
                        }
                    },
                )
                TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("基础配置", "请求参数", "请求预览").forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, maxLines = 1) },
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) {
                    when (selectedTab) {
                        0 ->
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 20.dp, vertical = 16.dp)
                                        .padding(bottom = 64.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                EditorSectionHeader(
                                    title = "基础配置",
                                    subtitle = "连接信息与默认模型",
                                )

                                // 配置名称
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = {
                                        name = it
                                        editBase(document.copy(name = it))
                                    },
                                    label = { Text("配置名称") },
                                    placeholder = { Text("例如：OpenAI API") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Label, null)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                )

                                // 接口类型（选中自动填充预设地址）
                                ApiTypeSelectorField(
                                    type = type,
                                    onTypeChange = { newType ->
                                        val normalized = normalizeApiType(newType)
                                        val previousType = type
                                        val switch = ApiConfigEditorLogic.requestForTypeSwitch(document.request, previousType, normalized)
                                        type = normalized
                                        previewExpandedGroups = setOf(defaultPreviewGroupKey(normalized))
                                        apiUrl = resolvePresetUrl(normalized, apiUrl)
                                        apiUrlError = null
                                        protocolNotice =
                                            if (switch.keptUserRequest) {
                                                "已切换协议；request 保留了你修改的字段，请核对参数名是否适用于新协议。"
                                            } else {
                                                null
                                            }
                                        editBase(document.copy(type = normalized, baseUrl = apiUrl, request = switch.request))
                                    },
                                )
                                protocolNotice?.let { notice ->
                                    Text(
                                        text = notice,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }

                                // API 地址
                                OutlinedTextField(
                                    value = apiUrl,
                                    onValueChange = {
                                        apiUrl = it
                                        apiUrlError = null
                                        editBase(document.copy(baseUrl = ApiConfigJson.stripUrlCredentials(it)))
                                    },
                                    label = { Text("API 地址") },
                                    placeholder = {
                                        Text(defaultBaseUrlForType(type).ifBlank { "https://your-gateway.example.com/v1" })
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Link, null)
                                    },
                                    isError = apiUrlError != null,
                                    supportingText = {
                                        apiUrlError?.let { Text(it) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                )

                                // API 密钥
                                ApiKeyField(
                                    apiKey = apiKey,
                                    onApiKeyChange = { newKey ->
                                        apiKey = newKey
                                        // Key 不在 JSON 内：重校验当前草稿，清掉“合法草稿上的陈旧 jsonError”；
                                        // 若草稿本身非法，则保留原文并继续展示真实错误。
                                        val edit =
                                            ApiConfigEditorLogic.afterApiKeyEdit(
                                                jsonValue.text,
                                                newKey.isNotBlank(),
                                                protectInvalidDraft = manualJsonEdited,
                                            )
                                        setJsonText(edit.text)
                                        jsonError = edit.error
                                        edit.parsed?.let { parsed ->
                                            document =
                                                parsed.copy(
                                                    id = apiConfig?.id ?: parsed.id,
                                                    baseUrl =
                                                        if (parsed.baseUrl == ApiConfigJson.redactUrl(document.baseUrl)) {
                                                            document.baseUrl
                                                        } else {
                                                            parsed.baseUrl
                                                        },
                                                )
                                        }
                                    },
                                )

                                // 模型输入与获取/连接工具
                                ApiModelField(
                                    model = model,
                                    onModelChange = {
                                        model = it
                                        editBase(document.copy(model = it))
                                    },
                                    fetchedModels = fetchedModels,
                                )
                                DialogApiToolsRow(
                                    apiUrl = apiUrl,
                                    apiKey = apiKey,
                                    model = model,
                                    type = type,
                                    viewModel = viewModel,
                                    fetchingModels = fetchingModels,
                                    fetchModelsError = fetchModelsError,
                                )

                                CollapsibleSection(
                                    title = "响应与本地预算",
                                    subtitle = "SSE、上下文长度与默认配置",
                                    expanded = responseBudgetExpanded,
                                    onToggle = { responseBudgetExpanded = !responseBudgetExpanded },
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SettingsSwitchRow(
                                            title = "SSE 流式传输",
                                            subtitle = "逐步显示模型回复；关闭后等待整包返回",
                                            checked = streamingEnabled,
                                            onCheckedChange = {
                                                streamingEnabled = it
                                                editBase(document.copy(streamingEnabled = it))
                                            },
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "上下文长度：${formatContextLimit(contextTokenLimit)}",
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Slider(
                                                value = contextSliderValue(contextTokenLimit),
                                                onValueChange = { value ->
                                                    contextTokenLimit = contextLimitFromSlider(value)
                                                    context1M = contextTokenLimit >= ApiConfigJson.MAX_CONTEXT_TOKEN_LIMIT
                                                    editBase(document.copy(contextTokenLimit = contextTokenLimit))
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
                                        SettingsSwitchRow(
                                            title = "设为默认配置",
                                            subtitle = "作为新会话的默认 API",
                                            icon = Icons.Default.Star,
                                            checked = setAsDefault,
                                            onCheckedChange = { setAsDefault = it },
                                        )
                                    }
                                }
                            }
                        1 ->
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 20.dp, vertical = 16.dp)
                                        .padding(bottom = 64.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                EditorSectionHeader(
                                    title = "请求参数 JSON",
                                    subtitle = "模板自带默认值；null = 未设置且不会发送；可直接修改或复制给 AI；API Key 不会进入 JSON。",
                                )
                                draftNotice?.let { notice ->
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

                                // 完整配置文档：表单字段只是同一个有效文档对象的投影。
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = jsonValue,
                                        onValueChange = { value ->
                                            manualJsonEdited = true
                                            jsonValue = value
                                            updateFromJson(value.text)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 8,
                                        maxLines = 14,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        isError = jsonError != null,
                                        supportingText = {
                                            Text(jsonError ?: clipboardMessage.orEmpty())
                                        },
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val clipboard = LocalClipboardManager.current
                                        TextButton(
                                            onClick = {
                                                when (val result = ApiConfigJson.parse(jsonValue.text)) {
                                                    is ApiConfigJsonResult.Valid -> {
                                                        updateFromJson(
                                                            ApiConfigEditorLogic.formatForEditor(result.document, apiKey.isNotBlank()),
                                                        )
                                                        clipboardMessage = "已格式化"
                                                    }
                                                    is ApiConfigJsonResult.Invalid -> jsonError = result.message
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) { Text("格式化") }
                                        TextButton(
                                            onClick = {
                                                val safe =
                                                    ApiConfigEditorLogic.safeJsonForClipboard(
                                                        jsonValue.text,
                                                        apiKey.isNotBlank(),
                                                    )
                                                if (safe != null) {
                                                    clipboard.setText(AnnotatedString(safe))
                                                    clipboardMessage = "已复制脱敏 JSON"
                                                } else {
                                                    jsonError =
                                                        (ApiConfigJson.parse(jsonValue.text) as? ApiConfigJsonResult.Invalid)
                                                            ?.message
                                                            ?: "配置 JSON 校验失败"
                                                    clipboardMessage = "草稿未通过安全校验，已阻止复制（可能含凭据）"
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) { Text("复制脱敏") }
                                        TextButton(
                                            onClick = {
                                                clipboard.getText()?.text?.let { text ->
                                                    manualJsonEdited = true
                                                    updateFromJson(text)
                                                }
                                                clipboardMessage = "已粘贴，保存前仍需通过校验"
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) { Text("粘贴") }
                                        Box {
                                            var resetMenuExpanded by remember { mutableStateOf(false) }
                                            IconButton(onClick = { resetMenuExpanded = true }) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                                            }
                                            DropdownMenu(
                                                expanded = resetMenuExpanded,
                                                onDismissRequest = { resetMenuExpanded = false },
                                            ) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text("重置为协议模板", color = MaterialTheme.colorScheme.error)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                    },
                                                    onClick = {
                                                        resetMenuExpanded = false
                                                        requestReset()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    if (jsonError == null) {
                                        val previewDiff = ApiConfigJson.diff(initialDocument, document)
                                        if (!previewDiff.isEmpty) {
                                            Text(
                                                text = changePreviewText(previewDiff),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = secondaryTextColor(),
                                            )
                                        }
                                    }
                                }
                            }
                        else ->
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 20.dp, vertical = 16.dp)
                                        .padding(bottom = 64.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                RequestPreviewBlock(
                                    document = document,
                                    isJsonValid = jsonError == null,
                                    expandedGroups = previewExpandedGroups,
                                    onToggleGroup = { groupKey ->
                                        previewExpandedGroups =
                                            if (groupKey in previewExpandedGroups) {
                                                previewExpandedGroups - groupKey
                                            } else {
                                                previewExpandedGroups + groupKey
                                            }
                                    },
                                    connectionExpanded = previewConnectionExpanded,
                                    onToggleConnection = { previewConnectionExpanded = !previewConnectionExpanded },
                                    generatedExpanded = previewGeneratedExpanded,
                                    onToggleGenerated = { previewGeneratedExpanded = !previewGeneratedExpanded },
                                )
                            }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置为协议模板？") },
            text = { Text("将丢弃当前参数修改和自定义字段，基础配置保留。") },
            confirmButton = {
                TextButton(
                    onClick = ::performReset,
                ) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 提示词编辑对话框（已在 PromptManagement.kt 中实现，这里保持一致性）
 */
@Composable
fun PromptEditorDialogWrapper(
    prompt: CustomPrompt? = null,
    onDismiss: () -> Unit,
    onConfirm: (CustomPrompt) -> Unit,
) {
    PromptEditorDialog(
        prompt = prompt,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

private val CONTEXT_LIMIT_STEPS = listOf(4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1_000_000)

private fun contextSliderValue(limit: Int): Float =
    CONTEXT_LIMIT_STEPS
        .indexOfFirst { it >= limit }
        .coerceAtLeast(0)
        .toFloat()

private fun contextLimitFromSlider(value: Float): Int = CONTEXT_LIMIT_STEPS[value.roundToInt().coerceIn(0, CONTEXT_LIMIT_STEPS.lastIndex)]

private fun formatContextLimit(limit: Int): String =
    when {
        limit >= 1_000_000 -> "1M tokens"
        limit >= 1024 -> "${limit / 1024}K tokens"
        else -> "$limit tokens"
    }

private fun changePreviewText(diff: ApiConfigJsonDiff): String {
    fun fields(values: List<String>): String = values.joinToString().ifBlank { "无" }
    val schema = if (diff.modified.contains("schemaVersion")) "schemaVersion 已变化" else "schemaVersion 未变化"
    return "变更预览：新增 ${fields(diff.added)}；删除 ${fields(diff.removed)}；修改 ${fields(diff.modified)}；$schema"
}

@Composable
@Suppress("FunctionNaming")
private fun EditorSectionHeader(
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
@Suppress("FunctionNaming", "LongParameterList")
private fun RequestPreviewBlock(
    document: ApiConfigDocument,
    isJsonValid: Boolean,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    connectionExpanded: Boolean,
    onToggleConnection: () -> Unit,
    generatedExpanded: Boolean,
    onToggleGenerated: () -> Unit,
) {
    val preview = ApiRequestPreview.from(document)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EditorSectionHeader(
            title = "请求预览",
            subtitle = "配置合并结果 · 非实时请求",
        )
        if (!isJsonValid) {
            Text(
                text = "修正 JSON 后查看预览",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor(),
            )
        } else {
            Text(
                text = "修改 ${preview.userFieldCount} · 默认 ${preview.defaultFieldCount} · 保护忽略 ${preview.ignoredFields.size}",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor(),
            )

            CollapsibleSection(
                title = "连接与本地预算",
                subtitle = "地址、认证与 contextTokenLimit",
                expanded = connectionExpanded,
                onToggle = onToggleConnection,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    previewLine("接口类型：${apiTypeLabel(document.type)}（${document.type}）")
                    previewLine("地址：${ApiConfigJson.redactUrl(document.baseUrl)}")
                    previewLine("认证：由 SecureStore 生成（已隐藏）")
                    previewLine("contextTokenLimit=${document.contextTokenLimit}（本地 prompt 输入预算）")
                }
            }

            CollapsiblePreviewGroup(
                title = "协议生成字段",
                summary = "${preview.generatedFields.size} 项",
                fields = preview.generatedFields,
                expanded = generatedExpanded,
                onToggle = onToggleGenerated,
            )

            preview.parameterGroups.forEach { group ->
                val stateKey = "${document.type}:${group.id}"
                RequestPreviewGroupBlock(
                    group = group,
                    expanded = stateKey in expandedGroups,
                    onToggle = { onToggleGroup(stateKey) },
                )
            }

            if (preview.ignoredFields.isNotEmpty()) {
                val stateKey = "${document.type}:ignored"
                CollapsiblePreviewGroup(
                    title = "保护忽略字段",
                    summary = "${preview.ignoredFields.size} 项",
                    fields = preview.ignoredFields,
                    expanded = stateKey in expandedGroups,
                    onToggle = { onToggleGroup(stateKey) },
                )
            }

            Text(
                text = "预览不含调用级覆盖；max_tokens / maxOutputTokens 是模型输出预算，contextTokenLimit 是本地输入预算。",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor(),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun CollapsibleSection(
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
@Suppress("FunctionNaming")
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
@Suppress("FunctionNaming")
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
@Suppress("FunctionNaming")
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
private fun secondaryTextColor() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

@Composable
@Suppress("FunctionNaming")
private fun previewLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = secondaryTextColor(),
    )
}
