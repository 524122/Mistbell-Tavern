package com.mistbell.tavern.android.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.regex.RegexRuleDraft
import com.mistbell.tavern.android.ui.components.FormTextArea
import com.mistbell.tavern.android.ui.components.FormTextField
import com.mistbell.tavern.android.ui.components.SectionHeader

@Composable
internal fun RuleEditorCard(
    rule: RegexRuleDraft,
    viewModel: RegexEditingContract,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader("规则编辑")
            RuleBasicSection(rule = rule, viewModel = viewModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RulePlacementSection(rule = rule, viewModel = viewModel)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RuleDepthSection(rule = rule, viewModel = viewModel)
            RuleValidationText(rule = rule)
        }
    }
}

@Composable
private fun RuleBasicSection(
    rule: RegexRuleDraft,
    viewModel: RegexEditingContract,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow(
            label = "启用规则",
            checked = !rule.disabled,
            onCheckedChange = { enabled ->
                viewModel.updateRule(rule.id) { copy(disabled = !enabled) }
            },
        )
        FormTextField(
            value = rule.name,
            onValueChange = { viewModel.updateRule(rule.id) { copy(name = it) } },
            label = "名称",
            placeholder = "规则名称",
        )
        FormTextArea(
            value = rule.findRegex,
            onValueChange = { viewModel.updateRule(rule.id) { copy(findRegex = it) } },
            label = "匹配表达式（Regex）",
            placeholder = "正则表达式",
            minLines = 2,
        )
        FormTextArea(
            value = rule.replaceString,
            onValueChange = { viewModel.updateRule(rule.id) { copy(replaceString = it) } },
            label = "替换字符串",
            placeholder = "替换文本，支持 $1、$2 等捕获组",
            minLines = 2,
        )
    }
}

@Composable
private fun RulePlacementSection(
    rule: RegexRuleDraft,
    viewModel: RegexEditingContract,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("应用目标")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CheckRow(
                label = "用户输入",
                checked = rule.userInput,
                onCheckedChange = { viewModel.updateRule(rule.id) { copy(userInput = it) } },
                modifier = Modifier.weight(1f),
            )
            CheckRow(
                label = "AI 输出",
                checked = rule.aiOutput,
                onCheckedChange = { viewModel.updateRule(rule.id) { copy(aiOutput = it) } },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CheckRow(
                label = "仅 Markdown",
                checked = rule.markdownOnly,
                onCheckedChange = { viewModel.updateRule(rule.id) { copy(markdownOnly = it) } },
                modifier = Modifier.weight(1f),
            )
            CheckRow(
                label = "仅 Prompt",
                checked = rule.promptOnly,
                onCheckedChange = { viewModel.updateRule(rule.id) { copy(promptOnly = it) } },
                modifier = Modifier.weight(1f),
            )
        }
        CheckRow(
            label = "编辑时运行",
            checked = rule.runOnEdit,
            onCheckedChange = { viewModel.updateRule(rule.id) { copy(runOnEdit = it) } },
        )
    }
}

@Composable
private fun RuleDepthSection(
    rule: RegexRuleDraft,
    viewModel: RegexEditingContract,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("深度与修剪")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormTextField(
                value = rule.minDepthText,
                onValueChange = { viewModel.updateRule(rule.id) { copy(minDepthText = it) } },
                label = "最小深度",
                keyboardType = KeyboardType.Text,
                modifier = Modifier.weight(1f),
            )
            FormTextField(
                value = rule.maxDepthText,
                onValueChange = { viewModel.updateRule(rule.id) { copy(maxDepthText = it) } },
                label = "最大深度",
                keyboardType = KeyboardType.Text,
                modifier = Modifier.weight(1f),
            )
        }
        FormTextArea(
            value = rule.trimStringsText,
            onValueChange = { viewModel.updateRule(rule.id) { copy(trimStringsText = it) } },
            label = "修剪字符串（每行一个）",
            placeholder = "会在匹配前后移除这些字符串",
            minLines = 2,
        )
    }
}

@Composable
private fun RuleValidationText(rule: RegexRuleDraft) {
    val error = rule.validationError()
    if (error != null) {
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun CheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
