package com.mistbell.tavern.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiConfigJson
import com.mistbell.tavern.android.data.api.model.*
import com.mistbell.tavern.android.data.network.NetworkMonitor
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.prompt.PromptBuilder.PromptTrace
import com.mistbell.tavern.android.data.repository.ChatRepository
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.data.repository.QuickReplyRepository
import com.mistbell.tavern.android.data.repository.ThemePackRepository
import com.mistbell.tavern.android.data.theme.ThemeSupport
import com.mistbell.tavern.android.data.theme.ThemeTokens
import com.mistbell.tavern.android.extension.SlashCommandContext
import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import com.mistbell.tavern.android.extension.slash.SlashCommandDispatcher
import com.mistbell.tavern.android.extension.slash.SlashCommandParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.container.database
    private val repo = ChatRepository(application)
    private val networkMonitor = NetworkMonitor(application)
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

    // 当前会话的主角色 id（loadSession 时写入）：提示词预览等需要按会话重建的派生数据用它，
    // 避免依赖 _currentCharacter 的异步加载（首次进入时它可能还没就绪）
    private val _activeCharacterId = MutableStateFlow("")
    val activeCharacterId: StateFlow<String> = _activeCharacterId.asStateFlow()

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

    // ---- 快捷回复（Quick Reply）：全局自定义 + 启用规则包聚合 ----

    private val quickReplyRepo = QuickReplyRepository()

    val quickReplies: StateFlow<List<QuickReplyRepository.ActiveQuickReply>> =
        quickReplyRepo.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** insert 动作的输入框填入请求：一次性（UI 消费后置 null） */
    private val _pendingInsertText = MutableStateFlow<String?>(null)
    val pendingInsertText: StateFlow<String?> = _pendingInsertText

    fun consumePendingInsert() {
        _pendingInsertText.value = null
    }

    /**
     * 执行快捷回复：send 走正常发送链路（斜杠拦截自然生效）；insert 填入输入框；
     * command 分发斜杠命令（payload 非 / 开头按非法配置提示，不执行）。
     */
    fun executeQuickReply(qr: QuickReplyRepository.ActiveQuickReply) {
        val char = _currentCharacter.value ?: return
        when (qr.action) {
            "send" -> sendMessage(qr.payload)
            "insert" -> _pendingInsertText.value = qr.payload
            "command" -> {
                if (!SlashCommandParser.isSlashCommand(qr.payload)) {
                    _toast.value = "快捷回复「${qr.label}」的 command 载荷须以 / 开头"
                    return
                }
                viewModelScope.launch {
                    _toast.value =
                        SlashCommandDispatcher.dispatch(
                            qr.payload,
                            SlashCommandContext(ownerId, char.id, _activeSessionId.value),
                        )
                }
            }
            else -> _toast.value = "快捷回复「${qr.label}」动作非法：${qr.action}"
        }
    }

    // ---- HTML 界面（楼层 WebView 渲染，沙箱 + 桥） ----

    /** 全局开关（设置 → 高级设置 → 实验性功能；默认关） */
    val htmlUiEnabled: StateFlow<Boolean> =
        db.settingsDao().observeValue("html_ui_enabled")
            .map { it == "1" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 当前角色卡的 tavern_helper.scripts（CharacterData.extensions 透传而来；无则为空表） */
    val htmlScripts: StateFlow<List<com.mistbell.tavern.android.ui.chat.htmlui.HtmlScript>> =
        _currentCharacter
            .map { char -> parseTavernHelperScripts(char?.data?.extensions) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前角色卡的 regex_scripts（显示时作用于 AI 消息内容，不改存储） */
    val cardRegexScripts: StateFlow<List<com.mistbell.tavern.android.data.regex.CardRegexEngine.CardRegexScript>> =
        _currentCharacter
            .map { char ->
                com.mistbell.tavern.android.data.regex.CardRegexEngine.parse(char?.data?.extensions)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** MVU 变量版本（当前会话的 VariablesUpdated 事件计数；HtmlBubble 据此推送变量更新） */
    val mvuVariablesVersion: StateFlow<Long> =
        TavernEventBus.events
            .filterIsInstance<TavernEvent.VariablesUpdated>()
            .map { it.takeIf { ev -> ev.sessionId == _activeSessionId.value }?.version }
            .filterNotNull()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** HTML 界面桥宿主：数据与动作的受控出口（变量会话级隔离） */
    val htmlBridgeHost: com.mistbell.tavern.android.ui.chat.htmlui.HtmlBridgeHost =
        object : com.mistbell.tavern.android.ui.chat.htmlui.HtmlBridgeHost {
            override fun getVar(key: String): String =
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    db.settingsDao().getValue(huiVarKey(key)) ?: ""
                }

            override fun setVar(
                key: String,
                value: String,
            ) {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    db.settingsDao().upsert(
                        com.mistbell.tavern.android.data.local.entity.SettingsEntity(huiVarKey(key), value),
                    )
                }
            }

            override fun getChatMessagesJson(limit: Int): String {
                val array = org.json.JSONArray()
                _messages.value.takeLast(limit).forEach { msg ->
                    array.put(
                        org.json.JSONObject()
                            .put("role", msg.role)
                            .put("content", msg.content),
                    )
                }
                return array.toString()
            }

            override fun sendMessage(text: String) = this@ChatViewModel.sendMessage(text)

            override fun emitEvent(
                type: String,
                payload: String,
            ) {
                android.util.Log.d("HtmlUi", "eventEmit($type): ${payload.take(EVENT_PAYLOAD_LOG_LIMIT)}")
            }

            override fun getMvuVariablesJson(): String =
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    db.settingsDao().getValue(
                        com.mistbell.tavern.android.extension.builtin.MvuExtension.statKey(_activeSessionId.value),
                    ) ?: "{}"
                }

            override fun insertMvuVariablesJson(json: String) {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    val mvu = com.mistbell.tavern.android.extension.builtin.MvuExtension
                    val statKey = mvu.statKey(_activeSessionId.value)
                    val verKey = mvu.versionKey(_activeSessionId.value)
                    val current =
                        db.settingsDao().getValue(statKey)
                            ?.let {
                                runCatching {
                                    kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject
                                }.getOrNull()
                            } ?: kotlinx.serialization.json.JsonObject(emptyMap())
                    val patch =
                        runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                        }.getOrNull() ?: return@runBlocking
                    // insertOrAssign 语义：顶层键浅合并赋值
                    val merged =
                        kotlinx.serialization.json.JsonObject(
                            current.toMutableMap().apply { putAll(patch) },
                        )
                    db.settingsDao().upsert(
                        com.mistbell.tavern.android.data.local.entity.SettingsEntity(statKey, merged.toString()),
                    )
                    val version = (db.settingsDao().getValue(verKey)?.toLongOrNull() ?: 0L) + 1
                    db.settingsDao().upsert(
                        com.mistbell.tavern.android.data.local.entity.SettingsEntity(verKey, version.toString()),
                    )
                }
            }

            override fun triggerSlash(command: String) {
                viewModelScope.launch {
                    _toast.value =
                        SlashCommandDispatcher.dispatch(
                            command,
                            SlashCommandContext(ownerId, _currentCharacter.value?.id.orEmpty(), _activeSessionId.value),
                        )
                }
            }

            private fun huiVarKey(key: String): String = "ext.hui.${_activeSessionId.value}.${key.trim()}"
        }

    // ---- HTML 界面展开状态（按会话持久化） ----

    /** 当前会话中处于展开状态的 HTML 消息 id 集合；默认折叠，用户操作后按会话记忆 */
    private val _htmlExpandedIds = MutableStateFlow<Set<String>>(emptySet())
    val htmlExpandedIds: StateFlow<Set<String>> = _htmlExpandedIds.asStateFlow()

    fun setHtmlExpanded(
        messageId: String,
        expanded: Boolean,
    ) {
        val sessionId = _activeSessionId.value
        if (sessionId.isBlank()) return
        val next =
            _htmlExpandedIds.value.toMutableSet().apply {
                if (expanded) add(messageId) else remove(messageId)
            }
        _htmlExpandedIds.value = next
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(
                    huiExpandKey(sessionId),
                    com.mistbell.tavern.android.ui.chat.htmlui.HtmlExpandCodec.encode(next),
                ),
            )
        }
    }

    /** 会话切换时重载该会话的展开状态集合（无记录则为空集 = 全部默认折叠） */
    private suspend fun reloadHtmlExpanded(sessionId: String) {
        _htmlExpandedIds.value =
            if (sessionId.isBlank()) {
                emptySet()
            } else {
                com.mistbell.tavern.android.ui.chat.htmlui.HtmlExpandCodec
                    .decode(db.settingsDao().getValue(huiExpandKey(sessionId)))
            }
    }

    private fun huiExpandKey(sessionId: String): String = "ext.htmlui.expanded.$sessionId"

    /** 从角色卡 extensions 提取 tavern_helper.scripts（坏卡/无字段按空表处理）。 */
    private fun parseTavernHelperScripts(
        extensions: kotlinx.serialization.json.JsonObject?,
    ): List<com.mistbell.tavern.android.ui.chat.htmlui.HtmlScript> {
        val scripts =
            (extensions?.get("tavern_helper") as? kotlinx.serialization.json.JsonObject)
                ?.get("scripts") as? kotlinx.serialization.json.JsonArray
        if (scripts == null) return emptyList()
        return runCatching {
            scripts.mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                com.mistbell.tavern.android.ui.chat.htmlui.HtmlScript(
                    name = name,
                    content = content,
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }
        }.onFailure {
            android.util.Log.w("HtmlUi", "tavern_helper.scripts 解析失败：${it.message}")
        }.getOrDefault(emptyList())
    }

    // Navigation events
    private val _navigationEvent = MutableStateFlow<String?>(null)
    val navigationEvent: StateFlow<String?> = _navigationEvent

    fun navigateTo(route: String) {
        _navigationEvent.value = route
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    // 开场白选项（已过宏渲染，与消息正文同管线）：主角色 first_mes + 备用开场白
    // （alternate_greetings）。聊天界面在「会话仅剩开场白一条消息」时提供切换入口
    val greetingOptions: StateFlow<List<String>> =
        _currentCharacter.map { character ->
            if (character == null) {
                emptyList()
            } else {
                buildGreetingOptions(character.firstMes, character.data?.alternateGreetings.orEmpty())
            }
        }.map { raws ->
            if (raws.isEmpty()) {
                emptyList()
            } else {
                // 与建会话同一套宏上下文：用户名与人设取全局设置（键常量收敛在 ChatSettingsResolver）
                val character = _currentCharacter.value
                val mctx =
                    com.mistbell.tavern.android.util.MacroContext(
                        char = character?.name ?: "",
                        user = ChatSettingsResolver.userName(db.settingsDao().getValue(ChatSettingsResolver.KEY_USER_NAME)),
                        description = character?.description ?: "",
                        personality = character?.personality ?: "",
                        scenario = character?.scenario ?: "",
                        persona = ChatSettingsResolver.userPersona(db.settingsDao().getValue(ChatSettingsResolver.KEY_USER_PERSONA)),
                    )
                raws.map { com.mistbell.tavern.android.util.MacroEngine.render(it, mctx) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 更换开场白（聊天界面抽屉）：宏渲染后原位替换首条消息内容。
     * UI 门禁：仅当会话只剩开场白这一条消息时展示入口——已有对话历史后开场白属于
     * 既成上下文，不允许改写。
     */
    fun swapGreeting(
        messageId: String,
        index: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rendered = greetingOptions.value.getOrNull(index) ?: return@launch
                val sessionId = _activeSessionId.value
                if (sessionId.isBlank()) return@launch
                val entity =
                    db.messageDao().getById(messageId, sessionId, ownerId) ?: return@launch
                if (entity.content == rendered) return@launch
                db.messageDao().upsert(entity.copy(content = rendered))
            } catch (e: Exception) {
                _toast.value = "切换开场白失败: ${e.message}"
            }
        }
    }

    // ---- 提示词预览（聊天页「查看提示词」） ----

    // 预览中的提示词分段（null = 未加载）。由 [loadPromptTrace] 填充，抽屉展示时读取
    private val _promptTrace = MutableStateFlow<PromptTrace?>(null)
    val promptTrace: StateFlow<PromptTrace?> = _promptTrace.asStateFlow()

    // 本次请求实际生效的采样/请求参数（与真实请求同源：同样经 SettingsRepository.getLlmConfig 解析）
    private val _promptRequestParams = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val promptRequestParams: StateFlow<List<Pair<String, String>>> = _promptRequestParams.asStateFlow()

    private val _isLoadingPromptTrace = MutableStateFlow(false)
    val isLoadingPromptTrace: StateFlow<Boolean> = _isLoadingPromptTrace.asStateFlow()

    private val _promptTraceError = MutableStateFlow<String?>(null)
    val promptTraceError: StateFlow<String?> = _promptTraceError.asStateFlow()

    /**
     * 装配当前会话的完整提示词（与真实请求同一条代码路径）。
     *
     * 用会话里**最后一条用户消息**作为"当前消息"来装配——它是用户下一次发送时的等价输入
     * （真实请求发生在该消息落库之前，PromptBuilder 会按 currentMessageId 过滤掉已落库的同一条）。
     */
    fun loadPromptTrace() {
        if (_isLoadingPromptTrace.value) return
        val sessionId = _activeSessionId.value
        if (sessionId.isBlank()) {
            _promptTraceError.value = "会话未就绪"
            return
        }
        val characterId = _activeCharacterId.value
        if (characterId.isBlank()) {
            _promptTraceError.value = "角色未就绪"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingPromptTrace.value = true
            _promptTraceError.value = null
            try {
                val lastUserMessage =
                    db.messageDao().getBySession(sessionId, ownerId).first()
                        .lastOrNull { it.role == "user" }
                val groupContext = groupContextFor(sessionId, characterId)
                val requestConfig = repo.effectiveLlmConfig(sessionId)
                _promptTrace.value =
                    PromptBuilder.buildPromptTrace(
                        db = db,
                        request =
                            PromptBuilder.PromptRequest(
                                ownerId = ownerId,
                                characterId = characterId,
                                sessionId = sessionId,
                                userMessage = lastUserMessage?.content ?: "",
                                // 已落库的最后一条用户消息需排除，否则预览里会重复出现
                                currentMessageId = lastUserMessage?.id,
                                groupContext = groupContext,
                                contextTokenLimitOverride = requestConfig.contextTokenLimit,
                            ),
                    )
                _promptRequestParams.value = describeRequestParams()
            } catch (e: Exception) {
                _promptTraceError.value = "提示词装配失败: ${e.message}"
            } finally {
                _isLoadingPromptTrace.value = false
            }
        }
    }

    /**
     * 本次请求实际生效的参数（与真实请求同源：`collectReply` 用的就是 [SettingsRepository.getLlmConfig]）。
     *
     * 密钥与 baseUrl 只显示"是否已配置"而不显示内容（隐私）；采样参数按"生效值"呈现，
     * null 字段标注"未设置（不出现在请求体）"——排查"两次回复差别大"时需要看这一栏。
     */
    private suspend fun describeRequestParams(): List<Pair<String, String>> {
        // 与会话级覆盖后的真实请求同源（ChatRepository.effectiveLlmConfig），否则诊断栏
        // 显示全局默认配置，与会话实际使用的模型/端点不一致
        val config = repo.effectiveLlmConfig(_activeSessionId.value)

        fun num(value: Double?): String = value?.toString() ?: "未设置（不发送）"
        val protectedFields = ApiConfigJson.protectedFieldsForType(config.type)
        val merge = ApiConfigJson.mergeRequest(buildJsonObject { }, config.request, protectedFields)
        return buildList {
            add("模型" to config.model.ifBlank { "未配置" })
            add("接口类型" to config.type)
            add("接口" to ApiConfigJson.redactUrl(config.baseUrl).ifBlank { "未配置" })
            add("密钥" to if (config.apiKey.isBlank()) "未配置" else "已配置（${config.apiKey.length} 字符）")
            add("流式输出" to if (config.streamingEnabled) "开" else "关")
            add("temperature" to num(config.temperature))
            add("max_tokens" to "${config.maxTokens}（模型输出预算）")
            add("top_p" to num(config.topP))
            add("top_k" to num(config.topK?.toDouble()))
            add("frequency_penalty" to num(config.frequencyPenalty))
            add("思考模式" to if (config.disableThinking) "已关闭" else "跟随模型默认（开启）")
            add("超时/重试" to "${config.timeoutSeconds}s / ${config.retries} 次")
            add("contextTokenLimit" to "${config.contextTokenLimit}（本地 prompt 输入预算，与 max_tokens 不同）")
            // 递归展开扩展字段的实际值（thinking.type / reasoning_effort 等嵌套字段也逐叶显示）
            add("request 扩展字段" to ApiConfigJson.flattenRequest(config.request).joinToString("；").ifBlank { "无" })
            add("request 已应用" to merge.applied.joinToString("；").ifBlank { "无" })
            add("request 被忽略" to merge.ignored.joinToString("；").ifBlank { "无" })
            add("受保护字段（${config.type}）" to protectedFields.toSortedSet().joinToString())
            // 与本次预览装配的提示词同源：消息条数 + 估算输入 token + 召回诊断
            _promptTrace.value?.let { trace ->
                add("消息数量" to "${trace.segments.size} 条")
                add("估算输入 token" to "约 ${trace.totalEstimatedTokens}")
                trace.recallDiagnostics?.let { add("记忆召回" to it.summaryLine()) }
            }
        }
    }

    /** 群聊会话才需要说话方上下文；classic 返回 null（与真实请求一致） */
    private suspend fun groupContextFor(
        sessionId: String,
        characterId: String,
    ): GroupChatContext? {
        val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return null
        if (session.mode != SESSION_MODE_GROUP) return null
        val names =
            session
                .participantCharacterIds()
                .mapNotNull { id -> db.characterDao().getById(id)?.let { id to it.name } }
                .toMap()
        return GroupChatContext(speakerNames = names)
    }

    fun clearPromptTrace() {
        _promptTrace.value = null
        _promptTraceError.value = null
        _promptRequestParams.value = emptyList()
    }

    /** 复制提示词全文到剪贴板（按 role + 来源分段，便于贴到别处分析） */
    fun copyPromptTraceText() {
        val trace = _promptTrace.value ?: return
        val text =
            buildString {
                trace.segments.forEachIndexed { index, segment ->
                    if (index > 0) appendLine()
                    appendLine("─── #${index + 1} [${segment.message.role}] ${segment.source} ───")
                    appendLine(segment.message.content)
                }
                appendLine()
                trace.recallDiagnostics?.let { appendLine("记忆召回：${it.summaryLine()}") }
                appendLine("合计约 ${trace.totalEstimatedTokens} tokens（${trace.segments.size} 条消息）")
            }
        copyMessage(text)
    }

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

        // HTML 界面展开状态跟随会话切换重载（按会话隔离持久化）
        viewModelScope.launch {
            _activeSessionId.collect { reloadHtmlExpanded(it) }
        }

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
        // 切角色即离开当前会话：先抽掉不足一批的记忆抽取尾巴
        repo.flushPendingMemoryExtractions()
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
                    TavernEventBus.emit(TavernEvent.ChatChanged(ownerId, characterId, sessionId))
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
        // 进入新会话前抽掉上一会话不足一批的记忆抽取尾巴（首次进入时缓冲为空，天然 no-op）
        repo.flushPendingMemoryExtractions()

        // 取消之前的观察
        messageObserverJob?.cancel()
        characterObserverJob?.cancel()

        // 切换会话：重置窗口分页状态（prepend 历史/加载标记/到头标记）
        resetMessageWindowState()

        _activeSessionId.value = sessionId
        currentCharacterId = characterId
        _activeCharacterId.value = characterId
        // 切换会话即作废上一份提示词预览（内容属于旧会话）
        _promptTrace.value = null
        _promptTraceError.value = null
        isSessionExplicitlySet = true
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

        // 斜杠命令（对标酒馆 STscript 输入）：不进聊天链路，分发到注册表执行，
        // 结果走 toast 展示，不落库为消息
        if (SlashCommandParser.isSlashCommand(content)) {
            viewModelScope.launch {
                _toast.value =
                    SlashCommandDispatcher.dispatch(
                        content,
                        SlashCommandContext(ownerId, char.id, _activeSessionId.value),
                    )
            }
        } else {
            sendChatMessage(content, char.id)
        }
    }

    private fun sendChatMessage(
        content: String,
        characterId: String,
    ) {
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
                characterId = characterId,
                sessionId = _activeSessionId.value,
                message = content,
                onPartial = ::emitStreamingTextThrottled,
                groupContext = groupContext,
            )
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
        launchGeneration("继续失败") {
            repo.continueMessage(ownerId, char.id, _activeSessionId.value, onPartial = ::emitStreamingTextThrottled)
        }
    }

    fun swipeMessage(
        messageId: String,
        direction: String,
    ) {
        val char = _currentCharacter.value ?: return
        launchGeneration("切换失败") {
            repo.swipeMessage(
                ownerId,
                char.id,
                _activeSessionId.value,
                messageId,
                direction,
                onPartial = ::emitStreamingTextThrottled,
            )
        }
    }

    fun newChat() {
        val char = _currentCharacter.value ?: return
        // 开新会话即离开当前会话：先抽掉不足一批的记忆抽取尾巴
        repo.flushPendingMemoryExtractions()
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
        // 切会话：先抽掉当前会话不足一批的记忆抽取尾巴
        repo.flushPendingMemoryExtractions()
        _activeSessionId.value = sessionId
        _messages.value = emptyList()
        startObservingMessages(ownerId, char.id, sessionId)
    }

    fun deleteSession(sessionId: String) {
        val char = _currentCharacter.value ?: return
        // 源消息即将删除：丢弃该会话的抽取缓冲（抽出来也只会留下悬空的消息引用）
        repo.discardPendingMemoryExtractions(sessionId)
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

    fun clearChat() {
        val char = _currentCharacter.value ?: return
        val sessionId = _activeSessionId.value
        // 历史即将清空：丢弃该会话的抽取缓冲（理由同 deleteSession）
        repo.discardPendingMemoryExtractions(sessionId)
        viewModelScope.launch {
            try {
                repo.clearConversation(ownerId, char.id, sessionId)
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

        /** HTML 桥 eventEmit 日志的载荷截断长度 */
        private const val EVENT_PAYLOAD_LOG_LIMIT = 200
    }

    override fun onCleared() {
        // 退出聊天页（CHAT 出栈）：抽掉不足一批的记忆抽取尾巴。
        // flush 走 ChatRepository 独立 backgroundScope，VM 销毁后抽取仍会继续
        repo.flushPendingMemoryExtractions()
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
    if (!content.startsWith("@") || participants.isEmpty()) return null

    val tail = content.substring(1)
    val separatorPattern = "^[\\s\\uFF1A:]".toRegex()

    // 按名字长度降序，保证前缀重叠时最长名字优先
    val sortedParticipants = participants.sortedByDescending { it.name.trim().length }

    for (character in sortedParticipants) {
        val name = character.name.trim()
        if (name.isEmpty()) continue

        if (tail.startsWith(name, ignoreCase = true)) {
            val afterName = tail.substring(name.length)
            // @名字 后必须紧跟分隔符（空白/冒号），直接结束或非分隔符字符均不命中
            if (afterName.isNotEmpty() && afterName.first().toString().matches(separatorPattern)) {
                return character.id
            }
        }
    }

    return null
}
