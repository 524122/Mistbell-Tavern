package com.mistbell.tavern.android.ui.prompt

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.theme.AccentBlue
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptPreviewScreen(
    onBack: () -> Unit,
    viewModel: PromptPreviewViewModel = viewModel()
) {
    val characters by viewModel.characters.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val selectedCharacterId by viewModel.selectedCharacterId.collectAsState()
    val selectedWorldBookId by viewModel.selectedWorldBookId.collectAsState()
    val testMessage by viewModel.testMessage.collectAsState()
    val previewResult by viewModel.previewResult.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(22.dp))
                        }
                        Text("提示词预览", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Character selector
            item {
                SectionHeader("角色")
                Spacer(modifier = Modifier.height(8.dp))
                var charExpanded by remember { mutableStateOf(false) }
                val selectedChar = characters.find { it.id == selectedCharacterId }
                ExposedDropdownMenuBox(expanded = charExpanded, onExpandedChange = { charExpanded = it }) {
                    OutlinedTextField(
                        value = selectedChar?.name ?: "选择角色",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("角色") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = charExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(expanded = charExpanded, onDismissRequest = { charExpanded = false }) {
                        characters.forEach { char ->
                            DropdownMenuItem(text = { Text(char.name) }, onClick = {
                                viewModel.setSelectedCharacterId(char.id); charExpanded = false
                            })
                        }
                    }
                }
            }

            // World book selector
            item {
                SectionHeader("世界书")
                Spacer(modifier = Modifier.height(8.dp))
                var wbExpanded by remember { mutableStateOf(false) }
                val selectedWb = worldBooks.find { it.id == selectedWorldBookId }
                ExposedDropdownMenuBox(expanded = wbExpanded, onExpandedChange = { wbExpanded = it }) {
                    OutlinedTextField(
                        value = selectedWb?.name ?: "无",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("世界书") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wbExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(expanded = wbExpanded, onDismissRequest = { wbExpanded = false }) {
                        DropdownMenuItem(text = { Text("无") }, onClick = {
                            viewModel.setSelectedWorldBookId(""); wbExpanded = false
                        })
                        worldBooks.forEach { wb ->
                            DropdownMenuItem(text = { Text(wb.name) }, onClick = {
                                viewModel.setSelectedWorldBookId(wb.id); wbExpanded = false
                            })
                        }
                    }
                }
            }

            // Test message
            item {
                SectionHeader("测试消息")
                Spacer(modifier = Modifier.height(8.dp))
                FormTextArea(
                    value = testMessage,
                    onValueChange = { viewModel.setTestMessage(it) },
                    label = "输入测试消息",
                    placeholder = "你好！",
                    minLines = 2
                )
            }

            // Preview button
            item {
                Button(
                    onClick = { viewModel.preview() },
                    enabled = !isLoading && selectedCharacterId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("生成预览")
                }
            }

            // Results
            previewResult?.let { result ->
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SectionHeader("预览结果")
                }

                // Token audit
                val sections = result["sections"] as? JsonArray
                sections?.let { sectionList ->
                    item {
                        SectionHeader("提示词段落", modifier = Modifier.padding(top = 8.dp))
                    }
                    items(sectionList.size) { idx ->
                        val section = sectionList[idx] as? JsonObject ?: return@items
                        val name = section["name"]?.jsonPrimitive?.content ?: ""
                        val included = section["included"]?.jsonPrimitive?.booleanOrNull ?: true
                        val tokens = section["tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (included) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (included) "✓" else "✗", modifier = Modifier.width(24.dp),
                                    color = if (included) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                }
                                Text("~${tokens} tokens", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Final system prompt
                val systemPrompt = result["systemPrompt"]?.jsonPrimitive?.content
                systemPrompt?.let {
                    item {
                        SectionHeader("系统提示词", modifier = Modifier.padding(top = 8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                // Recalled memories
                val memories = result["recalledMemories"] as? JsonArray
                memories?.takeIf { it.isNotEmpty() }?.let { memList ->
                    item {
                        SectionHeader("召回记忆 (${memList.size})", modifier = Modifier.padding(top = 8.dp))
                    }
                    items(memList.size) { idx ->
                        val mem = memList[idx] as? JsonObject ?: return@items
                        val content = mem["content"]?.jsonPrimitive?.content ?: ""
                        val score = mem["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(content, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                                Text("%.2f".format(score), style = MaterialTheme.typography.labelSmall,
                                    color = AccentBlue)
                            }
                        }
                    }
                }

                // Activated world info
                val worldInfo = result["activatedWorldInfo"] as? JsonArray
                worldInfo?.takeIf { it.isNotEmpty() }?.let { wiList ->
                    item {
                        SectionHeader("激活世界信息 (${wiList.size})", modifier = Modifier.padding(top = 8.dp))
                    }
                    items(wiList.size) { idx ->
                        val entry = wiList[idx] as? JsonObject ?: return@items
                        val comment = entry["comment"]?.jsonPrimitive?.content ?: ""
                        val content = entry["content"]?.jsonPrimitive?.content ?: ""
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(comment, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(content, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Final messages
                val messages = result["finalMessages"] as? JsonArray
                messages?.takeIf { it.isNotEmpty() }?.let { msgList ->
                    item {
                        SectionHeader("最终消息结构 (${msgList.size})", modifier = Modifier.padding(top = 8.dp))
                    }
                    items(msgList.size) { idx ->
                        val msg = msgList[idx] as? JsonObject ?: return@items
                        val role = msg["role"]?.jsonPrimitive?.content ?: ""
                        val content = msg["content"]?.jsonPrimitive?.content ?: ""
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(role.uppercase(), style = MaterialTheme.typography.labelSmall,
                                    color = AccentBlue, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(content, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                            }
                        }
                    }
                }
            }
        }
    }
}
