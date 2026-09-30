package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import com.mistbell.tavern.android.ui.common.ModernSettingsItem
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.EmptyStateView
import java.util.*

/**
 * 提示词类型
 */
enum class PromptType {
    JAILBREAK, // 破甲提示词
    WRITING_STYLE, // 文风提示词
    CHARACTER, // 角色行为提示词
    CUSTOM, // 自定义提示词
}

/**
 * 提示词注入位置
 */
enum class PromptPosition {
    SYSTEM_START, // 系统提示词开头
    SYSTEM_END, // 系统提示词末尾
    USER_PREFIX, // 用户消息前缀
    USER_SUFFIX, // 用户消息后缀
    ASSISTANT_PREFIX, // AI 回复前缀
}

/**
 * 提示词数据类
 */
data class CustomPrompt(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val content: String,
    val type: PromptType,
    val position: PromptPosition,
    // 优先级，数字越大越优先
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val tags: List<String> = emptyList(),
    val description: String = "",
)

/**
 * 提示词管理界面
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级组件的既有形态（原由 detekt 基线吸收）
@Composable
fun PromptManagementScreen(
    prompts: List<CustomPrompt>,
    onAddPrompt: (CustomPrompt) -> Unit,
    onEditPrompt: (CustomPrompt) -> Unit,
    onDeletePrompt: (String) -> Unit,
    onTogglePrompt: (String, Boolean) -> Unit,
    onReorderPrompts: (List<CustomPrompt>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<CustomPrompt?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ModernTopBar(
                title = "提示词管理",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "添加提示词")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("添加提示词") },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // 使用说明
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PromptUsageGuideCard()
                }
            }

            // 按类型分组显示
            val groupedPrompts = prompts.groupBy { it.type }

            groupedPrompts.forEach { (type, promptsOfType) ->
                item {
                    ModernSettingsGroup(title = getPromptTypeLabel(type)) {
                        promptsOfType.forEach { prompt ->
                            PromptListItem(
                                prompt = prompt,
                                onToggle = { onTogglePrompt(prompt.id, !prompt.isEnabled) },
                                onEdit = { editingPrompt = prompt },
                                onDelete = { onDeletePrompt(prompt.id) },
                            )
                        }
                    }
                }
            }

            // 空状态
            if (prompts.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = "📝",
                        title = "还没有自定义提示词",
                        subtitle = "添加破甲或文风提示词，让对话更加个性化",
                        actionLabel = "添加提示词",
                        onAction = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // 添加/编辑对话框
    if (showAddDialog) {
        PromptEditorDialog(
            prompt = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { newPrompt ->
                onAddPrompt(newPrompt)
                showAddDialog = false
            },
        )
    }

    editingPrompt?.let { prompt ->
        PromptEditorDialog(
            prompt = prompt,
            onDismiss = { editingPrompt = null },
            onConfirm = { updatedPrompt ->
                onEditPrompt(updatedPrompt)
                editingPrompt = null
            },
        )
    }
}

/**
 * 提示词列表项：标题 + 摘要 + 开关/更多菜单
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun PromptListItem(
    prompt: CustomPrompt,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    ModernSettingsItem(
        title = prompt.name,
        subtitle = buildPromptSummary(prompt),
        icon = getPromptTypeIcon(prompt.type),
        onClick = onEdit,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = prompt.isEnabled,
                    onCheckedChange = { onToggle() },
                )

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null)
                            },
                        )
                    }
                }
            }
        },
    )
}

/** 列表项副标题：描述 / 注入位置 / 优先级 / 标签 摘要 */
private fun buildPromptSummary(prompt: CustomPrompt): String {
    val parts =
        buildList {
            if (prompt.description.isNotBlank()) add(prompt.description)
            add(getPositionLabel(prompt.position))
            if (prompt.priority > 0) add("优先级 ${prompt.priority}")
            prompt.tags.forEach { add("#$it") }
        }
    return parts.joinToString(" · ")
}

