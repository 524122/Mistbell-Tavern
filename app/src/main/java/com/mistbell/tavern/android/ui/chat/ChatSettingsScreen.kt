package com.mistbell.tavern.android.ui.chat

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.ProviderConfig
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.repository.ProviderRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val providerRepo = ProviderRepository(application)
    private val worldBookRepo = WorldBookRepository(application)
    private val db = TavernApplication.instance.database

    val providers: StateFlow<List<ProviderConfig>> = providerRepo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val characters: StateFlow<List<Character>> = db.characterDao()
        .getAll()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val worldBooks: StateFlow<List<WorldBook>> = worldBookRepo.observeWorldBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    private val _selectedModelId = MutableStateFlow("")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _enableLongTermMemory = MutableStateFlow(false)
    val enableLongTermMemory: StateFlow<Boolean> = _enableLongTermMemory.asStateFlow()

    private val _contextTokenLimit = MutableStateFlow(4096)
    val contextTokenLimit: StateFlow<Int> = _contextTokenLimit.asStateFlow()

    private val _selectedCharacterIds = MutableStateFlow<List<String>>(emptyList())
    val selectedCharacterIds: StateFlow<List<String>> = _selectedCharacterIds.asStateFlow()

    // 选中的世界书 ID。"" = 无，具体 ID = 选中的世界书
    private val _selectedWorldBookId = MutableStateFlow<String>("")
    val selectedWorldBookId: StateFlow<String> = _selectedWorldBookId.asStateFlow()

    // 主角色默认世界书 ID（仅用于内部逻辑，UI 不展示）
    private val _characterDefaultWorldBookId = MutableStateFlow<String?>(null)
    val characterDefaultWorldBookId: StateFlow<String?> = _characterDefaultWorldBookId.asStateFlow()

    private var currentSessionId: String? = null
    private var mainCharacterId: String = ""

    fun loadSessionSettings(sessionId: String) {
        currentSessionId = sessionId
        viewModelScope.launch {
            try {
                val session = db.sessionDao().getById(sessionId)
                if (session != null) {
                    mainCharacterId = session.characterId
                    _selectedProviderId.value = session.providerId.takeIf { it.isNotBlank() }
                    _selectedModelId.value = session.modelId
                    _enableLongTermMemory.value = session.enableLongTermMemory
                    _contextTokenLimit.value = session.contextTokenLimit.coerceIn(1024, 1_000_000)
                    _selectedCharacterIds.value = session.participantCharacterIds()
                    // 直接使用 session.worldBookId（空串 = "无"）
                    _selectedWorldBookId.value = session.worldBookId
                    _characterDefaultWorldBookId.value =
                        db.characterDao().getById(session.characterId)?.worldBookId?.takeIf { it.isNotBlank() }
                    android.util.Log.d("ChatSettings", "Loaded session settings: providerId=${session.providerId}, modelId=${session.modelId}, enableLongTermMemory=${session.enableLongTermMemory}, contextTokenLimit=${session.contextTokenLimit}, worldBookId=${session.worldBookId}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatSettings", "Failed to load session settings", e)
            }
        }
    }

    fun setSelectedProvider(providerId: String?) {
        _selectedProviderId.value = providerId
        // 同时更新数据库中的会话设置
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        val provider = providers.value.find { it.id == providerId }
                        val modelId = provider?.selectedModel ?: ""
                        _selectedModelId.value = modelId
                        db.sessionDao().upsert(
                            session.copy(
                                providerId = providerId ?: "",
                                modelId = modelId,
                                updatedAt = java.time.Instant.now().toString()
                            )
                        )
                        android.util.Log.d("ChatSettings", "Updated session: providerId=$providerId, modelId=$modelId")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update session settings", e)
                }
            }
        }
    }

    // "" = 无世界书；具体 ID = 指定世界书
    fun setSelectedWorldBook(worldBookId: String) {
        _selectedWorldBookId.value = worldBookId
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                worldBookId = worldBookId,
                                updatedAt = java.time.Instant.now().toString()
                            )
                        )
                        android.util.Log.d("ChatSettings", "Updated session worldBookId: ${if (worldBookId.isBlank()) "(none)" else worldBookId}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update world book setting", e)
                }
            }
        }
    }

    fun toggleLongTermMemory() {
        _enableLongTermMemory.value = !_enableLongTermMemory.value
        // 同时更新数据库中的会话设置
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                enableLongTermMemory = _enableLongTermMemory.value,
                                updatedAt = java.time.Instant.now().toString()
                            )
                        )
                        android.util.Log.d("ChatSettings", "Updated enableLongTermMemory: ${_enableLongTermMemory.value}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update long term memory setting", e)
                }
            }
        }
    }

    fun updateContextTokenLimit(value: Int) {
        val normalized = value.coerceIn(1024, 1_000_000)
        _contextTokenLimit.value = normalized
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                contextTokenLimit = normalized,
                                updatedAt = java.time.Instant.now().toString()
                            )
                        )
                        android.util.Log.d("ChatSettings", "Updated contextTokenLimit: $normalized")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update context token limit", e)
                }
            }
        }
    }

    fun toggleParticipantCharacter(characterId: String) {
        val current = _selectedCharacterIds.value.toMutableList()
        if (current.contains(characterId)) {
            if (current.size <= 1) return
            current.remove(characterId)
        } else {
            if (current.size >= 4) return
            current.add(characterId)
        }
        saveParticipantCharacters(current)
    }

    fun makePrimaryCharacter(characterId: String) {
        val current = _selectedCharacterIds.value.toMutableList()
        if (!current.contains(characterId)) {
            if (current.size >= 4) return
            current.add(characterId)
        }
        current.remove(characterId)
        current.add(0, characterId)
        saveParticipantCharacters(current)
    }

    private fun saveParticipantCharacters(ids: List<String>) {
        val normalized = ids
            .ifEmpty { listOf(mainCharacterId) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
        _selectedCharacterIds.value = normalized
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(normalized),
                                updatedAt = java.time.Instant.now().toString()
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update participant characters", e)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit,
    onNavigateToMemoryManagement: () -> Unit,
    sessionId: String? = null,
    viewModel: ChatSettingsViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val providers by viewModel.providers.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val selectedProviderId by viewModel.selectedProviderId.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val selectedWorldBookId by viewModel.selectedWorldBookId.collectAsState()
    val characterDefaultWorldBookId by viewModel.characterDefaultWorldBookId.collectAsState()
    val enableLongTermMemory by viewModel.enableLongTermMemory.collectAsState()
    val contextTokenLimit by viewModel.contextTokenLimit.collectAsState()
    val selectedCharacterIds by viewModel.selectedCharacterIds.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }
    var showCharacterDialog by remember { mutableStateOf(false) }
    var showWorldBookDialog by remember { mutableStateOf(false) }
    val selectedCharacters = remember(characters, selectedCharacterIds) {
        selectedCharacterIds.mapNotNull { id -> characters.find { it.id == id } }
    }

    // 加载当前会话的设置
    LaunchedEffect(sessionId) {
        sessionId?.let {
            viewModel.loadSessionSettings(it)
        }
    }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "返回",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = "聊天设置",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "角色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCharacterDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompositeCharacterAvatar(
                        characters = selectedCharacters,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前角色", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = selectedCharacters.joinToString("、") { it.name }.ifBlank { "未选择" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 模型设置
            Text(
                text = "模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showProviderDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前模型", style = MaterialTheme.typography.bodySmall)
                        Text(
                            providers.find { it.id == selectedProviderId }?.let { provider ->
                                "${provider.name} - ${selectedModelId.ifBlank { provider.selectedModel }}"
                            } ?: "未配置",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 世界书设置
            Text(
                text = "世界书",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWorldBookDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前世界书", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (selectedWorldBookId.isBlank()) {
                                "无"
                            } else {
                                worldBooks.find { it.id == selectedWorldBookId }?.name ?: "未知世界书"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 记忆
            Text(
                text = "记忆",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // 记忆管理
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToMemoryManagement() }
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
                            text = "记忆管理",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "查看和编辑结构化记忆",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 长期记忆开关
            Card(
                modifier = Modifier.fillMaxWidth()
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
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "保存对话内容到长期记忆",
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

            // 上下文长度
            Text(
                text = "高级",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "上下文长度",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "控制 AI 能记住多少对话历史",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${formatTokenLimit(contextTokenLimit)} tokens",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Slider(
                        value = tokenLimitToSliderValue(contextTokenLimit),
                        onValueChange = {
                            viewModel.updateContextTokenLimit(sliderValueToTokenLimit(it))
                        },
                        valueRange = 0f..7f,
                        steps = 6
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        contextTokenPresets.forEach { preset ->
                            FilterChip(
                                selected = contextTokenLimit == preset,
                                onClick = { viewModel.updateContextTokenLimit(preset) },
                                label = { Text(formatTokenLimit(preset)) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCharacterDialog) {
        AlertDialog(
            onDismissRequest = { showCharacterDialog = false },
            title = { Text("选择聊天角色") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "可选择 1-4 个角色；第一位作为主角色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    characters.forEach { character ->
                        val selected = selectedCharacterIds.contains(character.id)
                        val isPrimary = selectedCharacterIds.firstOrNull() == character.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleParticipantCharacter(character.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CompositeCharacterAvatar(
                                    characters = listOf(character),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(character.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (isPrimary) "主角色" else if (selected) "参与聊天" else "点击加入",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selected && !isPrimary) {
                                    TextButton(onClick = { viewModel.makePrimaryCharacter(character.id) }) {
                                        Text("设为主")
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCharacterDialog = false }) {
                    Text("完成")
                }
            }
        )
    }

    // 提供商选择对话框
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("选择模型提供商") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (providers.isEmpty()) {
                        Text(
                            "暂无提供商，请先在设置中添加",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        providers.forEach { provider ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setSelectedProvider(provider.id)
                                        showProviderDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            provider.name,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (provider.selectedModel.isNotBlank()) {
                                            Text(
                                                "模型: ${provider.selectedModel}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (selectedProviderId == provider.id) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 世界书选择对话框
    if (showWorldBookDialog) {
        AlertDialog(
            onDismissRequest = { showWorldBookDialog = false },
            title = { Text("选择世界书") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // "无" 选项
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setSelectedWorldBook("")
                                showWorldBookDialog = false
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("无", fontWeight = FontWeight.Medium)
                            if (selectedWorldBookId.isBlank()) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // 世界书列表
                    worldBooks.forEach { book ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setSelectedWorldBook(book.id)
                                    showWorldBookDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(book.name.ifBlank { "未命名世界书" }, fontWeight = FontWeight.Medium)
                                if (selectedWorldBookId == book.id) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWorldBookDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

private val contextTokenPresets = listOf(
    2048,
    4096,
    8192,
    16384,
    32768,
    65536,
    131072,
    1_000_000
)

private fun tokenLimitToSliderValue(tokenLimit: Int): Float {
    val index = contextTokenPresets.indexOfFirst { it >= tokenLimit }
        .takeIf { it >= 0 }
        ?: (contextTokenPresets.lastIndex)
    return index.toFloat()
}

private fun sliderValueToTokenLimit(value: Float): Int {
    val index = value.toInt().coerceIn(0, contextTokenPresets.lastIndex)
    return contextTokenPresets[index]
}

private fun formatTokenLimit(value: Int): String {
    return when {
        value >= 1_000_000 -> "1M"
        value >= 1024 -> "${value / 1024}K"
        else -> value.toString()
    }
}
