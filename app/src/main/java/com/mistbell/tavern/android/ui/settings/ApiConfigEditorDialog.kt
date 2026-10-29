package com.mistbell.tavern.android.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.ApiConfigJsonResult

/** 编辑页顶部：退出 + 标题 + 保存；缺失必填项时仅禁用保存。 */
@Composable
private fun DialogTopActions(
    title: String,
    state: ApiConfigEditorState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val missingHint =
        ApiConfigEditorLogic.firstMissingRequiredHint(
            state.name,
            state.apiUrl,
            state.apiKey,
            state.model,
        )

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
                        disabledContainerColor =
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
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

internal typealias ApiConfigConfirm = (
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
) -> Unit

/**
 * API 配置编辑对话框。
 *
 * 功能：接口类型选择（OpenAI 兼容 / Anthropic / Gemini / 自定义）、获取模型列表、
 * 连接测试（按类型走对应协议）、完整配置 JSON 编辑/校验/脱敏复制、上下文预算、设为默认。
 *
 * 对话、连接测试与模型列表均按接口类型走对应协议；request 扩展字段与预览
 * 共用协议的合并和保护规则。
 */
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
    val initialDraft = remember(apiConfig?.id) { initialApiConfigDraft(apiConfig) }
    val state = remember { ApiConfigEditorState(apiConfig, initialDraft) }
    state.apiConfig = apiConfig
    state.draftInfo =
        ApiConfigDraftInfo(
            document = initialDraft.first,
            notice =
                when {
                    apiConfig == null -> "新配置草稿：保存后才会成为此 API 的独立配置。"
                    initialDraft.second -> "已补齐协议默认参数，保存后成为此 API 的独立配置。"
                    else -> null
                },
        )
    val fetchedModels by
        viewModel?.fetchedModels?.collectAsState()
            ?: remember { mutableStateOf(emptyList<String>()) }
    val fetchingModels by
        viewModel?.fetchingModels?.collectAsState()
            ?: remember { mutableStateOf(false) }
    val fetchModelsError by
        viewModel?.fetchModelsError?.collectAsState()
            ?: remember { mutableStateOf<String?>(null) }

    ApiConfigEditorDialogContent(
        state = state,
        viewModel = viewModel,
        fetchState = ApiFetchState(fetchedModels, fetchingModels, fetchModelsError),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun ApiConfigEditorDialogContent(
    state: ApiConfigEditorState,
    viewModel: ApiConfigViewModel?,
    fetchState: ApiFetchState,
    onDismiss: () -> Unit,
    onConfirm: ApiConfigConfirm,
) {
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
                    title = if (state.apiConfig == null) "添加 API 配置" else "编辑 API 配置",
                    state = state,
                    onDismiss = onDismiss,
                    onConfirm = { confirmApiConfigSave(state, onConfirm) },
                )
                ApiConfigEditorTabs(
                    state = state,
                    viewModel = viewModel,
                    fetchState = fetchState,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    ResetConfirmationDialog(state)
}

@Composable
private fun ApiConfigEditorTabs(
    state: ApiConfigEditorState,
    viewModel: ApiConfigViewModel?,
    fetchState: ApiFetchState,
    modifier: Modifier,
) {
    TabRow(
        selectedTabIndex = state.selectedTab,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        listOf("基础配置", "请求参数", "请求预览").forEachIndexed { index, title ->
            Tab(
                selected = state.selectedTab == index,
                onClick = { state.selectedTab = index },
                text = { Text(title, maxLines = 1) },
            )
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        when (state.selectedTab) {
            0 -> ApiConfigBasicTab(state, viewModel, fetchState)
            1 -> ApiConfigJsonTab(state, state.draftInfo)
            else -> ApiConfigRequestPreviewTab(state)
        }
    }
}

private fun confirmApiConfigSave(
    state: ApiConfigEditorState,
    onConfirm: ApiConfigConfirm,
) {
    when (val parsed = ApiConfigJson.parse(state.jsonValue.text)) {
        is ApiConfigJsonResult.Invalid -> {
            state.jsonError = parsed.message
            state.selectedTab = 1
        }
        is ApiConfigJsonResult.Valid -> {
            if (ApiConfigJson.sanitizeBaseUrl(state.apiUrl) == null) {
                state.apiUrlError = "API 地址包含无法安全清理的凭据或格式错误，请修正后再保存"
                state.selectedTab = 0
            } else {
                state.jsonError = null
                val finalDocument =
                    ApiConfigEditorLogic.finalDocument(
                        parsed = parsed.document,
                        fields = state.editorFields(parsed.document),
                    )
                onConfirm(
                    finalDocument.name,
                    finalDocument.baseUrl,
                    state.apiKey,
                    finalDocument.model,
                    state.setAsDefault,
                    finalDocument.type,
                    state.context1M,
                    finalDocument.streamingEnabled,
                    finalDocument.contextTokenLimit,
                    ApiConfigJson.format(
                        ApiConfigJson.redacted(finalDocument, state.apiKey.isNotBlank()),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ResetConfirmationDialog(state: ApiConfigEditorState) {
    if (state.showResetConfirm) {
        AlertDialog(
            onDismissRequest = { state.showResetConfirm = false },
            title = { Text("重置为协议模板？") },
            text = { Text("将丢弃当前参数修改和自定义字段，基础配置保留。") },
            confirmButton = {
                TextButton(onClick = state::performReset) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showResetConfirm = false }) {
                    Text("取消")
                }
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