/**
 * 提示词编辑对话框
 */
@Composable
fun PromptEditorDialog(
    prompt: CustomPrompt?,
    onDismiss: () -> Unit,
    onConfirm: (CustomPrompt) -> Unit,
) {
    var name by remember { mutableStateOf(prompt?.name ?: "") }
    var content by remember { mutableStateOf(prompt?.content ?: "") }
    var description by remember { mutableStateOf(prompt?.description ?: "") }
    var type by remember { mutableStateOf(prompt?.type ?: PromptType.CUSTOM) }
    var position by remember { mutableStateOf(prompt?.position ?: PromptPosition.SYSTEM_END) }
    var priority by remember { mutableStateOf(prompt?.priority ?: 0) }
    var tags by remember { mutableStateOf(prompt?.tags ?: emptyList()) }
    var newTag by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 标题
                Text(
                    text = if (prompt == null) "添加提示词" else "编辑提示词",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                // 名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 描述
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 类型选择
                Column {
                    Text("类型", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PromptType.values().forEach { promptType ->
                            FilterChip(
                                selected = type == promptType,
                                onClick = { type = promptType },
                                label = { Text(getPromptTypeLabel(promptType)) },
                            )
                        }
                    }
                }

                // 注入位置
                Column {
                    Text("注入位置", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PromptPosition.values().forEach { pos ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { position = pos }
                                        .background(
                                            if (position == pos) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                        )
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = position == pos,
                                    onClick = { position = pos },
                                )
                                Text(getPositionLabel(pos))
                            }
                        }
                    }
                }

                // 内容
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("提示词内容") },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                    maxLines = 6,
                )

                // 优先级
                OutlinedTextField(
                    value = priority.toString(),
                    onValueChange = { priority = it.toIntOrNull() ?: 0 },
                    label = { Text("优先级（0-100）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }

                    Button(
                        onClick = {
                            if (name.isNotBlank() && content.isNotBlank()) {
                                onConfirm(
                                    CustomPrompt(
                                        id = prompt?.id ?: UUID.randomUUID().toString(),
                                        name = name,
                                        content = content,
                                        description = description,
                                        type = type,
                                        position = position,
                                        priority = priority,
                                        tags = tags,
                                        isEnabled = prompt?.isEnabled ?: true,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank() && content.isNotBlank(),
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

/**
 * 使用指南卡片
 */
@Composable
fun PromptUsageGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "提示词功能说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Text(
                text =
                    "• 破甲：绕过模型限制，解锁更多可能性\n" +
                        "• 文风：控制 AI 的回复风格和语气\n" +
                        "• 角色行为：定义角色的行为准则\n" +
                        "• 注入位置：控制提示词在对话中的插入位置\n" +
                        "• 优先级：数字越大越优先执行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// 辅助函数
private fun getPromptTypeLabel(type: PromptType): String =
    when (type) {
        PromptType.JAILBREAK -> "🔓 破甲"
        PromptType.WRITING_STYLE -> "✍️ 文风"
        PromptType.CHARACTER -> "👤 角色行为"
        PromptType.CUSTOM -> "⚙️ 自定义"
    }

private fun getPromptTypeIcon(type: PromptType) =
    when (type) {
        PromptType.JAILBREAK -> Icons.Default.Lock
        PromptType.WRITING_STYLE -> Icons.Default.Edit
        PromptType.CHARACTER -> Icons.Default.Person
        PromptType.CUSTOM -> Icons.Default.Settings
    }

private fun getPositionLabel(position: PromptPosition): String =
    when (position) {
        PromptPosition.SYSTEM_START -> "系统提示词开头"
        PromptPosition.SYSTEM_END -> "系统提示词末尾"
        PromptPosition.USER_PREFIX -> "用户消息前缀"
        PromptPosition.USER_SUFFIX -> "用户消息后缀"
        PromptPosition.ASSISTANT_PREFIX -> "AI 回复前缀"
    }
