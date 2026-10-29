package com.mistbell.tavern.android.ui.character

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.regex.RegexRuleDraft

@Composable
internal fun RuleListItem(
    state: RuleItemState,
    viewModel: RegexEditingContract,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = state.rule
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (state.isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectRule(rule.id) }.padding(12.dp),
        ) {
            RuleListItemHeader(
                rule = rule,
                viewModel = viewModel,
            )
            RuleListItemActions(
                state = state,
                viewModel = viewModel,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

@Composable
private fun RuleListItemHeader(
    rule: RegexRuleDraft,
    viewModel: RegexEditingContract,
    modifier: Modifier = Modifier,
) {
    val targets =
        buildList {
            if (rule.userInput) add("用户输入")
            if (rule.aiOutput) add("AI 输出")
        }
    val nameColor =
        if (rule.disabled) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.titleSmall,
                color = nameColor,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "目标：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (targets.isEmpty()) {
                    Text(
                        text = "未启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                } else {
                    targets.forEach { target ->
                        Card(
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Text(
                                text = target,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Switch(
            checked = !rule.disabled,
            onCheckedChange = { enabled -> viewModel.updateRule(rule.id) { copy(disabled = !enabled) } },
        )
    }
}

@Composable
private fun RuleListItemActions(
    state: RuleItemState,
    viewModel: RegexEditingContract,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = state.rule
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { viewModel.moveRule(rule.id, -1) },
            enabled = state.canMoveUp,
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
        }
        IconButton(
            onClick = { viewModel.moveRule(rule.id, 1) },
            enabled = state.canMoveDown,
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
        }
        IconButton(onClick = { viewModel.addRule(rule.id) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
        }
        IconButton(onClick = onRequestDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
