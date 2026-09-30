package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import kotlinx.coroutines.launch

/**
 * 全局提示词模板设置卡：主提示词 / 用户名称 / 用户人设 / 群聊规范。
 *
 * 四项都是全局 KV（明文，自动纳入备份），键常量与解析函数收敛在 [ChatSettingsResolver]。
 * 空值语义：主提示词与用户人设**不注入**该段；群聊规范回落内置默认模板；
 * 用户名称回落 "User"。因此这里一律展示**原始存储值**，默认值只在副标题里说明。
 */
@Suppress("FunctionNaming")
@Composable
internal fun PromptTemplateCard(viewModel: SettingsViewModel) {
    val mainPrompt by viewModel.mainPromptSetting.collectAsState()
    val userName by viewModel.userNameSetting.collectAsState()
    val userPersona by viewModel.userPersonaSetting.collectAsState()
    val groupRules by viewModel.groupChatRulesSetting.collectAsState()

    var dialog by remember { mutableStateOf<PromptTemplateKind?>(null) }

    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsNavItem(
                title = "主提示词",
                subtitle = "所有会话共用的基础系统提示，支持 {{char}}、{{user}} 等宏（留空不注入）",
                onClick = { dialog = PromptTemplateKind.MAIN_PROMPT },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsNavItem(
                title = "用户名称",
                subtitle = "{{user}} 宏的取值，也是群聊历史里你的显示名（留空用 “User”）",
                onClick = { dialog = PromptTemplateKind.USER_NAME },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsNavItem(
                title = "用户人设",
                subtitle = "{{persona}} 宏的内容：你是谁、有什么设定（留空不注入）",
                onClick = { dialog = PromptTemplateKind.USER_PERSONA },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsNavItem(
                title = "群聊规范",
                subtitle = "群聊时的行为规范模板，{user} 表示用户名（留空用内置默认）",
                onClick = { dialog = PromptTemplateKind.GROUP_CHAT_RULES },
            )
        }
    }

    dialog?.let { kind ->
        PromptTemplateDialog(
            kind = kind,
            value =
                when (kind) {
                    PromptTemplateKind.MAIN_PROMPT -> mainPrompt
                    PromptTemplateKind.USER_NAME -> userName
                    PromptTemplateKind.USER_PERSONA -> userPersona
                    PromptTemplateKind.GROUP_CHAT_RULES -> groupRules
                },
            onSave = { edited ->
                viewModel.saveTextSetting(
                    key = kind.key,
                    value = edited,
                    savedMessage = "${kind.title}已保存",
                )
            },
            onDismiss = { dialog = null },
        )
    }
}

/** 四个提示词模板项：标题 / 说明 / 键 / 占位提示 / 可否一键填入默认值 */
private enum class PromptTemplateKind(
    val title: String,
    val description: String,
    val key: String,
    val placeholder: String,
    /** 非空表示提供「填入默认内容」按钮（把默认文本灌进编辑框，便于在此基础上改写） */
    val defaultToFill: String? = null,
) {
    MAIN_PROMPT(
        title = "主提示词",
        description =
            "所有会话共用的基础系统提示。注入在整条提示词最前（世界书「角色定义前」条目之前），" +
                "支持 {{char}}、{{user}}、{{persona}}、{{time}} 等宏。留空则完全不注入这一段。",
        key = ChatSettingsResolver.KEY_MAIN_PROMPT,
        placeholder = "你是一个乐于扮演角色的助手……",
    ),
    USER_NAME(
        title = "用户名称",
        description =
            "{{user}} 宏的取值，同时用于群聊历史的说话方前缀。留空或全空白时回落为 “User”。",
        key = ChatSettingsResolver.KEY_USER_NAME,
        placeholder = ChatSettingsResolver.DEFAULT_USER_NAME,
    ),
    USER_PERSONA(
        title = "用户人设",
        description =
            "{{persona}} 宏的内容，用来描述你自己（身份、性格、与角色的关系等）。" +
                "注入在角色卡之后。留空则不注入这一段。",
        key = ChatSettingsResolver.KEY_USER_PERSONA,
        placeholder = "我是一名大学生，性格内向……",
    ),
    GROUP_CHAT_RULES(
        title = "群聊规范",
        description =
            "群聊模式下的行为规范块，注入在角色卡之后。{user}（单花括号）会被替换成你的用户名称。" +
                "留空则使用内置默认模板——下方按钮可把默认内容填进编辑框供你改写。",
        key = ChatSettingsResolver.KEY_GROUP_CHAT_RULES,
        placeholder = "（留空使用内置默认模板）",
        defaultToFill = ChatSettingsResolver.DEFAULT_GROUP_CHAT_RULES,
    ),
}

/**
 * 提示词模板编辑对话框（四个键共用）。
 *
 * 与「记忆提取提示词」对话框同样的形态：多行文本 + 可选「填入默认」+ 保存/取消；
 * 保存空串即恢复"未设置"语义（不注入 / 回落内置默认）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptTemplateDialog(
    kind: PromptTemplateKind,
    value: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 以外部值为 key：备份恢复或别处改动后，重新打开即为最新值
    var edited by remember(value) { mutableStateOf(value) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = kind.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = kind.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 460.dp),
                    placeholder = {
                        Text(
                            kind.placeholder,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                )

                kind.defaultToFill?.let { defaultText ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { edited = defaultText },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("填入内置默认")
                        }
                        OutlinedButton(
                            onClick = { edited = "" },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("清空（用默认）")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        onSave(edited)
                        onDismiss()
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
