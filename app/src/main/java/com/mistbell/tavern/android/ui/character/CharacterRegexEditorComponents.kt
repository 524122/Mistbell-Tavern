package com.mistbell.tavern.android.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.regex.CardRegexDocument
import com.mistbell.tavern.android.data.regex.RegexPreviewMode
import com.mistbell.tavern.android.data.regex.RegexPreviewRequest
import com.mistbell.tavern.android.data.regex.RegexPreviewResult
import com.mistbell.tavern.android.data.regex.RegexRuleDraft
import com.mistbell.tavern.android.data.regex.evaluateRegexPreview
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.ConfirmDeleteDialog
import com.mistbell.tavern.android.ui.components.EmptyStateView
import com.mistbell.tavern.android.ui.components.FormTextArea
import com.mistbell.tavern.android.ui.components.FormTextField
import com.mistbell.tavern.android.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RegexManagerActions(
    isDirty: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDirty) {
            Text(
                text = "未保存",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onSave,
            enabled = isDirty && !isSaving,
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("保存")
            }
        }
    }
}

@Composable
internal fun RegexEmptyState(
    onAddRule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyStateView(
        modifier = modifier,
        icon = "📝",
        title = "暂无正则规则",
        subtitle = "添加一条规则来处理角色卡文本",
        actionLabel = "新增规则",
        onAction = onAddRule,
    )
}

@Composable
internal fun RegexDiscardDialog(
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未保存的修改") },
        text = { Text("有未保存的修改，确定要离开吗？") },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    "丢弃",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("继续编辑") }
        },
    )
}

@Composable
internal fun RegexManagerContent(
    uiState: CharacterRegexUiState,
    viewModel: RegexEditingContract,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ModernTopBar(
                title = "正则表达式",
                subtitle = uiState.characterName,
                onBack = onBack,
                actions = {
                    RegexManagerActions(
                        isDirty = uiState.isDirty,
                        isSaving = uiState.isSaving,
                        onSave = { viewModel.save() },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                !uiState.isLoaded -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.notFound -> {
                    EmptyStateView(
                        icon = "!",
                        title = "角色不存在",
                        subtitle = "无法加载该角色的正则设置",
                    )
                }
                uiState.document.rules.isEmpty() -> {
                    RegexEmptyState(
                        onAddRule = { viewModel.addRule() },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> RegexManagerBody(uiState = uiState, viewModel = viewModel)
            }
        }
    }
}

@Composable
internal fun RegexManagerBody(
    uiState: CharacterRegexUiState,
    viewModel: RegexEditingContract,
    modifier: Modifier = Modifier,
) {
    val selectedRule = uiState.document.rules.firstOrNull { it.id == uiState.selectedRuleId }
    var ruleToDelete by remember { mutableStateOf<RegexRuleDraft?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "add-rule") {
            OutlinedButton(
                onClick = { viewModel.addRule() },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("新增规则")
            }
        }

        itemsIndexed(
            items = uiState.document.rules,
            key = { _, rule -> rule.id },
        ) { index, rule ->
            RuleListItem(
                state =
                    RuleItemState(
                        rule = rule,
                        isSelected = rule.id == uiState.selectedRuleId,
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.document.rules.size - 1,
                    ),
                viewModel = viewModel,
                onRequestDelete = { ruleToDelete = rule },
            )
        }

        item(key = "editor") {
            selectedRule?.let { rule ->
                RuleEditorCard(rule = rule, viewModel = viewModel)
            } ?: EmptyStateView(icon = "!", title = "请选择规则")
        }

        item(key = "preview") {
            RegexPreviewCard(
                document = uiState.document,
                request = uiState.preview,
                viewModel = viewModel,
            )
        }
    }

    ruleToDelete?.let { rule ->
        ConfirmDeleteDialog(
            title = "删除规则",
            message = "确定要删除“${rule.name.ifBlank { "未命名" }}”吗？此操作在保存后生效。",
            onConfirm = {
                viewModel.deleteRule(rule.id)
                ruleToDelete = null
            },
            onDismiss = { ruleToDelete = null },
        )
    }
}

internal data class RuleItemState(
    val rule: RegexRuleDraft,
    val isSelected: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

@Composable
internal fun RegexPreviewCard(
    document: CardRegexDocument,
    request: RegexPreviewRequest,
    viewModel: RegexEditingContract,
    modifier: Modifier = Modifier,
) {
    val result by produceState(
        initialValue = RegexPreviewResult(""),
        key1 = document,
        key2 = request,
    ) {
        value =
            withContext(Dispatchers.Default) {
                evaluateRegexPreview(document, request)
            }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader("效果预览")
            PreviewInputSection(
                value = request.input,
                onValueChange = { viewModel.updatePreview { copy(input = it) } },
            )
            PreviewOptionsSection(
                mode = request.mode,
                role = request.role,
                onModeSelected = { viewModel.updatePreview { copy(mode = it) } },
                onRoleSelected = { viewModel.updatePreview { copy(role = it) } },
            )
            FormTextField(
                value = request.depthText,
                onValueChange = { viewModel.updatePreview { copy(depthText = it) } },
                label = "深度",
                keyboardType = KeyboardType.Text,
            )
            SwitchRow(
                label = "编辑消息",
                checked = request.isEdit,
                onCheckedChange = { viewModel.updatePreview { copy(isEdit = it) } },
            )
            PreviewResultSection(result = result)
        }
    }
}

@Composable
internal fun PreviewInputSection(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FormTextArea(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        label = "样例输入",
        placeholder = "输入要测试的文本",
        minLines = 4,
    )
}

@Composable
internal fun PreviewResultSection(
    result: RegexPreviewResult,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (result.skippedInvalidRules > 0) {
            Text(
                text = "有 ${result.skippedInvalidRules} 条无效规则已跳过",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionHeader("结果")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = result.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PreviewOptionsSection(
    mode: RegexPreviewMode,
    role: String,
    onModeSelected: (RegexPreviewMode) -> Unit,
    onRoleSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("模式")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "展示" to RegexPreviewMode.DISPLAY,
                "发送" to RegexPreviewMode.USER_INPUT,
                "提示词" to RegexPreviewMode.PROMPT,
            ).forEach { (label, modeValue) ->
                val selected = mode == modeValue
                OutlinedButton(
                    onClick = { onModeSelected(modeValue) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            containerColor =
                                MaterialTheme.colorScheme.primaryContainer.takeIf { selected } ?: Color.Transparent,
                            contentColor =
                                MaterialTheme.colorScheme.onPrimaryContainer.takeIf { selected }
                                    ?: MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Text(label)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SectionHeader("角色")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = role == "assistant",
                onClick = { onRoleSelected("assistant") },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("AI")
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(
                selected = role == "user",
                onClick = { onRoleSelected("user") },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("用户")
        }
    }
}
