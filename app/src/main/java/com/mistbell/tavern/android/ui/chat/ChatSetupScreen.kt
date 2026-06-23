package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSetupScreen(
    initialCharacterId: String? = null,
    onBack: () -> Unit,
    onStartChat: (sessionId: String, characterIds: Set<String>) -> Unit,
    viewModel: ChatSetupViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val characters by viewModel.characters.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val selectedCharacterIds by viewModel.selectedCharacterIds.collectAsState()
    val selectedProviderId by viewModel.selectedProviderId.collectAsState()
    val selectedWorldBookId by viewModel.selectedWorldBookId.collectAsState()
    val characterDefaultWorldBookId by viewModel.characterDefaultWorldBookId.collectAsState()
    val enableLongTermMemory by viewModel.enableLongTermMemory.collectAsState()
    val toast by viewModel.toast.collectAsState()

    var showProviderDropdown by remember { mutableStateOf(false) }
    var showWorldBookDropdown by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialCharacterId) {
        viewModel.initialize(initialCharacterId)
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
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
                        Text(
                            text = "创建聊天",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (selectedCharacterIds.isNotEmpty()) {
                                    coroutineScope.launch {
                                        val sessionId = viewModel.getOrCreateSession(selectedCharacterIds)
                                        onStartChat(sessionId, selectedCharacterIds)
                                    }
                                } else {
                                    viewModel.showToast("请至少选择一个角色")
                                }
                            },
                            enabled = selectedCharacterIds.isNotEmpty()
                        ) {
                            Text("开始聊天")
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 选择角色
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (selectedCharacterIds.size > 1)
                            "选择角色 (${selectedCharacterIds.size} 人群聊)"
                        else "选择角色 (${selectedCharacterIds.size} 已选)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (characters.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                "暂无角色，请先创建或导入角色",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            items(characters) { character ->
                CharacterCard(
                    character = character,
                    isSelected = selectedCharacterIds.contains(character.id),
                    onClick = { viewModel.toggleCharacter(character.id) }
                )
            }

            // 模型选择
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "选择模型",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    ExposedDropdownMenuBox(
                        expanded = showProviderDropdown,
                        onExpandedChange = { showProviderDropdown = it }
                    ) {
                        OutlinedTextField(
                            value = providers.find { it.id == selectedProviderId }?.name ?: "选择提供商",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderDropdown) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )

                        ExposedDropdownMenu(
                            expanded = showProviderDropdown,
                            onDismissRequest = { showProviderDropdown = false }
                        ) {
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(provider.name, fontWeight = FontWeight.Medium)
                                            if (provider.selectedModel.isNotBlank()) {
                                                Text(
                                                    "模型: ${provider.selectedModel}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSelectedProvider(provider.id)
                                        showProviderDropdown = false
                                    },
                                    leadingIcon = if (selectedProviderId == provider.id) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }

            // 世界书选择
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "世界书",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    val fieldValue = if (selectedWorldBookId.isBlank()) {
                        "无"
                    } else {
                        worldBooks.find { it.id == selectedWorldBookId }?.name ?: "未知世界书"
                    }

                    ExposedDropdownMenuBox(
                        expanded = showWorldBookDropdown,
                        onExpandedChange = { showWorldBookDropdown = it }
                    ) {
                        OutlinedTextField(
                            value = fieldValue,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showWorldBookDropdown) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )

                        ExposedDropdownMenu(
                            expanded = showWorldBookDropdown,
                            onDismissRequest = { showWorldBookDropdown = false }
                        ) {
                            // "无" 选项
                            DropdownMenuItem(
                                text = { Text("无", fontWeight = FontWeight.Medium) },
                                onClick = {
                                    viewModel.setSelectedWorldBook("")
                                    showWorldBookDropdown = false
                                },
                                leadingIcon = if (selectedWorldBookId.isBlank()) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )

                            // 世界书列表
                            worldBooks.forEach { book ->
                                DropdownMenuItem(
                                    text = { Text(book.name.ifBlank { "未命名世界书" }) },
                                    onClick = {
                                        viewModel.setSelectedWorldBook(book.id)
                                        showWorldBookDropdown = false
                                    },
                                    leadingIcon = if (selectedWorldBookId == book.id) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }

            // 长期记忆开关
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "长期记忆",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "保存对话内容到长期记忆中",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enableLongTermMemory,
                            onCheckedChange = { viewModel.toggleLongTermMemory() }
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun CharacterCard(
    character: com.mistbell.tavern.android.data.api.model.Character,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
        else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        character.color?.let {
                            try {
                                Color(android.graphics.Color.parseColor(it))
                            } catch (_: Exception) {
                                MaterialTheme.colorScheme.primary
                            }
                        } ?: MaterialTheme.colorScheme.primary
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = character.name.take(1),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (character.description.isNotBlank()) {
                    Text(
                        text = character.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
