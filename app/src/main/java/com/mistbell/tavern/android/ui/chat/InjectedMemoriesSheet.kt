package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.model.MemoryType
import com.mistbell.tavern.android.data.api.model.StructuredMemory

/** 忘记确认框里预览记忆正文的截断长度（只示意，不是数据操作） */
private const val FORGET_PREVIEW_CHARS = 40

/** 还回顾了的早先对话行（只读展示，不是结构化记忆，不能编辑/忘记） */
data class RecalledMessageLine(
    val messageId: String,
    val content: String,
)

/**
 * 注入明细弹层数据（「记得 N 件」点开）。
 *
 * 由 ChatViewModel 在打开时按 id 回库取正文：已被删除（忘掉）的记忆不再出现；
 * 编辑/忘记后即时刷新这份快照。
 */
data class InjectedMemoriesDetail(
    val messageId: String,
    val memories: List<StructuredMemory>,
    val recallLines: List<RecalledMessageLine>,
)

data class InjectedMemoriesSheetCallbacks(
    val onDismiss: () -> Unit,
    /** 保存编辑（参数：记忆、新正文）；归属与落库由 ChatViewModel 走仓库层 */
    val onEditMemory: (StructuredMemory, String) -> Unit,
    /** 忘记（已经过确认）；下一轮不再注入，且从所有消息的清单里移除 */
    val onForgetMemory: (StructuredMemory) -> Unit,
    /** 进入记忆管理（Routes.memoryList(sessionId, characterId)，导航由宿主层接线） */
    val onOpenMemoryManagement: () -> Unit,
)

/**
 * 「记忆可见 v0」注入明细底部弹层。
 *
 * 结构硬性要求：**整个弹层只有这一个 [LazyColumn]**——标题、分组、条目、按钮全部是列表项，
 * 条目多时下面的「进入记忆管理」始终可达（不套 Column+verticalScroll，不写不可滚动的 Column）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InjectedMemoriesSheet(
    detail: InjectedMemoriesDetail?,
    callbacks: InjectedMemoriesSheetCallbacks,
) {
    var editingMemory by remember { mutableStateOf<StructuredMemory?>(null) }
    var forgettingMemory by remember { mutableStateOf<StructuredMemory?>(null) }
    ModalBottomSheet(
        onDismissRequest = callbacks.onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        InjectedMemoriesSheetContent(
            detail = detail,
            onEditClick = { editingMemory = it },
            onForgetClick = { forgettingMemory = it },
            onOpenMemoryManagement = callbacks.onOpenMemoryManagement,
        )
    }
    editingMemory?.let { memory ->
        EditInjectedMemoryDialog(
            memory = memory,
            onDismiss = { editingMemory = null },
            onSave = { newContent ->
                callbacks.onEditMemory(memory, newContent)
                editingMemory = null
            },
        )
    }
    forgettingMemory?.let { memory ->
        ForgetInjectedMemoryDialog(
            memory = memory,
            onDismiss = { forgettingMemory = null },
            onConfirm = {
                callbacks.onForgetMemory(memory)
                forgettingMemory = null
            },
        )
    }
}

@Composable
private fun InjectedMemoriesSheetContent(
    detail: InjectedMemoriesDetail?,
    onEditClick: (StructuredMemory) -> Unit,
    onForgetClick: (StructuredMemory) -> Unit,
    onOpenMemoryManagement: () -> Unit,
) {
    val memories = detail?.memories.orEmpty()
    val recallLines = detail?.recallLines.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            SheetHeader()
        }
        if (detail == null || (memories.isEmpty() && recallLines.isEmpty())) {
            item(key = "empty") {
                Text(
                    text = "没有可显示的记忆",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        injectedMemoriesSection(memories, onEditClick, onForgetClick)
        recalledLinesSection(recallLines)
        item(key = "manage") {
            Button(
                onClick = onOpenMemoryManagement,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Text("进入记忆管理")
            }
        }
        item(key = "bottom-inset") {
            Spacer(modifier = Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

/** 「记住的事」分组：标题 + 每条结构化记忆（编辑/忘记入口） */
private fun LazyListScope.injectedMemoriesSection(
    memories: List<StructuredMemory>,
    onEditClick: (StructuredMemory) -> Unit,
    onForgetClick: (StructuredMemory) -> Unit,
) {
    if (memories.isEmpty()) return
    item(key = "structured-header") {
        Text(
            text = "记住的事",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    items(
        items = memories,
        key = { "memory-${it.id}" },
        contentType = { "memory-row" },
    ) { memory ->
        InjectedMemoryRow(
            memory = memory,
            onEdit = { onEditClick(memory) },
            onForget = { onForgetClick(memory) },
        )
    }
}

/** 「还回顾了早先对话」分组（只读，仅有召回行时出现） */
private fun LazyListScope.recalledLinesSection(recallLines: List<RecalledMessageLine>) {
    if (recallLines.isEmpty()) return
    item(key = "recall-header") {
        Text(
            text = "还回顾了 ${recallLines.size} 段早先对话",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    items(
        items = recallLines,
        key = { "recall-${it.messageId}" },
        contentType = { "recall-row" },
    ) { line ->
        RecalledLineRow(line)
    }
}

@Composable
private fun SheetHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "本轮注入的记忆",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "这些内容随本轮提示词发给了 AI；改动立即生效，忘记后下一轮不再注入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 「记住的事」单条：类型 + 重要度标签 + 正文，操作「编辑」「忘记」 */
@Composable
private fun InjectedMemoryRow(
    memory: StructuredMemory,
    onEdit: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeLabel(memory.memoryType)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "重要度 ${memory.importance}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Text("编辑", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onForget) {
                    Text(
                        "忘记",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TypeLabel(memoryType: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = MemoryType.getDescription(memoryType),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 「还回顾了早先对话」单条：只读历史消息正文 */
@Composable
private fun RecalledLineRow(line: RecalledMessageLine) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
            ),
    ) {
        Text(
            text = line.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 编辑对话框：只改正文（类型/重要度等归属与分类不动，与记忆管理页的编辑语义一致） */
@Composable
private fun EditInjectedMemoryDialog(
    memory: StructuredMemory,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by remember(memory.id) { mutableStateOf(memory.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑记忆") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank() && content != memory.content,
                onClick = { onSave(content.trim()) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 忘记确认对话框：忘记即删除，下一轮不再注入 */
@Composable
private fun ForgetInjectedMemoryDialog(
    memory: StructuredMemory,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("忘记这条记忆？") },
        text = {
            Text(
                text = "「${memory.content.take(FORGET_PREVIEW_CHARS)}」将从记忆库删除，下一轮对话不再注入。此操作不可撤销。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("忘记", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
