package com.mistbell.tavern.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.ProviderConfig
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.repository.ProviderRepository
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class ChatSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.database
    private val providerRepo = ProviderRepository(application)
    private val settingsRepo = SettingsRepository(application)
    private val worldBookRepo = WorldBookRepository(application)

    // 所有角色
    val characters: StateFlow<List<Character>> = db.characterDao()
        .getAll()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有提供商
    val providers: StateFlow<List<ProviderConfig>> = providerRepo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有世界书
    val worldBooks: StateFlow<List<WorldBook>> = worldBookRepo.observeWorldBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 选中的角色 IDs
    private val _selectedCharacterIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedCharacterIds: StateFlow<Set<String>> = _selectedCharacterIds.asStateFlow()

    // 选中的提供商 ID
    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    // 选中的世界书 ID。"" = 无，具体 ID = 选中的世界书
    private val _selectedWorldBookId = MutableStateFlow<String>("")
    val selectedWorldBookId: StateFlow<String> = _selectedWorldBookId.asStateFlow()

    // 标记用户是否手动选择过世界书
    private var hasUserSelectedWorldBook = false

    // 角色卡默认世界书 ID（多角色取首个有 worldBookId 的角色）；随选中角色变化。
    val characterDefaultWorldBookId: StateFlow<String?> =
        combine(_selectedCharacterIds, characters) { ids, chars ->
            ids.firstNotNullOfOrNull { id ->
                chars.find { it.id == id }?.worldBookId?.takeIf { wb -> wb.isNotBlank() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // 长期记忆开关初始值取全局默认；此后用户在界面上手动切换则尊重用户选择
        viewModelScope.launch {
            _enableLongTermMemory.value = settingsRepo.defaultLtmEnabled()
        }
        // 当角色选择变化时，自动更新世界书默认值（仅在用户未手动选择时）
        viewModelScope.launch {
            characterDefaultWorldBookId.collect { defaultId ->
                if (!hasUserSelectedWorldBook) {
                    _selectedWorldBookId.value = defaultId ?: ""
                }
            }
        }
    }

    // 长期记忆开关
    private val _enableLongTermMemory = MutableStateFlow(false)
    val enableLongTermMemory: StateFlow<Boolean> = _enableLongTermMemory.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // 初始化：从初始角色 ID 设置
    fun initialize(initialCharacterId: String?) {
        if (initialCharacterId != null && _selectedCharacterIds.value.isEmpty()) {
            _selectedCharacterIds.value = setOf(initialCharacterId)
        }
    }

    fun toggleCharacter(characterId: String) {
        val current = _selectedCharacterIds.value.toMutableSet()
        if (current.contains(characterId)) {
            current.remove(characterId)
        } else {
            if (current.size >= 4) {
                showToast("最多选择 4 个角色")
                return
            }
            current.add(characterId)
        }
        _selectedCharacterIds.value = current
    }

    fun setSelectedProvider(providerId: String?) {
        _selectedProviderId.value = providerId
    }

    // "" = 无世界书；具体 ID = 用户手动指定。
    fun setSelectedWorldBook(worldBookId: String) {
        hasUserSelectedWorldBook = true
        _selectedWorldBookId.value = worldBookId
    }

    fun toggleLongTermMemory() {
        _enableLongTermMemory.value = !_enableLongTermMemory.value
    }

    fun canStartChat(): Boolean {
        return _selectedCharacterIds.value.isNotEmpty()
    }

    // 创建页永远创建新会话；旧会话从最近聊天列表进入。
    suspend fun getOrCreateSession(characterIds: Set<String>, ownerId: String = "local-user"): String {
        if (characterIds.isEmpty()) return "new"

        val characterId = characterIds.first() // 使用第一个角色作为主角色

        return createNewSession(characterId, characterIds, ownerId)
    }

    private suspend fun createNewSession(characterId: String, characterIds: Set<String>, ownerId: String): String {
        android.util.Log.d("ChatSetup", "Creating new session for character $characterId")
        val sessionId = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        // 获取选中的 provider 信息，如果未选择则使用第一个可用的
        var selectedProvider = _selectedProviderId.value
        if (selectedProvider.isNullOrBlank()) {
            selectedProvider = providers.value.firstOrNull()?.id ?: ""
            android.util.Log.d("ChatSetup", "No provider selected, using first available: $selectedProvider")
        }

        val selectedModel = providers.value.find { it.id == selectedProvider }?.selectedModel ?: ""
        android.util.Log.d("ChatSetup", "Creating session with providerId: $selectedProvider, modelId: $selectedModel")

        // 世界书 ID 直接使用选中值
        val sessionWorldBookId = _selectedWorldBookId.value

        val session = SessionEntity(
            id = sessionId,
            ownerId = ownerId,
            characterId = characterId,
            title = "新对话",
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
            providerId = selectedProvider,
            modelId = selectedModel,
            worldBookId = sessionWorldBookId,
            summaryJson = "",
            unreadCount = 0,
            isPinned = false,
            pinnedAt = null,
            isMuted = false,
            // 长期记忆：优先尊重用户在设置页的显式开关；初始缺省值已在 init 中读全局默认
            enableLongTermMemory = _enableLongTermMemory.value,
            // 上下文 token 预算：新会话读全局默认（原实体缺省 4096）
            contextTokenLimit = settingsRepo.defaultContextTokens(),
            participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(characterIds)
        )

        db.sessionDao().upsert(session)

        // 插入角色的开场白（firstMes）
        val characterEntity = db.characterDao().getById(characterId)
        android.util.Log.d("ChatSetup", "Character found: ${characterEntity?.name}, firstMes: ${characterEntity?.firstMes}")

        if (characterEntity != null && characterEntity.firstMes.isNotBlank()) {
            // F2.1：开场白先用宏引擎渲染（{{char}}/{{user}} 等），用户名取 settings，缺省 "User"
            val mctx = com.mistbell.tavern.android.util.MacroContext(
                char = characterEntity.name,
                user = db.settingsDao().getValue("user_name") ?: "User",
                description = characterEntity.description,
                personality = characterEntity.personality,
                scenario = characterEntity.scenario,
                persona = ""
            )
            val firstMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                ownerId = ownerId,
                characterId = characterId,
                role = "assistant",
                content = com.mistbell.tavern.android.util.MacroEngine.render(characterEntity.firstMes, mctx),
                thinking = null,
                createdAt = now,
                memoryIdsJson = "[]",
                swipesJson = "[]",
                swipeIndex = 0,
                thinkingSwipesJson = "[]",
                isRead = true
            )
            db.messageDao().upsert(firstMessage)
            android.util.Log.d("ChatSetup", "FirstMes inserted: ${characterEntity.firstMes}")

            // 更新会话消息数
            db.sessionDao().upsert(session.copy(messageCount = 1, updatedAt = now))
        } else {
            android.util.Log.d("ChatSetup", "No firstMes to insert")
        }
        return sessionId
    }

    fun showToast(message: String) {
        _toast.value = message
    }

    fun clearToast() {
        _toast.value = null
    }
}
