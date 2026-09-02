package com.mistbell.tavern.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.*
import com.mistbell.tavern.android.data.network.NetworkMonitor
import com.mistbell.tavern.android.data.repository.ChatRepository
import com.mistbell.tavern.android.data.repository.ProviderRepository
import com.mistbell.tavern.android.data.repository.ThemePackRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import com.mistbell.tavern.android.data.theme.ThemeSupport
import com.mistbell.tavern.android.data.theme.ThemeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.database
    private val repo = ChatRepository(application)
    private val networkMonitor = NetworkMonitor(application)
    private val providerRepo = ProviderRepository(application)
    private val worldBookRepo = WorldBookRepository(application)
    private val themeRepo = ThemePackRepository(application)

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    // 语义（v16 性能修复）：已加载窗口 = 最新 N 条 + 用户上滚 prepend 的更旧消息
    val messages: StateFlow<List<Message>> = _messages

    // 窗口分页状态：还有更旧消息可加载 / 加载进行中（UI 据此决定是否触发 loadOlderMessages）
    private val _hasMoreOlder = MutableStateFlow(true)
    val hasMoreOlder: StateFlow<Boolean> = _hasMoreOlder.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    // 已 prepend 的更旧消息（按 createdAt 升序）：观察流只发最新窗口，prepend 结果在此累积，
    // 每次窗口发射时经 mergeMessageWindow 与窗口合并；会话切换/清空历史时重置
    private var prependedOlder: List<Message> = emptyList()

    // 最近一次观察发射的窗口（升序）：loadOlderMessages 返回后需与窗口重算合并
    private var currentWindow: List<Message> = emptyList()

    // 分页在途任务（修复1）：会话/角色切换时仅重置标志不够——在途 Job 返回后会把旧会话的
    // 更旧消息拼进新会话列表，或错误封锁新会话的 hasMoreOlder。存为字段以便切换时取消。
    private var loadOlderJob: Job? = null

    // 流式节流：距上次发射不足 STREAM_EMIT_INTERVAL_NS 的中间帧直接丢弃，
    // 降低高频 onPartial 对主线程与重组的压力（最终全文由落库消息展示）
    private var lastStreamEmitAt = 0L

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping

    // 流式生成：generationJob 可取消；streamingText 为累计的增量全文（null 表示未在流式输出中）
    private var generationJob: Job? = null
    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    // 用户主动停止生成（取消当前 LLM 流式请求）
    fun stopGeneration() {
        generationJob?.cancel()
    }

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

    // 群聊模式：沿用现有 session 观察链推导——活跃会话 id 变化（切会话/删会话回退）时重新
    // 观察会话实体，session.mode == "group" 才为 true；classic 会话与无会话态恒 false。
    // 轻量 Room Flow 订阅，无组合线程重活；@OptIn(ExperimentalCoroutinesApi) 已在类级声明。
    // 注意：声明必须位于 _activeSessionId 之后（Kotlin 属性按声明顺序初始化）
    val groupMode: StateFlow<Boolean> =
        _activeSessionId
            .flatMapLatest { sessionId ->
                if (sessionId.isBlank()) flowOf(null) else db.sessionDao().observeById(sessionId)
            }.map { session -> session?.mode == SESSION_MODE_GROUP }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    // Navigation events
    private val _navigationEvent = MutableStateFlow<String?>(null)
    val navigationEvent: StateFlow<String?> = _navigationEvent

    fun navigateTo(route: String) {
        _navigationEvent.value = route
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    // Provider/Model state
    val providers: StateFlow<List<ProviderConfig>> =
        providerRepo.observeProviders()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val activeModelId: StateFlow<String> =
        providerRepo.observeActiveModelId()
            .stateIn(viewModelScope, SharingStarted.Lazily, "")
    val activeProviderId: StateFlow<String> =
        providerRepo.observeActiveProviderId()
            .stateIn(viewModelScope, SharingStarted.Lazily, "")

    // World book state
    val worldBooks: StateFlow<List<WorldBook>> =
        worldBookRepo.observeWorldBooks()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val _activeWorldBookId = MutableStateFlow("")
    val activeWorldBookId: StateFlow<String> = _activeWorldBookId

    // 主题包状态：应用链为 会话 → 角色 → 全局（ThemeSupport 内逐层回落）
    // 双键驱动：会话 id + 角色 id 任一变化都重新解析（tokens / 背景图共用同一条解析链）
    private val sessionCharacterKey: Flow<Pair<String, String?>> =
        combine(
            _activeSessionId,
            _currentCharacter.map { it?.id }.distinctUntilChanged(),
        ) { sid, cid -> sid to cid }
            .distinctUntilChanged()

    val characterTokens: StateFlow<ThemeTokens?> =
        sessionCharacterKey
            .flatMapLatest { (sid, cid) ->
                themeRepo.observeResolvedPack(sid, cid)
                    .map { pack -> pack?.let { ThemeSupport.parseTokens(it.tokensJson) } }
                    // parseTokens 是 JSON 解析重活，切到 Default 线程，避免阻塞收集方（主线程）
                    .flowOn(Dispatchers.Default)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 背景图与 tokens 走同一条"会话→角色→全局"应用链（解析出命中的包实体再取背景文件）
    val characterBackgroundFile: StateFlow<java.io.File?> =
        sessionCharacterKey
            .flatMapLatest { (sid, cid) ->
                themeRepo.observeResolvedPack(sid, cid)
                    .map { pack -> pack?.let { themeRepo.backgroundFile(it) } }
                    // 背景文件解析含磁盘 IO，切到 Default 线程，避免阻塞收集方（主线程）
                    .flowOn(Dispatchers.Default)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val darkModeSetting: StateFlow<String> =
        db.settingsDao().observeValue("dark_mode")
            .map { it ?: "system" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    var ownerId = "local-user"
        private set

    private var currentCharacterId = ""
    private var isSessionExplicitlySet = false
    private var messageObserverJob: kotlinx.coroutines.Job? = null
    private var characterObserverJob: kotlinx.coroutines.Job? = null

    init {
        networkMonitor.start()
        // 不在 init 中调用 loadLocalState()，等待显式的 loadSession() 调用

        // 移除自动同步 - 安卓和Web数据完全隔离，本地优先
        // 用户如果需要同步可以手动触发
    }

    private fun loadLocalState(
        characterId: String? = null,
        sessionId: String? = null,
    ) {
        val charId = characterId ?: currentCharacterId

        // Observe characters
        viewModelScope.launch {
            repo.observeCharacters().collect { chars ->
                _characters.value = chars
                if (_currentCharacter.value == null && chars.isNotEmpty()) {
                    val target = chars.find { it.id == charId } ?: chars.first()
                    _currentCharacter.value = target
                    currentCharacterId = target.id
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
            if (!isSessionExplicitlySet) {
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
        currentCharacterId = character.id
        _messages.value = emptyList()
        // 切角色即切换窗口上下文：先重置分页状态（新角色可能无会话，观察不会重启）
        resetMessageWindowState()
        // 修复6：与 loadSession 一致，进入时取消旧角色的 characterObserverJob——否则旧观察者
        // 存活期间会把 _currentCharacter 拉回旧角色，后续消息被写错 character_id
        characterObserverJob?.cancel()
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
    private fun startObservingMessages(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        messageObserverJob?.cancel()
        // 观察重启即视为切换会话：窗口分页状态一并重置，避免残留上一会话的 prepend 历史与"到头"标记
        resetMessageWindowState()
        messageObserverJob =
            viewModelScope.launch {
                if (sessionId.isNotBlank() && characterId.isNotBlank()) {
                    // 一次性标志：首次收到非空列表后自动执行已读，UI 不再需要手动调用 markMessagesAsRead
                    var autoReadDone = false
                    repo.observeMessages(ownerId, characterId, sessionId, MESSAGE_PAGE_SIZE).collect { msgs ->
                        currentWindow = msgs
                        _messages.value = mergeMessageWindow(prependedOlder, msgs)
                        if (!autoReadDone && msgs.isNotEmpty()) {
                            autoReadDone = true
                            autoMarkMessagesAsReadIfNeeded(sessionId, characterId)
                        }
                    }
                }
            }
    }

    // 重置窗口分页状态：会话切换或历史被清空/截断后调用，下次观察发射自然重算窗口
    private fun resetMessageWindowState() {
        // 修复1：先取消在途分页任务——否则旧会话的 loadOlderMessages 返回后仍会写
        // prependedOlder/_messages/_hasMoreOlder，把旧会话结果串染进新会话
        loadOlderJob?.cancel()
        loadOlderJob = null
        prependedOlder = emptyList()
        currentWindow = emptyList()
        _hasMoreOlder.value = true
        _isLoadingOlder.value = false
    }

    // 上滚加载更旧一页：防重入（加载中/已到头直接返回）。游标为复合游标（修复3）：
    // 取当前合并列表最旧一条的 createdAt + id，避免并列时间戳被 LIMIT 切开导致永久丢失。
    // 结果为空或不足一页时置 hasMoreOlder=false，避免到底后继续空查询。
    fun loadOlderMessages() {
        if (_isLoadingOlder.value || !_hasMoreOlder.value) return
        // 前置守卫合并为单次判定（角色未就绪/会话无效/列表为空都无法确定游标）：
        // 提前 return 收敛到 2 个以内，满足 detekt ReturnCount，语义与逐个判空等价
        val char = _currentCharacter.value
        val sessionId = _activeSessionId.value
        val oldest = _messages.value.firstOrNull()
        if (char == null || sessionId.isBlank() || oldest == null) return
        // 复合游标：createdAt 相同时用 id（UUID 字符串，字典序稳定）决胜，与 getOlderBySession 的
        // (created_at, id) 复合比较语义一致
        val beforeCreatedAt = oldest.createdAt
        val beforeId = oldest.id
        val characterId = char.id

        // 修复1：Job 存字段，会话切换经 resetMessageWindowState 可取消；挂起返回后再校验
        // 会话/角色未变，防止切换瞬间已过挂起点、旧结果污染新会话状态
        loadOlderJob =
            viewModelScope.launch {
                _isLoadingOlder.value = true
                try {
                    val older =
                        repo.loadOlderMessages(
                            sessionId, ownerId, characterId, beforeCreatedAt, beforeId, MESSAGE_PAGE_SIZE,
                        )
                    // 挂起点之后二次校验：会话/角色已切换则丢弃结果（Job 取消的兜底防线）
                    if (_activeSessionId.value != sessionId || _currentCharacter.value?.id != characterId) {
                        return@launch
                    }
                    if (older.isEmpty()) {
                        _hasMoreOlder.value = false
                    } else {
                        prependedOlder = (prependedOlder + older).sortedBy { it.createdAt }.distinctBy { it.id }
                        _messages.value = mergeMessageWindow(prependedOlder, currentWindow)
                        if (older.size < MESSAGE_PAGE_SIZE) {
                            _hasMoreOlder.value = false
                        }
                    }
                } catch (e: CancellationException) {
                    // 协程取消不是错误：直接上抛
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "加载更旧消息失败", e)
                } finally {
                    _isLoadingOlder.value = false
                }
            }
    }

    // 自动已读：先查会话未读数，>0 才写库（markAsRead 已限定 is_read = 0，
    // 双重保证无未读时不产生任何行更新，避免无谓的 Room invalidation 整表重发）
    private fun autoMarkMessagesAsReadIfNeeded(
        sessionId: String,
        characterId: String,
    ) {
        viewModelScope.launch {
            try {
                val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return@launch
                if (session.unreadCount > 0) {
                    // 契约 1：markAsRead 会话级化（删除 character_id 过滤与参数），按会话+属主标记已读
                    db.messageDao().markAsRead(sessionId, ownerId)
                    db.sessionDao().updateUnreadCount(sessionId, ownerId, characterId, 0)
                }
            } catch (e: Exception) {
                // 静默失败：已读标记不影响核心聊天功能
            }
        }
    }

    // 公共方法：加载指定会话
    fun loadSession(
        sessionId: String,
        characterId: String,
    ) {
        android.util.Log.d("ChatViewModel", "loadSession called: sessionId=$sessionId, characterId=$characterId")
        android.util.Log.d("ChatViewModel", "Before set: _activeSessionId=${_activeSessionId.value}")

        // 取消之前的观察
        messageObserverJob?.cancel()
        characterObserverJob?.cancel()

        // 切换会话：重置窗口分页状态（prepend 历史/加载标记/到头标记）
        resetMessageWindowState()

        _activeSessionId.value = sessionId
        currentCharacterId = characterId
        isSessionExplicitlySet = true
        android.util.Log.d("ChatViewModel", "After set: _activeSessionId=${_activeSessionId.value}")

        characterObserverJob =
            viewModelScope.launch {
                combine(
                    repo.observeCharacters(),
                    db.sessionDao().observeById(sessionId),
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
                    _participantCharacters.value =
                        participantIds
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
        android.util.Log.d("ChatViewModel", "  isSessionExplicitlySet=$isSessionExplicitlySet")

        // 群聊模式：组装 GroupChatContext（参与者 id→名字 + @提及 解析出的 targetSpeakerId）；
        // 经典模式传 null（提示词路径零变化）。落库的用户消息内容保留原文——这里不改 content，
        // 仅把提及目标作为 groupContext 传入，剥前缀与回复归属由仓库层处理
        val groupContext =
            if (groupMode.value) {
                GroupChatContext(
                    speakerNames = _participantCharacters.value.associate { it.id to it.name },
                    targetSpeakerId = resolveMentionTarget(content, _participantCharacters.value),
                )
            } else {
                null
            }

        // 存入 generationJob 以支持"停止生成"；onPartial 经时间窗节流后把累计全文推给 UI 流式渲染
        launchGeneration("发送失败") {
            repo.sendMessage(
                ownerId = ownerId,
                characterId = char.id,
                sessionId = _activeSessionId.value,
                message = content,
                onPartial = ::emitStreamingTextThrottled,
                groupContext = groupContext,
            )
            android.util.Log.d("ChatViewModel", "Message sent successfully")
        }
    }

    fun undoLastMessage() {
        val char = _currentCharacter.value ?: return
        viewModelScope.launch {
            try {
                repo.undoLastMessage(ownerId, char.id, _activeSessionId.value)
                // 修复2：这里【不】清空 prependedOlder、【不】重置分页状态——undoLastMessage 只删
                // 窗口内最后一条，已 prepend 的更旧历史并未删除，清空会让它们从 UI 消失且
                // hasMoreOlder=false 永久锁死上滚加载。观察窗口重发后 mergeMessageWindow 的
                // 复合边界过滤 + id 去重会自然处理窗口与 prepend 缓存的重叠。
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
                // 修复2：同 undoLastMessage——deleteAfter 只删目标消息之后的窗口内近期消息，
                // 已 prepend 的更旧历史不受影响；保留分页状态，上滚仍能继续找回更早历史
            } catch (e: Exception) {
                _error.value = "回退失败: ${e.message}"
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        val char = _currentCharacter.value ?: return
        // 存入 generationJob 以支持"停止生成"；onPartial 经时间窗节流后把累计全文推给 UI 流式渲染
        launchGeneration("重新生成失败") {
            repo.regenerateMessage(
                ownerId,
                char.id,
                _activeSessionId.value,
                messageId,
                onPartial = ::emitStreamingTextThrottled,
            )
            // 修复2：regenerate 的 deleteAfter 同样只删窗口内近期消息，prepended 的
            // 更旧历史仍有效——保留分页状态，不清空 prependedOlder、不动 hasMoreOlder
        }
    }

    // 统一的生成状态机包装：isTyping/streamingText 状态、停止生成支持（generationJob 可取消）、
    // 错误上报收敛到一处，sendMessage/regenerateMessage/continueGroupChat 复用，避免复制
    private fun launchGeneration(
        errorPrefix: String,
        block: suspend () -> Unit,
    ) {
        generationJob =
            viewModelScope.launch {
                _isTyping.value = true
                lastStreamEmitAt = 0L
                try {
                    block()
                } catch (e: CancellationException) {
                    // 用户主动停止不是错误：直接上抛，不写 _error
                    throw e
                } catch (e: Exception) {
                    _error.value = "$errorPrefix: ${e.message}"
                } finally {
                    _isTyping.value = false
                    _streamingText.value = null
                }
            }
    }

    // 群聊"让TA继续"：不插入用户消息，由仓库端在提示词中注入系统级推动
    // （"请让最合适的下一位角色自然接话"）。guard：仅群聊模式且当前未在生成中；
    // isTyping/streaming 状态机复用 launchGeneration，与 sendMessage 行为一致
    fun continueGroupChat() {
        if (!groupMode.value || _isTyping.value) return
        val char = _currentCharacter.value ?: return
        launchGeneration("继续失败") {
            repo.continueGroupChat(
                ownerId = ownerId,
                characterId = char.id,
                sessionId = _activeSessionId.value,
                onPartial = ::emitStreamingTextThrottled,
            )
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

    fun swipeMessage(
        messageId: String,
        direction: String,
    ) {
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
                    // 删除会话后回到新会话：分页状态一并重置
                    resetMessageWindowState()
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
        val clipboard =
            getApplication<Application>().getSystemService(
                android.content.Context.CLIPBOARD_SERVICE,
            ) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("message", content)
        clipboard.setPrimaryClip(clip)
        _toast.value = "已复制"
    }

    fun switchModel(
        providerId: String,
        modelId: String,
    ) {
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
                // 历史被清空：分页状态一并重置，避免残留已删消息与错误的"到头"标记
                resetMessageWindowState()
                _toast.value = "对话已清除"
            } catch (e: Exception) {
                _error.value = "清除失败: ${e.message}"
            }
        }
    }

    fun clearToast() {
        _toast.value = null
    }

    // 保留兼容入口（UI 已改为首次消息发射后自动已读，不再主动调用）：
    // 复用带未读数检查的内部逻辑，未读为 0 时不产生任何写库
    fun markMessagesAsRead() {
        val char = _currentCharacter.value ?: return
        val sessionId = _activeSessionId.value
        if (sessionId.isBlank()) return
        autoMarkMessagesAsReadIfNeeded(sessionId, char.id)
    }

    // 流式中间帧时间窗节流：距上次发射不足间隔则丢弃本帧，降低高频 onPartial 的主线程压力
    private fun emitStreamingTextThrottled(text: String) {
        val now = System.nanoTime()
        if (shouldEmitStreamText(lastStreamEmitAt, now)) {
            lastStreamEmitAt = now
            _streamingText.value = text
        }
    }

    companion object {
        // 每页加载的消息条数：与 ChatRepository.DEFAULT_MESSAGE_WINDOW 保持一致
        private const val MESSAGE_PAGE_SIZE = 200
    }

    override fun onCleared() {
        super.onCleared()
        networkMonitor.stop()
    }
}

// 流式发射节流间隔：80ms 时间窗（纳秒）内的中间帧丢弃，最终全文由落库消息展示
internal const val STREAM_EMIT_INTERVAL_NS = 80_000_000L

// 时间窗判定纯函数（便于单测）：nowNanos - lastEmitAtNanos 达到间隔才允许发射；
// lastEmitAtNanos = 0（尚未发射过）时必然放行——单测以极小 now 值覆盖该分支
internal fun shouldEmitStreamText(
    lastEmitAtNanos: Long,
    nowNanos: Long,
): Boolean = lastEmitAtNanos == 0L || nowNanos - lastEmitAtNanos >= STREAM_EMIT_INTERVAL_NS

// 窗口合并纯函数（便于单测）：把已 prepend 的更旧消息接在当前观察窗口前面。
// 修复3：以窗口首条为复合边界（createdAt + id），保留 (createdAt < 边界) 或
// (createdAt == 边界 且 id < 边界 id) 的 prepend 消息——与 getOlderBySession 的
// 复合游标取数语义一致，导入会话保留原时间戳时同刻消息不再被合并侧误丢；
// 与窗口 id 重复的 prepend 消息兜底丢弃，避免重复渲染。
internal fun mergeMessageWindow(
    prepended: List<Message>,
    window: List<Message>,
): List<Message> {
    if (window.isEmpty()) return prepended
    val boundary = window.first()
    val windowIds = window.mapTo(HashSet()) { it.id }
    val prefix =
        prepended.filter {
            (it.createdAt < boundary.createdAt || (it.createdAt == boundary.createdAt && it.id < boundary.id)) &&
                it.id !in windowIds
        }
    return prefix + window
}

// @提及解析纯函数（便于单测）：用户消息以 "@名字" 开头且名字（trim 后）前缀匹配某参与者时，
// 返回该参与者 id 作为 targetSpeakerId；无匹配返回 null。
// 名字匹配语义对齐 util/GroupSpeaker.kt 的 parseGroupSpeaker（契约 6）：
// - 候选名字先 trim 再参与匹配（名字带首尾空白的角色卡同样可被 @提及）；
// - "@名字" 之后允许 0..n 空白后跟冒号（半角/全角都认，与契约 5 的 parseGroupSpeaker 一致）；
// - 兼容 @提及 的自然书写习惯：紧跟空白分隔符（"@A 你好"）同样算命中；
// - "@名字" 后无任何分隔符（如 "@A你好"）不命中，排除 "@AliceBot hi" 对 "Alice" 的误匹配；
// - 多候选前缀重叠时最长名字优先（detekt ReturnCount：收敛为单出口表达式）
internal fun resolveMentionTarget(
    content: String,
    participants: List<Character>,
): String? {
    if (!content.startsWith("@")) return null
    val candidates =
        participants.mapNotNull { participant ->
            // trim 语义对齐 parseGroupSpeaker：候选名先 trim，空名不参与匹配
            val name = participant.name.trim()
            if (name.isEmpty() || !content.startsWith("@$name")) null else participant to name
        }
    val (target, name) =
        candidates.maxByOrNull { (_, name) -> name.length } ?: return null
    // "@名字" 之后的剩余串：紧跟空白（提及习惯分隔）或 0..n 空白后跟冒号（对齐 parseGroupSpeaker）才算命中
    val rest = content.substring(name.length + 1)
    val afterBlank = rest.trimStart().firstOrNull()
    val matched =
        rest.firstOrNull()?.isWhitespace() == true ||
            afterBlank == ':' ||
            afterBlank == '：'
    return if (matched) target.id else null
}
