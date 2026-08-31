package com.mistbell.tavern.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.*
import com.mistbell.tavern.android.data.network.NetworkMonitor
import com.mistbell.tavern.android.data.repository.ChatRepository
import com.mistbell.tavern.android.data.repository.ProviderRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import com.mistbell.tavern.android.data.sync.SyncManager
import com.mistbell.tavern.android.data.api.ApiClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.database
    private val repo = ChatRepository(application)
    private val networkMonitor = NetworkMonitor(application)
    private val providerRepo = ProviderRepository(application)
    private val worldBookRepo = WorldBookRepository(application)

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping

    private val _characters = MutableStateFlow<List<Character>>(emptyList())
    val characters: StateFlow<List<Character>> = _characters

    private val _currentCharacter = MutableStateFlow<Character?>(null)
    val currentCharacter: StateFlow<Character?> = _currentCharacter

    private val _participantCharacters = MutableStateFlow<List<Character>>(emptyList())
    val participantCharacters: StateFlow<List<Character>> = _participantCharacters

    private val _recentSessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val recentSessions: StateFlow<List<SessionSummary>> = _recentSessions

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    // Navigation events
    private val _navigationEvent = MutableStateFlow<String?>(null)
    val navigationEvent: StateFlow<String?> = _navigationEvent

    fun navigateTo(route: String) { _navigationEvent.value = route }
    fun clearNavigationEvent() { _navigationEvent.value = null }

    // Provider/Model state
    val providers: StateFlow<List<ProviderConfig>> = providerRepo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val activeModelId: StateFlow<String> = providerRepo.observeActiveModelId()
        .stateIn(viewModelScope, SharingStarted.Lazily, "")
    val activeProviderId: StateFlow<String> = providerRepo.observeActiveProviderId()
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    // World book state
    val worldBooks: StateFlow<List<WorldBook>> = worldBookRepo.observeWorldBooks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val _activeWorldBookId = MutableStateFlow("")
    val activeWorldBookId: StateFlow<String> = _activeWorldBookId

    var ownerId = "local-user"
        private set

    private var _currentCharacterId = ""
    private var _isSessionExplicitlySet = false
    private var messageObserverJob: kotlinx.coroutines.Job? = null
    private var characterObserverJob: kotlinx.coroutines.Job? = null

    init {
        networkMonitor.start()
        // 不在 init 中调用 loadLocalState()，等待显式的 loadSession() 调用

        // 移除自动同步 - 安卓和Web数据完全隔离，本地优先
        // 用户如果需要同步可以手动触发
    }

    private fun loadLocalState(characterId: String? = null, sessionId: String? = null) {
        val charId = characterId ?: _currentCharacterId

        // Observe characters
        viewModelScope.launch {
            repo.observeCharacters().collect { chars ->
                _characters.value = chars
                if (_currentCharacter.value == null && chars.isNotEmpty()) {
                    val target = chars.find { it.id == charId } ?: chars.first()
                    _currentCharacter.value = target
                    _currentCharacterId = target.id
                }
            }
        }

        // Observe recent sessions
        viewModelScope.launch {
            repo.observeRecentSessions(ownerId).collect { sessions ->
                _recentSessions.value = sessions
            }
        }

        // Load active session and observe messages
        viewModelScope.launch {
            if (!_isSessionExplicitlySet) {
                val activeId = sessionId ?: repo.getActiveSessionId(ownerId, charId)
                _activeSessionId.value = activeId
            }
            if (_activeSessionId.value.isNotBlank() && charId.isNotBlank()) {
                // 统一走 startObservingMessages，与 loadSession/selectCharacter 共用同一个可取消的观察 job
                startObservingMessages(ownerId, charId, _activeSessionId.value)
            }
        }
    }

    fun selectCharacter(character: Character) {
        _currentCharacter.value = character
        _currentCharacterId = character.id
        _messages.value = emptyList()
        // 先同步取消旧观察：getActiveSessionId 是挂起调用，期间旧 observer 仍会写 _messages；
        // 且新角色没有会话时（activeId 为空）也必须保证旧会话观察已停止
        messageObserverJob?.cancel()
        viewModelScope.launch {
            val activeId = repo.getActiveSessionId(ownerId, character.id)
            _activeSessionId.value = activeId
            if (activeId.isNotBlank()) {
                startObservingMessages(ownerId, character.id, activeId)
            }
        }
    }

    // 统一的消息观察入口：先取消旧 job，再启动新观察，防止多流竞写 _messages
    private fun startObservingMessages(ownerId: String, characterId: String, sessionId: String) {
        messageObserverJob?.cancel()
        messageObserverJob = viewModelScope.launch {
            if (sessionId.isNotBlank() && characterId.isNotBlank()) {
                repo.observeMessages(ownerId, characterId, sessionId).collect { msgs ->
                    _messages.value = msgs
                }
            }
        }
    }

    // 公共方法：加载指定会话
    fun loadSession(sessionId: String, characterId: String) {
        android.util.Log.d("ChatViewModel", "loadSession called: sessionId=$sessionId, characterId=$characterId")
        android.util.Log.d("ChatViewModel", "Before set: _activeSessionId=${_activeSessionId.value}")

        // 取消之前的观察
        messageObserverJob?.cancel()
        characterObserverJob?.cancel()

        _activeSessionId.value = sessionId
        _currentCharacterId = characterId
        _isSessionExplicitlySet = true
        android.util.Log.d("ChatViewModel", "After set: _activeSessionId=${_activeSessionId.value}")

        characterObserverJob = viewModelScope.launch {
            combine(
                repo.observeCharacters(),
                db.sessionDao().observeById(sessionId)
            ) { chars, session ->
                chars to session
            }.collect { (chars, session) ->
                _characters.value = chars
                val target = chars.find { it.id == characterId }
                if (target != null) {
                    _currentCharacter.value = target
                    android.util.Log.d("ChatViewModel", "Character loaded: ${target.name}")
                }

                val participantIds = session?.participantCharacterIds() ?: listOf(characterId)
                _participantCharacters.value = participantIds
                    .mapNotNull { id -> chars.find { it.id == id } }
                    .ifEmpty { target?.let { listOf(it) } ?: emptyList() }
            }
        }

        // 加载消息
        startObservingMessages(ownerId, characterId, sessionId)
    }

    fun sendMessage(content: String) {
        val char = _currentCharacter.value ?: return
        if (content.isBlank()) return

        android.util.Log.d("ChatViewModel", "sendMessage called")
        android.util.Log.d("ChatViewModel", "  _activeSessionId.value=${_activeSessionId.value}")
        android.util.Log.d("ChatViewModel", "  characterId=${char.id}")
        android.util.Log.d("ChatViewModel", "  _isSessionExplicitlySet=$_isSessionExplicitlySet")

        viewModelScope.launch {
            _isTyping.value = true
            try {
                repo.sendMessage(
                    ownerId = ownerId,
                    characterId = char.id,
                    sessionId = _activeSessionId.value,
                    message = content
                )
                android.util.Log.d("ChatViewModel", "Message sent successfully")
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to send message", e)
                _error.value = "发送失败: ${e.message}"
            } finally {
                _isTyping.value = false
            }
        }
    }

    fun undoLastMessage() {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            try {
                repo.undoLastMessage(ownerId, char.id, _activeSessionId.value)
            } catch (e: Exception) {
                _error.value = "撤销失败: ${e.message}"
            }
        }
    }

    fun backtrackToMessage(messageId: String) {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            try {
                repo.backtrackToMessage(ownerId, char.id, _activeSessionId.value, messageId)
            } catch (e: Exception) {
                _error.value = "回退失败: ${e.message}"
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            _isTyping.value = true
            try {
                repo.regenerateMessage(ownerId, char.id, _activeSessionId.value, messageId)
            } catch (e: Exception) {
                _error.value = "重新生成失败: ${e.message}"
            } finally {
                _isTyping.value = false
            }
        }
    }

    fun continueMessage() {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            _isTyping.value = true
            try {
                repo.continueMessage(ownerId, char.id, _activeSessionId.value)
            } catch (e: Exception) {
                _error.value = "继续失败: ${e.message}"
            } finally {
                _isTyping.value = false
            }
        }
    }

    fun swipeMessage(messageId: String, direction: String) {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            try {
                repo.swipeMessage(ownerId, char.id, _activeSessionId.value, messageId, direction)
            } catch (e: Exception) {
                _error.value = "切换失败: ${e.message}"
            }
        }
    }

    fun newChat() {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            try {
                val sessionId = repo.createSession(ownerId, char.id)
                _activeSessionId.value = sessionId
                _messages.value = emptyList()
                // Observe new session's messages
                startObservingMessages(ownerId, char.id, sessionId)
            } catch (e: Exception) {
                _error.value = "创建会话失败: ${e.message}"
            }
        }
    }

    fun switchSession(sessionId: String) {
        val char = _currentCharacter.value ?: return
        _activeSessionId.value = sessionId
        _messages.value = emptyList()
        startObservingMessages(ownerId, char.id, sessionId)
    }

    fun deleteSession(sessionId: String) {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            try {
                repo.deleteSession(ownerId, char.id, sessionId)
                if (sessionId == _activeSessionId.value) {
                    // 删除的是当前会话：回退到新的 activeSessionId 并重建消息观察，
                    // 否则 observer 仍指向已删除的会话，后续消息 UI 永远看不到
                    val newId = repo.getActiveSessionId(ownerId, char.id)
                    _activeSessionId.value = newId
                    _messages.value = emptyList()
                    messageObserverJob?.cancel()
                    if (newId.isNotBlank()) {
                        startObservingMessages(ownerId, char.id, newId)
                    }
                }
            } catch (e: Exception) {
                _error.value = "删除会话失败: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun copyMessage(content: String) {
        val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("message", content)
        clipboard.setPrimaryClip(clip)
        _toast.value = "已复制"
    }

    fun switchModel(providerId: String, modelId: String) {
        viewModelScope.launch { providerRepo.setActiveProvider(providerId, modelId) }
    }

    fun switchWorldBook(bookId: String) {
        _activeWorldBookId.value = bookId
    }

    fun clearChat() {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            try {
                repo.clearConversation(ownerId, char.id, _activeSessionId.value)
                _messages.value = emptyList()
                _toast.value = "对话已清除"
            } catch (e: Exception) {
                _error.value = "清除失败: ${e.message}"
            }
        }
    }

    fun clearToast() {
        _toast.value = null
    }

    fun markMessagesAsRead() {
        val char = _currentCharacter.value ?: return
        val sessionId = _activeSessionId.value
        if (sessionId.isBlank()) return

        viewModelScope.launch {
            try {
                db.messageDao().markAsRead(sessionId, ownerId, char.id)
                db.sessionDao().updateUnreadCount(sessionId, ownerId, char.id, 0)
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        networkMonitor.stop()
    }
}
