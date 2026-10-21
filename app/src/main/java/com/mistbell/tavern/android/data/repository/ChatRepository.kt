package com.mistbell.tavern.android.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.model.*
import com.mistbell.tavern.android.data.local.entity.*
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import com.mistbell.tavern.android.service.MemoryExtractionService
import com.mistbell.tavern.android.util.parseGroupSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class ChatRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database
    private val api get() = ApiClient.getApi(context)
    private val settingsRepo = SettingsRepository(context)
    private val structuredMemoryRepo = StructuredMemoryRepository(context)
    private val memoryExtractionService = MemoryExtractionService(context, structuredMemoryRepo)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // 消息窗口默认大小（v16 性能修复）：首屏只观察最新 200 条，更旧消息由 loadOlderMessages 按页补加载
        const val DEFAULT_MESSAGE_WINDOW = 200

        // 群聊推动语（continueGroupChat）：由 ChatRepository 作为 system 消息传给 PromptBuilder
        // （classic 路径不涉及）；文案要求"让最合适的下一位角色自然接话"
        internal const val GROUP_CONTINUE_NUDGE = "（请让最合适的下一位角色自然接话，保持对话推进）"
    }

    /**
     * 待抽取缓冲挂在 AppContainer 上，而不是本实例的字段。
     *
     * 两层原因：每个聊天页都会新建一个 ChatRepository，缓冲若随实例走就会各攒一半、谁也攒不满；
     * 且缓冲需要落盘，进程被杀后重启还能接着攒（原实现只活在内存里，未满一批的轮次会丢）。
     */
    private val pendingExtractionStore get() = TavernApplication.instance.container.pendingExtractionStore

    // --- Local-first reads ---

    // 域映射与 distinctUntilChanged 的按值比较都可能有开销，统一切到 Default 线程，
    // 避免阻塞 Room 回调线程或收集方（主线程）
    fun observeCharacters(): Flow<List<Character>> {
        return db.characterDao().getAll().map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    fun observeSessions(
        ownerId: String,
        characterId: String,
    ): Flow<List<SessionSummary>> {
        return db.sessionDao().getByCharacter(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    fun observeRecentSessions(ownerId: String): Flow<List<SessionSummary>> {
        return db.sessionDao().getRecent(ownerId).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    // 消息窗口观察：只取最新 limit 条（DESC 查询反转回 ASC 展示），长会话不再全表加载。
    // Message 是 data class，distinctUntilChanged 按值去重，过滤 Room 无效化导致的多余重发。
    // 会话级语义（跨代理契约 2）：characterId 参数仅为调用方兼容保留，不再下传 DAO——
    // 会话是消息的完整归属单元，character_id 仅作说话方元数据（群聊），观察窗口按
    // (session_id, owner_id) 全量取，群聊 NPC 消息与主角色消息同窗可见。
    fun observeMessages(
        ownerId: String,
        characterId: String,
        sessionId: String,
        limit: Int = DEFAULT_MESSAGE_WINDOW,
    ): Flow<List<Message>> {
        return db.messageDao().getLatestBySession(sessionId, ownerId, limit).map { entities ->
            entities.map { it.toDomain() }.asReversed()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    // 上滚加载更旧一页：一次性挂起查询（非流）。修复3：游标为复合游标（窗口最旧一条的
    // created_at + id），与 DAO 的 (created_at DESC, id DESC) 排序构成全序，
    // 同 created_at 的并列消息不会被 LIMIT 切开永久丢失。
    // 会话级语义（跨代理契约 2）：characterId 参数仅为调用方兼容保留，不再下传 DAO。
    suspend fun loadOlderMessages(
        sessionId: String,
        ownerId: String,
        characterId: String,
        beforeCreatedAt: String,
        beforeId: String,
        limit: Int,
    ): List<Message> {
        return withContext(Dispatchers.IO) {
            db.messageDao()
                .getOlderBySession(sessionId, ownerId, beforeCreatedAt, beforeId, limit)
                .map { it.toDomain() }
                .asReversed()
        }
    }

    suspend fun getActiveSessionId(
        ownerId: String,
        characterId: String,
    ): String {
        val sessions = db.sessionDao().getByCharacter(ownerId, characterId).first()
        return sessions.firstOrNull()?.id ?: ""
    }

    // --- Write operations (local-first + optional sync) ---

    suspend fun sendMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        message: String,
        worldBookId: String = "",
        onPartial: ((String) -> Unit)? = null,
        // 群聊上下文（跨代理契约 4）：group 模式由 VM 传入（含 @提及 解析出的 targetSpeakerId）；
        // null = classic 模式（或未传时按会话 mode 兜底构建），提示词与归属行为与改动前完全一致
        groupContext: GroupChatContext? = null,
    ): Message {
        return withContext(Dispatchers.IO) {
            val msgId = UUID.randomUUID().toString()
            val userMsg =
                Message(
                    id = msgId,
                    role = "user",
                    content = message,
                    thinking = null,
                    createdAt = java.time.Instant.now().toString(),
                    memoryIds = null,
                    swipes = null,
                    swipeIndex = 0,
                )

            // 1. Save user message locally
            db.messageDao().upsert(
                MessageEntity.fromDomain(userMsg, sessionId, ownerId, characterId),
            )
            TavernEventBus.emit(TavernEvent.MessageSent(ownerId, characterId, sessionId, msgId))

            // 1.5. 源插入与恢复意图已由同一 Room 事务记录；后台恢复器自行在锁外 embedding、
            // 锁内核验版本，删除/回滚后的旧任务不会把消息写回索引。
            backgroundScope.launch {
                storeUserMessageVector(
                    content = message,
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = sessionId,
                    messageId = msgId,
                )
            }

            // 2. Update session message count
            val session = db.sessionDao().get(sessionId, ownerId, characterId)
            if (session != null) {
                db.sessionDao().upsert(
                    session.copy(
                        messageCount = session.messageCount + 1,
                        updatedAt = userMsg.createdAt,
                        title =
                            if (session.title.isBlank() && session.messageCount == 0) {
                                message.take(26)
                            } else {
                                session.title
                            },
                    ),
                )
            }

            // 3. Try to get AI response via LLM
            var insertedAssistantId: String? = null
            // 会话三元组收拢（owner + 主角色 + 会话）：落库/取消路径共用，避免私有 API 参数爆炸
            val scope = SessionScope(ownerId, characterId, sessionId)
            // 群聊上下文作用域覆盖 try/catch：取消路径也需据此做归属解析（群聊部分回复剥前缀）
            var effectiveGroupContext: GroupChatContext? = null
            // 流式累计缓冲，作用域覆盖整个 try，取消时据此判断是否已有部分回复
            val sb = StringBuilder()
            // GenerationStarted/Ended 严格配对：started 发射后才置位，finally 据此补 ended
            var generationActive = false
            try {
                val llmConfig = loadLlmConfig(sessionId)
                if (llmConfig.baseUrl.isNotBlank() && llmConfig.apiKey.isNotBlank()) {
                    // 群聊模式：优先用 VM 传入的 groupContext；未传时按会话 mode/参与者兜底构建
                    effectiveGroupContext = groupContext ?: loadGroupContext(ownerId, characterId, sessionId)
                    // 参与者空名单兜底（minor 修复）：VM 首帧竞态可能传入 speakerNames 为空 map 的
                    // groupContext（参与者尚未加载完成），此时按会话重建一次，防止整轮群聊退化为
                    // 无说话方表（历史无前缀、回复无法归属）
                    if (effectiveGroupContext?.speakerNames?.isEmpty() == true) {
                        effectiveGroupContext =
                            loadGroupContext(ownerId, characterId, sessionId) ?: effectiveGroupContext
                    }
                    val promptMessages =
                        PromptBuilder.buildPrompt(
                            db,
                            ownerId,
                            characterId,
                            sessionId,
                            message,
                            currentMessageId = msgId,
                            groupContext = effectiveGroupContext,
                            contextTokenLimitOverride = llmConfig.contextTokenLimit,
                        )
                    TavernEventBus.emit(TavernEvent.GenerationStarted(sessionId))
                    generationActive = true
                    collectReply(llmConfig, promptMessages, sb, onPartial)
                    // 公共尾部：群聊归属解析 + 落库 + 向量化 + 计数回写 + 记忆提取
                    // （classic 时 speakerNames 为 null，归属/内容与改动前逐字节一致）
                    val assistantMsg =
                        persistAssistantReply(
                            fullReply = sb.toString(),
                            scope = scope,
                            speakerNames = effectiveGroupContext?.speakerNames,
                            mode = ReplyTailMode.Append(userMessageForMemory = message, userMessageId = msgId),
                        )
                    insertedAssistantId = assistantMsg.id

                    return@withContext assistantMsg
                } else {
                    throw Exception("LLM 未配置：请在设置中配置 API 密钥")
                }
            } catch (e: CancellationException) {
                // 用户主动停止生成：不同于网络失败，不回滚用户消息。
                // 已收到部分回复则落库并回写计数，然后向上抛出取消。
                // 取消路径统一（跨代理契约 3）：群聊先做与成功路径 persistAssistantReply 相同的
                // splitThinking + parseGroupSpeaker 归属解析再落库；classic 行为不变（原文落库）。
                // effectiveGroupContext 尚未构建时（取消发生在提示词组装前）按会话兜底重建
                if (sb.isNotEmpty()) {
                    val cancelSpeakerNames =
                        effectiveGroupContext?.speakerNames
                            ?: loadGroupContext(ownerId, characterId, sessionId)?.speakerNames
                    persistPartialReplyOnCancel(
                        partialReply = sb.toString(),
                        scope = scope,
                        speakerNames = cancelSpeakerNames,
                    )
                }
                throw e
            } catch (e: Exception) {
                // LLM 调用或收尾失败：事务内回滚本条消息（用户消息，若已插入还包括回复），
                // 计数按真实行数重算（自愈，不依赖增量加减），再抛出异常让 UI 显示错误。
                // 已知残留：已异步写入的向量无法按消息清理（无对应接口，见 ROADMAP 向量双写一致性）
                //
                // 回滚的 Room 删除放进会话锁：与抽取保存的版本复核 + 落库临界区互斥——
                // 否则"复查通过 → 回滚删消息 → 保存引用已删轮次的记忆"的窗口仍在。
                // 向量删除留在锁外（删除意图机制本身就是提交时校验）。
                SessionMutationLocks.withSessionLock(sessionId) {
                    db.withTransaction {
                        db.messageDao().deleteById(msgId)
                        insertedAssistantId?.let { db.messageDao().deleteById(it) }
                        val sessionForRollback = db.sessionDao().get(sessionId, ownerId, characterId)
                        if (sessionForRollback != null) {
                            db.sessionDao().upsert(
                                sessionForRollback.copy(
                                    messageCount = db.messageDao().getBySession(sessionId, ownerId).first().size,
                                    title = if (sessionForRollback.title == message.take(26)) "" else sessionForRollback.title,
                                ),
                            )
                        }
                    }
                }
                deleteMessageVectors(msgId)
                insertedAssistantId?.let { deleteMessageVectors(it) }
                throw e
            } finally {
                // 成功/失败/取消统一在此收尾，与 GenerationStarted 严格配对
                if (generationActive) {
                    TavernEventBus.emit(TavernEvent.GenerationEnded(sessionId))
                }
            }
        }
    }

    /**
     * 群聊推动（跨代理契约 4）：不插入用户消息，注入系统级推动语让最合适的下一位角色接话。
     * 回复归属解析/落库/向量化/会话计数回写与 sendMessage 共用 persistAssistantReply 公共尾部。
     *
     * @param worldBookId 预留参数（与 sendMessage 一致，实际世界书由 PromptBuilder 按会话/角色解析）
     *
     * 函数体在会话锁改造前即为 61 行（阈值 60），仅超 1 行；拆分取消/异常路径风险高于收益，豁免。
     */
    @Suppress("LongMethod")
    suspend fun continueGroupChat(
        ownerId: String,
        characterId: String,
        sessionId: String,
        worldBookId: String = "",
        onPartial: ((String) -> Unit)? = null,
    ): Message {
        return withContext(Dispatchers.IO) {
            // 仅群聊会话可推动；classic 会话直接报错（UI 由 groupMode 门控，此处双保险）
            val groupContext =
                loadGroupContext(ownerId, characterId, sessionId)
                    ?: error("会话不存在或不是群聊模式，无法推动群聊")
            var insertedAssistantId: String? = null
            val scope = SessionScope(ownerId, characterId, sessionId)
            val sb = StringBuilder()
            try {
                val llmConfig = loadLlmConfig(sessionId)
                check(llmConfig.baseUrl.isNotBlank() && llmConfig.apiKey.isNotBlank()) {
                    "LLM 未配置：请在设置中配置 API 密钥"
                }
                val promptMessages =
                    PromptBuilder.buildPrompt(
                        db,
                        ownerId,
                        characterId,
                        sessionId,
                        // 无新用户消息；推动语作为 system 注入（groupNudge 非 null 时 PromptBuilder 不追加用户消息）
                        userMessage = "",
                        groupContext = groupContext,
                        groupNudge = GROUP_CONTINUE_NUDGE,
                        contextTokenLimitOverride = llmConfig.contextTokenLimit,
                    )
                collectReply(llmConfig, promptMessages, sb, onPartial)
                // 公共尾部：归属解析/落库/计数回写与 sendMessage 完全一致；本轮无用户消息参与记忆提取
                val assistantMsg =
                    persistAssistantReply(
                        fullReply = sb.toString(),
                        scope = scope,
                        speakerNames = groupContext.speakerNames,
                        mode = ReplyTailMode.Append(userMessageForMemory = "", userMessageId = null),
                    )
                insertedAssistantId = assistantMsg.id

                return@withContext assistantMsg
            } catch (e: CancellationException) {
                // 用户主动停止生成：已收到部分回复则落库并回写计数。
                // 取消路径统一（跨代理契约 3）：群聊部分回复先做与 persistAssistantReply 一致的
                // splitThinking + parseGroupSpeaker 归属解析再落库（复用 persistPartialReplyOnCancel）
                if (sb.isNotEmpty()) {
                    persistPartialReplyOnCancel(
                        partialReply = sb.toString(),
                        scope = scope,
                        speakerNames = groupContext.speakerNames,
                    )
                }
                throw e
            } catch (e: Exception) {
                // LLM 调用或收尾失败：无用户消息可回滚，仅删除已插入的助手回复并按真实行数重算计数。
                // Room 删除放进会话锁（同 sendMessage 回滚的理由）
                SessionMutationLocks.withSessionLock(sessionId) {
                    db.withTransaction {
                        insertedAssistantId?.let { db.messageDao().deleteById(it) }
                        val sessionForRollback = db.sessionDao().get(sessionId, ownerId, characterId)
                        if (sessionForRollback != null) {
                            db.sessionDao().upsert(
                                sessionForRollback.copy(
                                    messageCount = db.messageDao().getBySession(sessionId, ownerId).first().size,
                                ),
                            )
                        }
                    }
                }
                throw e
            }
        }
    }

    suspend fun undoLastMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 事务保证删除与计数回写原子完成，避免中途失败导致计数漂移。
            // 放进会话锁：删除源消息与抽取保存的版本复核 + 落库临界区互斥
            val deletedMessageId =
                SessionMutationLocks.withSessionLock(sessionId) {
                    db.withTransaction {
                        val messages = db.messageDao().getBySession(sessionId, ownerId).first()
                        if (messages.isNotEmpty()) {
                            db.messageDao().deleteById(messages.last().id)
                            val session = db.sessionDao().get(sessionId, ownerId, characterId)
                            if (session != null) {
                                db.sessionDao().upsert(session.copy(messageCount = messages.size - 1))
                            }
                        }
                        messages.lastOrNull()?.id
                    }
                }
            deletedMessageId?.let { deleteMessageVectors(it) }
            val remainingIds = db.messageDao().getBySession(sessionId, ownerId).first().mapTo(HashSet()) { it.id }
            prunePendingExtractionTurns(sessionId, remainingIds)
        }
    }

    suspend fun backtrackToMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
    ) {
        withContext(Dispatchers.IO) {
            val messages = db.messageDao().getBySession(sessionId, ownerId).first()
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                // 放进会话锁：截断删除源消息与抽取保存的版本复核 + 落库临界区互斥
                SessionMutationLocks.withSessionLock(sessionId) {
                    db.messageDao().deleteAfter(sessionId, messageId, ownerId)
                }
                messages.drop(idx + 1).forEach { deleteMessageVectors(it.id) }
                prunePendingExtractionTurns(sessionId, messages.take(idx + 1).mapTo(HashSet()) { it.id })
            }
        }
    }

    suspend fun regenerateMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        onPartial: ((String) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val msg = db.messageDao().getById(messageId, sessionId, ownerId) ?: return@withContext
            if (msg.role != "assistant") return@withContext
            val scope = SessionScope(ownerId, characterId, sessionId)

            // 先取上下文与配置，任何删除都在拿到新回复成功之后，避免旧消息丢失而新回复没来
            // （会话级读取：群聊 NPC 消息与主角色消息同窗，均在可追溯的用户消息范围内）
            val userMessages = db.messageDao().getBySession(sessionId, ownerId).first()
            val lastUserMsg =
                userMessages.lastOrNull { it.role == "user" }
                    ?: throw IllegalStateException("没有可重新生成的用户消息")
            val llmConfig = loadLlmConfig(sessionId)
            if (llmConfig.baseUrl.isBlank() || llmConfig.apiKey.isBlank()) {
                throw IllegalStateException("LLM 未配置：请在设置中配置 API 密钥")
            }

            // 群聊对齐（跨代理契约 4）：group 会话按 loadGroupContext 构建群聊上下文传 buildPrompt，
            // 提示词行为（规范块、历史说话方前缀、当前消息前缀）与 sendMessage/continueGroupChat 一致；
            // classic 会话 groupContext 为 null，提示词与改动前完全一致
            val session = db.sessionDao().get(sessionId, ownerId, characterId)
            val groupContext =
                if (session?.mode == SESSION_MODE_GROUP) {
                    loadGroupContext(ownerId, characterId, sessionId)
                } else {
                    null
                }

            // excludeFromMessageId：截断目标消息及其之后的历史，
            // 保证正要被替换的旧回复不进入上下文（否则模型会复述旧答案）
            val prompt =
                PromptBuilder.buildPrompt(
                    db,
                    ownerId,
                    characterId,
                    sessionId,
                    lastUserMsg.content,
                    currentMessageId = lastUserMsg.id,
                    excludeFromMessageId = messageId,
                    groupContext = groupContext,
                    contextTokenLimitOverride = llmConfig.contextTokenLimit,
                )
            val sb = StringBuilder()
            val replacedMessageIds =
                userMessages
                    .dropWhile { it.id != messageId }
                    .map { it.id }
            try {
                collectReply(llmConfig, prompt, sb, onPartial)
            } catch (e: CancellationException) {
                // 用户主动停止重新生成：不触发失败回滚。
                // 已收到部分回复则按替换事务落库（群聊先做与成功路径一致的归属解析，
                // 复用 persistPartialReplyOnCancel，勿复制粘贴）；空则直接上抛取消。
                if (sb.isNotEmpty()) {
                    persistPartialReplyOnCancel(
                        partialReply = sb.toString(),
                        scope = scope,
                        speakerNames = groupContext?.speakerNames,
                        replaceFromMessageId = messageId,
                    )
                }
                throw e
            }

            // 成功拿到新回复：落库复用 persistAssistantReply 公共尾部（跨代理契约 4）——
            // 归属解析 + 替换事务（删目标及其后、落新消息、按真实行数回写计数）+ 向量化。
            // 已知残留：被替换的旧消息向量没有按消息删除的接口，暂无法清理（见 ROADMAP 向量双写一致性问题）
            persistAssistantReply(
                fullReply = sb.toString(),
                scope = scope,
                speakerNames = groupContext?.speakerNames,
                mode = ReplyTailMode.Replace(fromMessageId = messageId),
            )
            replacedMessageIds.forEach { deleteMessageVectors(it) }
        }
    }

    suspend fun continueMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        onPartial: ((String) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val messages = db.messageDao().getBySession(sessionId, ownerId).first()
            val target =
                messages.lastOrNull { it.role == "assistant" }
                    ?: error("没有可继续的助手消息")
            val lastUser =
                messages.lastOrNull { it.role == "user" && it.createdAt < target.createdAt }
                    ?: error("没有可继续的用户消息")
            val config = loadLlmConfig(sessionId)
            check(config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) { "LLM 未配置：请在设置中配置 API 密钥" }
            val prompt =
                PromptBuilder.buildPrompt(
                    db,
                    ownerId,
                    characterId,
                    sessionId,
                    userMessage = "请自然地继续上一条助手回复，不要重复已经说过的内容。",
                    groupContext = loadGroupContext(ownerId, characterId, sessionId),
                    contextTokenLimitOverride = config.contextTokenLimit,
                )
            val sb = StringBuilder()
            try {
                collectReply(config, prompt, sb, onPartial)
            } catch (e: CancellationException) {
                if (sb.isNotEmpty()) appendContinuation(target, sb.toString(), ownerId, characterId, sessionId)
                throw e
            }
            appendContinuation(target, sb.toString(), ownerId, characterId, sessionId)
        }
    }

    // 本函数的体量/分支在会话锁改造前已超阈值；拆分需要改动取消路径与 swipe 生成路径的
    // 控制流（安全风险高于收益），豁免并在后续专项重构中处理。
    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    suspend fun swipeMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        direction: String,
        onPartial: ((String) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val entity =
                requireNotNull(db.messageDao().getById(messageId, sessionId, ownerId)) { "消息不存在" }
            require(entity.role == "assistant") { "只有助手消息支持 swipe" }
            val existing = decodeStringList(entity.swipesJson).ifEmpty { listOf(entity.content) }.toMutableList()
            val thinkingSwipes = decodeStringList(entity.thinkingSwipesJson).toMutableList()
            val currentIndex = entity.swipeIndex.coerceIn(0, existing.lastIndex)
            // Keep imported/legacy swipe arrays index-aligned with their thinking text.
            while (thinkingSwipes.size < existing.size) {
                val index = thinkingSwipes.size
                thinkingSwipes += if (index == currentIndex) entity.thinking.orEmpty() else ""
            }
            if (thinkingSwipes.size > existing.size) {
                thinkingSwipes.subList(existing.size, thinkingSwipes.size).clear()
            }
            val step = if (direction.equals("left", ignoreCase = true)) -1 else 1
            val nextIndex = currentIndex + step
            if (nextIndex in existing.indices) {
                // 换 swipe 是"同 id 消息内容变更"：Room upsert 放进会话锁，与抽取保存的
                // 版本复核互斥（签名失配的轮次会在保存时被整轮作废）；锁内复查会话仍在——
                // 删除方先拿到锁则放弃写入，不复活已删数据。
                // 向量删除/重写留在锁外——删除意图机制（addIfAllowed 提交时闸门）兜底在途写入。
                val persisted =
                    SessionMutationLocks.withSessionLock(sessionId) {
                        val session = db.sessionDao().getById(sessionId)
                        if (session == null || session.ownerId != ownerId) return@withSessionLock false
                        db.messageDao().upsert(
                            entity.copy(
                                content = existing[nextIndex],
                                thinking = thinkingSwipes.getOrNull(nextIndex).orEmpty().ifBlank { null },
                                swipeIndex = nextIndex,
                                swipesJson = encodeStringList(existing),
                                thinkingSwipesJson = encodeStringList(thinkingSwipes),
                            ),
                        )
                        true
                    }
                if (!persisted) return@withContext
                deleteMessageVectors(messageId)
                storeAssistantMessageVector(
                    existing[nextIndex],
                    ownerId,
                    entity.characterId.ifBlank { characterId },
                    sessionId,
                    messageId,
                )
                return@withContext
            }
            val lastUser =
                db.messageDao().getBySession(sessionId, ownerId).first()
                    .lastOrNull { it.role == "user" && it.createdAt < entity.createdAt }
                    ?: error("没有可用于生成替代回复的用户消息")
            val config = loadLlmConfig(sessionId)
            check(config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) { "LLM 未配置：请在设置中配置 API 密钥" }
            val prompt =
                PromptBuilder.buildPrompt(
                    db,
                    ownerId,
                    characterId,
                    sessionId,
                    lastUser.content,
                    currentMessageId = lastUser.id,
                    excludeFromMessageId = messageId,
                    groupContext = loadGroupContext(ownerId, characterId, sessionId),
                    contextTokenLimitOverride = config.contextTokenLimit,
                )
            val sb = StringBuilder()
            try {
                collectReply(config, prompt, sb, onPartial)
            } catch (e: CancellationException) {
                // A stopped swipe still owns a valid partial candidate. Persist it
                // before propagating cancellation so Room and the UI stay aligned.
                if (sb.isNotEmpty()) {
                    val (partialContent, partialThinking, partialCharacterId) =
                        resolveReplyAttribution(
                            sb.toString(),
                            loadGroupContext(ownerId, characterId, sessionId)?.speakerNames,
                            characterId,
                        )
                    if (partialContent.isNotBlank() || !partialThinking.isNullOrBlank()) {
                        persistSwipeCandidate(
                            entity,
                            existing,
                            thinkingSwipes,
                            step,
                            partialContent,
                            partialThinking,
                            partialCharacterId,
                            ownerId,
                            characterId,
                            sessionId,
                        )
                    }
                }
                throw e
            }
            val (newContent, newThinking, storedCharacterId) =
                resolveReplyAttribution(
                    sb.toString(),
                    loadGroupContext(ownerId, characterId, sessionId)?.speakerNames,
                    characterId,
                )
            if (newContent.isBlank() && newThinking.isNullOrBlank()) return@withContext
            persistSwipeCandidate(
                entity,
                existing,
                thinkingSwipes,
                step,
                newContent,
                newThinking,
                storedCharacterId,
                ownerId,
                characterId,
                sessionId,
            )
        }
    }

    // 参数即一条 swipe 候选落库所需的全部上下文（收对象只会把同一批字段在调用方再拼一遍），
    // 函数先于本轮改造存在，豁免。
    @Suppress("LongParameterList")
    private suspend fun persistSwipeCandidate(
        entity: MessageEntity,
        existing: MutableList<String>,
        thinkingSwipes: MutableList<String>,
        step: Int,
        content: String,
        thinking: String?,
        storedCharacterId: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        if (step > 0) {
            existing += content
            thinkingSwipes += thinking.orEmpty()
        } else {
            existing.add(0, content)
            thinkingSwipes.add(0, thinking.orEmpty())
        }
        while (thinkingSwipes.size < existing.size) thinkingSwipes += ""
        if (thinkingSwipes.size > existing.size) {
            thinkingSwipes.subList(existing.size, thinkingSwipes.size).clear()
        }
        val newIndex = if (step > 0) existing.lastIndex else 0
        // 新生成 swipe 候选落库（同 id 内容变更）：Room upsert 放进会话锁，与抽取保存的
        // 版本复核互斥；锁内复查会话与目标消息仍在（慢 LLM 期间会话可能已被删、消息可能
        // 已被清空/回退）——失效则整段放弃（含向量重写），不复活已删数据。
        // 向量删除/重写留在锁外——删除意图机制兜底在途写入。
        val persisted =
            SessionMutationLocks.withSessionLock(sessionId) {
                val session = db.sessionDao().getById(sessionId)
                if (session == null || session.ownerId != ownerId) return@withSessionLock false
                if (db.messageDao().getById(entity.id, sessionId, ownerId) == null) {
                    return@withSessionLock false
                }
                db.messageDao().upsert(
                    entity.copy(
                        characterId = storedCharacterId,
                        content = content,
                        thinking = thinking,
                        swipesJson = encodeStringList(existing),
                        swipeIndex = newIndex,
                        thinkingSwipesJson = encodeStringList(thinkingSwipes),
                    ),
                )
                true
            }
        if (!persisted) return
        deleteMessageVectors(entity.id)
        storeAssistantMessageVector(
            content = content,
            ownerId = ownerId,
            characterId = storedCharacterId.ifBlank { characterId },
            sessionId = sessionId,
            messageId = entity.id,
            // 这是"同一条消息换了 swipe 内容"的替换写入：上面那次删除会登记删除意图，
            // 不解除的话新 swipe 的向量会被永久压住
            replacePrevious = true,
        )
    }

    suspend fun clearConversation(
        ownerId: String,
        // 会话级删除以 (owner, session) 为键，主角色不再参与向量过滤（群聊 NPC 向量须一并清除）；
        // 保留参数位以免破坏调用方签名
        @Suppress("UnusedParameter") characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 整段放入会话锁：与在途的记忆保存、消息向量化互斥，删除期间不会有新写入落地
            SessionMutationLocks.withSessionLock(sessionId) {
                pendingExtractionStore.discard(sessionId)
                // 与 deleteSession 对称：清空连本会话的结构化记忆一起清掉——
                // 旧记忆留在库里只会被召回注入一个"什么都没发生过"的提示词
                structuredMemoryRepo.deleteMemoriesBySession(ownerId, sessionId)
                // 会话级删除：群聊 NPC 消息与主角色消息同属一个归属单元，一并清空
                val messageIds = db.messageDao().idsBySession(sessionId, ownerId)
                db.messageDao().deleteBySession(sessionId, ownerId)
                deleteSessionVectors(ownerId, sessionId, messageIds)
            }
        }
    }

    // 体量因本轮锁内复查从阈值下升到 68 行；合并逻辑（swipes 对齐 + 锁内 upsert）强耦合，
    // 拆分需引入参数对象且收益有限，豁免。
    @Suppress("LongMethod")
    private suspend fun appendContinuation(
        target: MessageEntity,
        rawReply: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        val (continuation, continuationThinking, storedCharacterId) =
            resolveReplyAttribution(
                rawReply,
                loadGroupContext(ownerId, characterId, sessionId)?.speakerNames,
                characterId,
            )
        if (continuation.isBlank() && continuationThinking.isNullOrBlank()) return
        val mergedContent =
            listOf(target.content.trimEnd(), continuation.trimStart())
                .filter { it.isNotBlank() }
                .joinToString("\n")
        val mergedThinking =
            listOfNotNull(
                target.thinking?.takeIf { it.isNotBlank() },
                continuationThinking,
            ).joinToString("\n\n").ifBlank { null }
        val swipes = decodeStringList(target.swipesJson).toMutableList()
        val thinkingSwipes = decodeStringList(target.thinkingSwipesJson).toMutableList()
        if (swipes.isNotEmpty()) {
            val currentIndex = target.swipeIndex.coerceIn(0, swipes.lastIndex)
            while (thinkingSwipes.size < swipes.size) thinkingSwipes += ""
            if (thinkingSwipes.size > swipes.size) thinkingSwipes.subList(swipes.size, thinkingSwipes.size).clear()
            swipes[currentIndex] = mergedContent
            thinkingSwipes[currentIndex] = mergedThinking.orEmpty()
        }
        // 续写是"同 id 消息内容合并变更"：Room upsert 放进会话锁，与抽取保存的
        // 版本复核互斥；锁内复查会话与目标消息仍在（慢 LLM 期间会话可能已被删、消息可能
        // 已被清空/回退）——失效则整段放弃（含向量重写），不复活已删数据。
        // 向量替换写入留在锁外——删除意图机制兜底在途写入。
        val persisted =
            SessionMutationLocks.withSessionLock(sessionId) {
                val session = db.sessionDao().getById(sessionId)
                if (session == null || session.ownerId != ownerId) return@withSessionLock false
                if (db.messageDao().getById(target.id, sessionId, ownerId) == null) {
                    return@withSessionLock false
                }
                db.messageDao().upsert(
                    target.copy(
                        characterId = storedCharacterId,
                        content = mergedContent,
                        thinking = mergedThinking,
                        swipesJson =
                            if (swipes.isEmpty()) {
                                target.swipesJson
                            } else {
                                encodeStringList(swipes)
                            },
                        thinkingSwipesJson =
                            if (thinkingSwipes.isEmpty()) {
                                target.thinkingSwipesJson
                            } else {
                                encodeStringList(thinkingSwipes)
                            },
                    ),
                )
                true
            }
        if (!persisted) return
        deleteMessageVectors(target.id)
        storeAssistantMessageVector(
            content = mergedContent,
            ownerId = ownerId,
            characterId = storedCharacterId,
            sessionId = sessionId,
            messageId = target.id,
            // 同上：继续生成是"合并进同一条消息"，属于替换而非新增
            replacePrevious = true,
        )
        db.sessionDao().get(sessionId, ownerId, characterId)?.let { session ->
            db.sessionDao().upsert(session.copy(updatedAt = java.time.Instant.now().toString()))
        }
    }

    private fun decodeStringList(jsonText: String): List<String> =
        try {
            if (jsonText.isBlank()) emptyList() else Json.decodeFromString<List<String>>(jsonText)
        } catch (_: Exception) {
            emptyList()
        }

    private fun encodeStringList(values: List<String>): String =
        Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
            values,
        )

    // null = 跟随全局默认（读取时解析）；显式值才会压过全局——建会话不再快照全局默认
    suspend fun createSession(
        ownerId: String,
        characterId: String,
        title: String = "",
        providerId: String = "",
        enableLongTermMemory: Boolean? = null,
        worldBookId: String = "",
    ): String {
        return withContext(Dispatchers.IO) {
            val sessionId = UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            // 如果没有指定 providerId，取 api_configs 默认配置（会话 providerId 存 ApiConfig.id）
            val actualProviderId =
                if (providerId.isBlank()) {
                    db.apiConfigDao().getDefault()?.id ?: ""
                } else {
                    providerId
                }

            val session =
                SessionEntity(
                    id = sessionId,
                    ownerId = ownerId,
                    characterId = characterId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    messageCount = 0,
                    providerId = actualProviderId,
                    modelId = "",
                    worldBookId = worldBookId,
                    summaryJson = "",
                    enableLongTermMemory = enableLongTermMemory,
                    participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(listOf(characterId)),
                )
            db.sessionDao().upsert(session)
            sessionId
        }
    }

    suspend fun deleteSession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 整段放入会话锁：与在途的记忆保存（LLM 抽取落库）、消息向量化互斥——
            // 锁内执行期间不会有新写入落地，删除完成后在途任务的锁内复查会发现会话已消失
            SessionMutationLocks.withSessionLock(sessionId) {
                // 待抽取缓冲同步丢弃：源消息都要没了，留着只会在以后 drain 时抽出悬空记忆
                pendingExtractionStore.discard(sessionId)
                // 删除该会话的所有记忆
                db.memoryDao().deleteBySession(ownerId, characterId, sessionId)
                structuredMemoryRepo.deleteMemoriesBySession(ownerId, sessionId)
                val messageIds = db.messageDao().idsBySession(sessionId, ownerId)
                db.vectorMemoryDao().deleteBySession(ownerId, sessionId)
                deleteSessionVectors(ownerId, sessionId, messageIds)
                android.util.Log.d("ChatRepository", "Deleted memories for session: $sessionId")

                // 删除消息和会话（会话级删除，含群聊 NPC 消息）
                db.messageDao().deleteBySession(sessionId, ownerId)
                db.sessionDao().delete(sessionId, ownerId, characterId)
            }
        }
    }

    // --- Helpers ---

    // 收集完整回复：流式开 → SSE 逐增量累计，onPartial 每次回调累计全文供 UI 渲染；流式关 → 整包返回不调 onPartial。
    // sb 由调用方持有（作用域覆盖其 try/catch），取消/异常路径可据此判断并保存部分回复。
    private suspend fun collectReply(
        llmConfig: LlmConfig,
        promptMessages: List<ChatMessage>,
        sb: StringBuilder,
        onPartial: ((String) -> Unit)?,
    ) {
        if (llmConfig.streamingEnabled) {
            val thinking = StringBuilder()
            val content = StringBuilder()
            LlmClient.chatStreamWithThinking(llmConfig, promptMessages).collect { delta ->
                if (!delta.thinking.isNullOrBlank()) thinking.append(delta.thinking)
                if (delta.text.isNotEmpty()) content.append(delta.text)
                sb.replace(0, sb.length, formatReply(content.toString(), thinking.toString()))
                onPartial?.invoke(sb.toString())
            }
        } else {
            val response = LlmClient.chatWithThinking(llmConfig, promptMessages)
            sb.append(formatReply(response.text, response.thinking))
        }
    }

    /** 将协议层分离出的思维链重新编码为现有消息存储格式。 */
    private fun formatReply(
        content: String,
        thinking: String?,
    ): String {
        val cleanThinking = thinking?.trim().orEmpty()
        return if (cleanThinking.isBlank()) {
            content
        } else {
            "<think>\n$cleanThinking\n</think>\n$content"
        }
    }

    /**
     * 回复归属解析公共函数（成功/取消路径共用，避免复制粘贴）：
     * 1) F2.1 回复清洗——提取全部 <think>…</think> 块为思考内容，正文不含 think；
     * 2) 群聊归属解析——对清洗后的回复 parseGroupSpeaker：
     *    命中「名字:」前缀 → 归属说话 NPC（character_id 写为说话者 id）、内容剥前缀；
     *    未命中 → 保持主角色与清洗后原文。
     * 返回 Triple(存储正文, 思考内容, 存储归属角色 id)。
     * classic（speakerNames 为 null）时正文清洗照常、归属保持主角色。
     */
    private fun resolveReplyAttribution(
        fullReply: String,
        speakerNames: Map<String, String>?,
        fallbackCharacterId: String,
    ): Triple<String, String?, String> {
        val (replyContent, replyThinking) = splitThinking(fullReply)
        // 群聊归属 MVP：流式期间气泡按主角色显示原文，完成后才按「名字:」前缀解析真实说话者
        val speaker = speakerNames?.let { parseGroupSpeaker(replyContent, it) }
        return Triple(
            speaker?.strippedContent ?: replyContent,
            replyThinking,
            speaker?.speakerId ?: fallbackCharacterId,
        )
    }

    /**
     * 会话三元组（owner + 主角色 + 会话 id）：消息归属、会话计数回写与记忆提取的公共键。
     * 收拢为单一参数，避免仓库私有落库 API 的参数爆炸（detekt LongParameterList）。
     */
    private data class SessionScope(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
    )

    /**
     * 回复落库尾部模式：
     * - [Append]（sendMessage / continueGroupChat）：追加语义，携带记忆提取输入
     *   （群聊推动轮无用户消息，userMessageForMemory 传 ""），计数 +1；
     * - [Replace]（regenerateMessage，跨代理契约 4）：替换语义——事务内先删目标及其后的
     *   全部消息再落库，计数按真实行数自愈重算，不做记忆提取（无新用户消息参与）。
     */
    private sealed interface ReplyTailMode {
        data class Append(
            val userMessageForMemory: String,
            val userMessageId: String?,
        ) : ReplyTailMode

        data class Replace(
            val fromMessageId: String,
        ) : ReplyTailMode
    }

    /**
     * 取消路径（sendMessage / continueGroupChat / regenerateMessage）共用的部分回复落库：
     * - 群聊（speakerNames 非 null）：先做与成功路径 persistAssistantReply 一致的
     *   splitThinking + parseGroupSpeaker 归属解析再落库（跨代理契约 3）；
     * - classic（speakerNames 为 null）：保持改动前行为——原文落库、thinking 为空、归属主角色。
     * - 追加场景（replaceFromMessageId 为 null）：直接 upsert 并回写计数 +1；
     * - 替换场景（regenerateMessage）：先删目标及其后的全部消息再落库，计数按真实行数重算，
     *   同一事务内原子完成。
     *
     * 体量在锁改造前已超阈值；Replace/Append 两路径的事务边界是正确性关键，不做纯为缩行的拆分，豁免。
     */
    @Suppress("LongMethod")
    private suspend fun persistPartialReplyOnCancel(
        partialReply: String,
        scope: SessionScope,
        speakerNames: Map<String, String>?,
        replaceFromMessageId: String? = null,
    ) {
        val content: String
        val thinking: String?
        val storedCharacterId: String
        if (speakerNames != null) {
            val attribution = resolveReplyAttribution(partialReply, speakerNames, scope.characterId)
            content = attribution.first
            thinking = attribution.second
            storedCharacterId = attribution.third
        } else {
            // 协议层的 reasoning 在收集阶段编码成 <think> 块；取消生成也要清洗并落入独立字段，
            // 避免半截思维链出现在正文气泡中。
            val cleaned = splitThinking(partialReply)
            content = cleaned.first
            thinking = cleaned.second
            storedCharacterId = scope.characterId
        }

        val partialMsg =
            Message(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = content,
                thinking = thinking,
                createdAt = java.time.Instant.now().toString(),
                memoryIds = null,
                swipes = null,
                swipeIndex = 0,
            )

        if (replaceFromMessageId != null) {
            // 替换场景：删除旧消息与落库部分回复在同一事务内原子完成，计数按真实行数自愈重算。
            // 事务放进会话锁：删除/替换源消息与抽取保存的版本复核 + 落库临界区互斥；
            // 锁内复查会话仍在——已删则放弃，不复活已删数据
            val persisted =
                SessionMutationLocks.withSessionLock(scope.sessionId) {
                    val sessionNow = db.sessionDao().getById(scope.sessionId)
                    if (sessionNow == null || sessionNow.ownerId != scope.ownerId) {
                        return@withSessionLock false
                    }
                    db.withTransaction {
                        db.messageDao().deleteAfter(scope.sessionId, replaceFromMessageId, scope.ownerId)
                        db.messageDao().deleteById(replaceFromMessageId)
                        db.messageDao().upsert(
                            MessageEntity.fromDomain(partialMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                        )
                        db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)?.let { session ->
                            db.sessionDao().upsert(
                                session.copy(
                                    messageCount =
                                        db.messageDao().getBySession(scope.sessionId, scope.ownerId).first().size,
                                ),
                            )
                        }
                    }
                    true
                }
            if (!persisted) return
            deleteMessageVectors(replaceFromMessageId)
        } else {
            // 追加场景：锁内复查会话仍在才落库（慢生成期间会话可能已被删）
            val persisted =
                SessionMutationLocks.withSessionLock(scope.sessionId) {
                    val sessionNow = db.sessionDao().getById(scope.sessionId)
                    if (sessionNow == null || sessionNow.ownerId != scope.ownerId) {
                        return@withSessionLock false
                    }
                    db.messageDao().upsert(
                        MessageEntity.fromDomain(partialMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                    )
                    true
                }
            if (!persisted) return
            db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)?.let { sessionAfterPartial ->
                db.sessionDao().upsert(
                    sessionAfterPartial.copy(
                        messageCount = sessionAfterPartial.messageCount + 1,
                        updatedAt = partialMsg.createdAt,
                    ),
                )
            }
        }
        storeAssistantMessageVector(
            content = content,
            ownerId = scope.ownerId,
            characterId = storedCharacterId,
            sessionId = scope.sessionId,
            messageId = partialMsg.id,
        )
    }

    /**
     * sendMessage / continueGroupChat / regenerateMessage 共用的回复落库公共尾部（避免复制粘贴）：
     * 1) 回复清洗（think 块提取）；2) 群聊归属解析（resolveReplyAttribution）；3) 助手消息落库；
     * 4) 向量化；5) 会话计数回写；6) 记忆提取（Append 入缓冲攒批，满批合并抽取；
     * Replace 剔除被覆盖轮次，群聊 MVP 仍归属主角色）。
     *
     * 群聊归属（speakerNames 非 null 时）：对清洗后的回复 parseGroupSpeaker——
     * 命中「名字:」前缀 → character_id 写为说话 NPC id、内容剥前缀；未命中 → 保持主角色与原文。
     * classic（speakerNames 为 null）时归属/内容与改动前完全一致。
     *
     * mode：Append 走完整尾部（计数 +1 + 记忆提取）；Replace（regenerateMessage）在同一事务内
     * 原子完成「删目标及其后的全部消息 → 落新回复 → 按真实行数回写计数」，随后仅向量化。
     */
    private suspend fun persistAssistantReply(
        fullReply: String,
        scope: SessionScope,
        speakerNames: Map<String, String>?,
        mode: ReplyTailMode,
    ): Message {
        // 回复清洗 + 群聊归属解析（与取消路径共用 resolveReplyAttribution）
        val (storedContent, replyThinking, storedCharacterId) =
            resolveReplyAttribution(fullReply, speakerNames, scope.characterId)

        val assistantMsg =
            Message(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = storedContent,
                thinking = replyThinking,
                createdAt = java.time.Instant.now().toString(),
                memoryIds = null,
                swipes = null,
                swipeIndex = 0,
            )

        // 会话已删除（慢 LLM 期间）→ 不落库、不向量化、不计数：返回消息仅供 UI 展示已流式内容
        var persisted = false

        when (mode) {
            is ReplyTailMode.Replace -> {
                val replacedIds =
                    db.messageDao().getBySession(scope.sessionId, scope.ownerId).first()
                        .dropWhile { it.id != mode.fromMessageId }
                        .map { it.id }
                // 替换场景：删除旧消息、落库新回复、按真实行数回写计数在同一事务内原子完成
                // （自愈式计数，不依赖增量加减）；失败回滚语义由调用方保证——删除只发生在新回复已到手之后。
                // 事务放进会话锁：删除/替换源消息与抽取保存的版本复核 + 落库临界区互斥；
                // 锁内复查会话仍在（ownerId 匹配）——会话已被删则整段放弃，不复活已删数据
                persisted =
                    SessionMutationLocks.withSessionLock(scope.sessionId) {
                        val sessionNow = db.sessionDao().getById(scope.sessionId)
                        if (sessionNow == null || sessionNow.ownerId != scope.ownerId) {
                            return@withSessionLock false
                        }
                        db.withTransaction {
                            db.messageDao().deleteAfter(scope.sessionId, mode.fromMessageId, scope.ownerId)
                            db.messageDao().deleteById(mode.fromMessageId)
                            db.messageDao().upsert(
                                MessageEntity.fromDomain(
                                    assistantMsg,
                                    scope.sessionId,
                                    scope.ownerId,
                                    storedCharacterId,
                                ),
                            )
                            db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)?.let { session ->
                                db.sessionDao().upsert(
                                    session.copy(
                                        messageCount =
                                            db.messageDao().getBySession(scope.sessionId, scope.ownerId).first().size,
                                    ),
                                )
                            }
                        }
                        true
                    }
                if (persisted) {
                    replacedIds.forEach { deleteMessageVectors(it) }
                    // 重生覆盖：凡引用被删消息的待发轮次整轮剔除（新回复与改动前一致，不进缓冲）
                    val remainingIds =
                        db.messageDao().getBySession(scope.sessionId, scope.ownerId).first()
                            .mapTo(HashSet()) { it.id }
                    prunePendingExtractionTurns(scope.sessionId, remainingIds)
                }
            }
            is ReplyTailMode.Append -> {
                // 追加场景：纯 INSERT，但慢 LLM 期间会话可能已被删——锁内复查会话仍在才落库
                persisted =
                    SessionMutationLocks.withSessionLock(scope.sessionId) {
                        val sessionNow = db.sessionDao().getById(scope.sessionId)
                        if (sessionNow == null || sessionNow.ownerId != scope.ownerId) {
                            return@withSessionLock false
                        }
                        db.messageDao().upsert(
                            MessageEntity.fromDomain(
                                assistantMsg,
                                scope.sessionId,
                                scope.ownerId,
                                storedCharacterId,
                            ),
                        )
                        true
                    }
            }
        }

        // 会话已删除（慢 LLM 期间）→ 不落库、不向量化、不计数：返回消息仅供 UI 展示已流式内容
        if (persisted) {
            finalizePersistedReply(scope, mode, assistantMsg, storedCharacterId)
            TavernEventBus.emit(
                TavernEvent.MessageReceived(scope.ownerId, scope.characterId, scope.sessionId, assistantMsg.id),
            )
        }

        return assistantMsg
    }

    /**
     * 回复成功落库后的收尾（从 persistAssistantReply 抽出以收敛其圈复杂度）：
     * 向量化 AI 回复 + 会话计数回写 + 记忆抽取攒批。
     * 正文/思考直接取 [assistantMsg]（构建时即 storedContent/replyThinking，逐字节一致）。
     */
    private suspend fun finalizePersistedReply(
        scope: SessionScope,
        mode: ReplyTailMode,
        assistantMsg: Message,
        storedCharacterId: String,
    ) {
        // 3.5. 向量化 AI 回复（异步，不阻塞主流程）——群聊归属实际说话角色
        storeAssistantMessageVector(
            content = assistantMsg.content,
            ownerId = scope.ownerId,
            characterId = storedCharacterId,
            sessionId = scope.sessionId,
            messageId = assistantMsg.id,
        )

        val updatedSession = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
        if (updatedSession == null) return
        when (mode) {
            is ReplyTailMode.Append -> {
                // 追加场景：计数 +1 并触发记忆提取
                val sessionAfterReply =
                    updatedSession.copy(
                        messageCount = updatedSession.messageCount + 1,
                        updatedAt = assistantMsg.createdAt,
                    )
                db.sessionDao().upsert(sessionAfterReply)

                // 记忆抽取攒批：本轮入缓冲，满 PendingExtractionStore.BATCH_TURNS 轮合并为一次调用
                // （开场白已经在创建会话时插入，不参与这里的逻辑）。
                // 群聊 MVP：记忆提取仍归属主角色 characterId（按说话者 witness 分账是后续增强）
                enqueueMemoryExtraction(
                    // 全局默认记忆开关已删除：会话显式值优先，未设置即为关
                    // （与 PromptBuilder 的注入判断同一解析器，防双源真相）
                    enabled =
                        ChatSettingsResolver.longTermMemoryEnabled(
                            sessionAfterReply,
                            false,
                        ),
                    providerId = sessionAfterReply.providerId,
                    userMessage = mode.userMessageForMemory,
                    assistantMessage = assistantMsg.content,
                    ownerId = scope.ownerId,
                    characterId = scope.characterId,
                    sessionId = scope.sessionId,
                    messageIds = listOfNotNull(mode.userMessageId, assistantMsg.id),
                    // 入队快照：各消息正文（content + thinking）的内容签名，
                    // 锁内保存前逐轮核对"未被编辑/重生成篡改"（见 turnContentMatches）
                    messageSignatures =
                        listOfNotNull(
                            mode.userMessageId?.let {
                                messageContentSignature(mode.userMessageForMemory, null)
                            },
                            messageContentSignature(assistantMsg.content, assistantMsg.thinking),
                        ),
                )
            }
            is ReplyTailMode.Replace -> {
                // 替换场景：计数已在替换事务内按真实行数回写，仅刷新 updatedAt
                db.sessionDao().upsert(updatedSession.copy(updatedAt = assistantMsg.createdAt))
            }
        }
    }

    // 群聊上下文兜底构建：VM 已传 groupContext 时直接使用；未传时按会话 mode + 参与者列表查角色名。
    // 非群聊会话（mode != group 或会话不存在）返回 null，classic 路径零影响。
    private suspend fun loadGroupContext(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): GroupChatContext? {
        val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return null
        if (session.mode != SESSION_MODE_GROUP) return null
        val speakerNames =
            session
                .participantCharacterIds()
                .mapNotNull { id -> db.characterDao().getById(id)?.let { id to it.name } }
                .toMap()
        return GroupChatContext(speakerNames = speakerNames)
    }

    // F2.1 回复清洗：提取全部 <think>…</think> 块，多段以空行合并为思考内容；
    // 正文为去除全部 think 块后 trim 的结果。无有效思考时 thinking 为 null。
    private fun splitThinking(reply: String): Pair<String, String?> {
        val thinking =
            Regex("(?s)<think>([\\s\\S]*?)</think>").findAll(reply)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        val content = reply.replace(Regex("(?s)<think>[\\s\\S]*?</think>"), "").trim()
        return content to thinking.ifBlank { null }
    }

    // 统一走 SettingsRepository.getLlmConfig：此前此处另读一套旧键，导致 llm_* 覆盖、
    // 采样预设、超时与重试设置对聊天请求不生效（双源真相 bug）
    // sessionId 非空时应用会话级覆盖：会话在聊天设置里指定的 API 配置
    // （providerId → api_configs；modelId 压过配置默认模型）优先于全局默认配置
    private suspend fun loadLlmConfig(sessionId: String? = null): LlmConfig {
        val base = settingsRepo.getLlmConfig()
        val session = sessionId?.let { db.sessionDao().getById(it) }
        val apiConfig =
            session?.providerId
                ?.takeIf { it.isNotBlank() }
                ?.let { db.apiConfigDao().getById(it) }
        return if (apiConfig != null) {
            val selected = settingsRepo.getLlmConfig(apiConfig)
            selected.copy(model = session.modelId.ifBlank { selected.model })
        } else {
            base
        }
    }

    /**
     * 会话覆盖后的生效配置（internal：ChatViewModel 的"请求参数"诊断展示需与真实请求同源，
     * 不经此入口拿配置会显示全局默认值，与会话实际使用的模型不一致）
     */
    internal suspend fun effectiveLlmConfig(sessionId: String?): LlmConfig = loadLlmConfig(sessionId)

    /**
     * 消息内容签名：content + thinking 的 SHA-256（hex）。
     *
     * 抽取批次在入队时快照每轮的来源消息签名；LLM 抽取完成、进保存临界区前逐轮核对——
     * 消息被编辑/换 swipe/重生成覆盖（同 id 内容变）或删除（id 消失）都会使轮次失效。
     */
    private fun messageContentSignature(
        content: String,
        thinking: String?,
    ): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((content + "\u0000" + (thinking ?: "")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 抽取批次的版本核对（锁内调用）：
     * - 轮内每条消息必须仍存在；
     * - 入队快照带签名时，每条消息的内容签名必须与快照一致（防"同 id 内容已变"）；
     * - 签名缺失/错位（旧格式持久化数据）退化为只查存在并记 warning。
     *
     * 回退/重生/编辑路径（regenerateMessage、换 swipe 等）删除或替换消息后，已启动抽取
     * 适用同一规则：失配的轮次整轮作废，不信任被污染上下文产出的候选。
     */
    private fun turnContentMatches(
        turn: PendingExtractionTurn,
        liveMessages: Map<String, MessageEntity>,
    ): Boolean {
        if (turn.messageIds.isEmpty()) return true
        val allPresent = turn.messageIds.all { it in liveMessages }
        val signaturesUsable =
            turn.messageSignatures.isNotEmpty() && turn.messageSignatures.size == turn.messageIds.size
        if (!signaturesUsable) {
            android.util.Log.w(
                "ChatRepository",
                "抽取轮签名缺失或错位（旧格式数据），仅校验消息存在性: turnIds=${turn.messageIds.size}",
            )
        }
        // 消息缺失 → 整轮失效；签名缺失/错位（旧格式数据）退化为只查存在；否则逐条比对签名
        val signaturesMatch =
            !signaturesUsable ||
                turn.messageIds.withIndex().all { (index, id) ->
                    val message = liveMessages.getValue(id)
                    messageContentSignature(message.content, message.thinking) ==
                        turn.messageSignatures[index]
                }
        return allPresent && signaturesMatch
    }

    /**
     * 一轮回复入抽取缓冲：攒满 PendingExtractionStore.BATCH_TURNS 轮后排空并合并发起一次后台抽取。
     * 不满一批的尾巴靠 flushPendingMemoryExtractions 兜底（切会话/退出聊天页时调用）。
     *
     * 参数即一轮抽取入队的全部维度（消息签名快照是本轮新增的版本复核维度），
     * 收对象只会把同一批字段在调用方再拼一遍，豁免 LongParameterList。
     */
    @Suppress("LongParameterList")
    private fun enqueueMemoryExtraction(
        enabled: Boolean,
        providerId: String,
        userMessage: String,
        assistantMessage: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
        messageSignatures: List<String>,
    ) {
        if (!enabled) {
            // 长期记忆被关掉：丢弃该会话的待发缓冲——已停用的功能不应在 flush 时又抽一批
            android.util.Log.d("ChatRepository", "Long-term memory disabled for this session")
            pendingExtractionStore.discard(sessionId)
            return
        }

        val batch =
            pendingExtractionStore.enqueue(
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                providerId = providerId,
                turn = PendingExtractionTurn(userMessage, assistantMessage, messageIds, messageSignatures),
            )
        if (batch != null) {
            launchMemoryExtraction(batch)
        } else {
            android.util.Log.d("ChatRepository", "Memory extraction buffered: session=$sessionId")
        }
    }

    /**
     * 排空全部会话的抽取缓冲（不足一批的尾巴也立即抽取）。
     * fire-and-forget：抽取走独立 backgroundScope，可在 ViewModel.onCleared 等不可挂起的时机调用；
     * 缓冲已落盘，进程被强杀也只会丢"最后一次入队"的那一轮（原文仍在消息库）。
     */
    fun flushPendingMemoryExtractions() {
        pendingExtractionStore.drainAll().forEach { launchMemoryExtraction(it) }
    }

    /** 会话被删除/清空时丢弃其缓冲：源消息已不存在，抽出结果的 relatedMessageIds 只会指向悬空 id */
    fun discardPendingMemoryExtractions(sessionId: String) {
        pendingExtractionStore.discard(sessionId)
    }

    /**
     * 消息被撤销/回退/重生覆盖后修剪缓冲：凡引用了已不存在消息的待发轮次整轮剔除——
     * 缺了另一半的对话抽出来也是失真记忆（regenerate 与改动前一致，新回复不进缓冲）。
     */
    private fun prunePendingExtractionTurns(
        sessionId: String,
        remainingMessageIds: Set<String>,
    ) {
        pendingExtractionStore.prune(sessionId, remainingMessageIds)
    }

    /**
     * 启动一批记忆抽取。extract/save 两阶段 + 会话锁 + 版本复核：
     *
     * 1. 抽取（慢，锁外）：LLM 调用纯计算，持锁会卡住删除路径数十秒；
     * 2. 保存（快，锁内）：与 deleteSession/clearConversation 同一把会话锁互斥——
     *    锁内复查会话仍存在，并逐轮核对"消息存在且内容与入队签名一致"；
     *    会话已删 / 轮次全部失效 → **ack 丢弃**（不是 nack：nack 会让已删会话的批次无限重试）；
     *    部分轮次失效 → 整批候选作废，对仍有效轮次重新抽取（锁外），再走一遍本协议；
     * 3. 向量收尾（锁外）：重要性达标的记忆出锁后同步向量摘要（慢 embedding 不占锁，
     *    写前复查源记忆/会话仍在）。
     * 保存失败仍走 nack（网络/磁盘类错误可重试）。
     */
    private fun launchMemoryExtraction(batch: PendingExtractionBatch) {
        backgroundScope.launch {
            try {
                val precheck = precheckExtractionBatch(batch)
                if (precheck !is BatchPrecheck.Proceed) return@launch

                var turnsToExtract = precheck.turns
                var candidates =
                    extractBatchCandidates(batch, turnsToExtract, precheck.config)
                        ?: return@launch

                // 版本复核循环：锁内逐轮核对"消息存在且内容与入队快照一致"。任何一轮失效 →
                // 整批候选作废（不信任被污染上下文产出的记忆），对仍有效轮次重新抽取（锁外），
                // 再走一遍同一互斥协议。每轮循环至少剔除 1 个失效轮次，循环次数有界。
                while (true) {
                    val attempt = saveExtractionBatchUnderLock(batch, turnsToExtract, candidates)

                    // 出锁后：慢 embedding 不占用会话锁；runVectorFollowUps 写前会复查
                    // 源记忆/会话仍存在（复查失败放弃——向量是可重建投影，Room 才是权威）
                    structuredMemoryRepo.runVectorFollowUps(attempt.vectorFollowUps)

                    val nextTurns = attempt.reextractTurns ?: return@launch
                    turnsToExtract = nextTurns
                    candidates =
                        extractBatchCandidates(batch, turnsToExtract, precheck.config)
                            ?: return@launch
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "Memory extraction failed: ${e.message}", e)
                pendingExtractionStore.nack(batch.batchId)
            }
        }
    }

    /** 批次前置筛选结果：[Proceed] 继续抽取；[Acked]/[Nacked] 表示批次已被处置，调用方直接结束 */
    private sealed interface BatchPrecheck {
        class Proceed(
            val turns: List<PendingExtractionTurn>,
            val config: LlmConfig,
        ) : BatchPrecheck

        data object Acked : BatchPrecheck

        data object Nacked : BatchPrecheck
    }

    /**
     * 锁外的粗筛：会话已删 / 无有效轮次 → ack；配置暂缺 → nack（保留批次待重试）。
     * 删除/回滚可能发生在批次排队之后，只把仍存在的消息轮次交给 LLM，
     * 避免已删除会话在重启后重新生成长期记忆。
     */
    private suspend fun precheckExtractionBatch(batch: PendingExtractionBatch): BatchPrecheck {
        val sessionExists = db.sessionDao().getById(batch.sessionId) != null
        val remainingMessageIds =
            db.messageDao()
                .getBySession(batch.sessionId, batch.ownerId)
                .first()
                .mapTo(mutableSetOf()) { it.id }
        val validTurns =
            batch.turns.filter { turn ->
                turn.messageIds.isEmpty() || turn.messageIds.all { it in remainingMessageIds }
            }
        val config = resolveExtractionConfig(batch.providerId)
        return when {
            !sessionExists -> {
                // 批次出队后源会话已被删除：消息已经不存在，抽出来的记忆只会指向悬空 messageId
                android.util.Log.d("ChatRepository", "会话 ${batch.sessionId} 已删除，跳过本批记忆抽取")
                pendingExtractionStore.ack(batch.batchId)
                BatchPrecheck.Acked
            }
            validTurns.isEmpty() -> {
                pendingExtractionStore.ack(batch.batchId)
                BatchPrecheck.Acked
            }
            config == null -> {
                // 配置暂不可用时保留批次，稍后 flush/重启可以重试。
                pendingExtractionStore.nack(batch.batchId)
                BatchPrecheck.Nacked
            }
            else -> {
                android.util.Log.d(
                    "ChatRepository",
                    "Long-term memory enabled, extracting memories for ${batch.turns.size} turn(s)...",
                )
                BatchPrecheck.Proceed(validTurns, config)
            }
        }
    }

    /** 阶段 1（锁外，慢）：LLM 抽取；空候选 → ack 并返回 null（调用方结束本批） */
    private suspend fun extractBatchCandidates(
        batch: PendingExtractionBatch,
        turnsToExtract: List<PendingExtractionTurn>,
        config: LlmConfig,
    ): List<MemoryExtractionService.MemoryCandidate>? {
        val candidates =
            memoryExtractionService.extractValidatedCandidates(
                turns = turnsToExtract.toDialogueTurns(),
                ownerId = batch.ownerId,
                config = config,
            )
        return if (candidates.isEmpty()) {
            pendingExtractionStore.ack(batch.batchId)
            null
        } else {
            candidates
        }
    }

    /** 阶段 2（锁内，快）的产出：需要重新抽取的轮次（null = 批次已终结）+ 锁外向量收尾 */
    private class SaveAttempt(
        val reextractTurns: List<PendingExtractionTurn>?,
        val vectorFollowUps: List<StructuredMemoryRepository.VectorFollowUp>,
    )

    /**
     * 阶段 2（快，锁内）：与删除/清空互斥——复查会话存在，逐轮核对消息存在且内容签名
     * 与入队快照一致；全部有效才落库（Room 写入在锁内，向量收尾带出锁执行）。
     * 会话已删 / 轮次全部失效 → ack 丢弃（不 nack：会话不存在是终态，nack 会无限重提）；
     * 部分失效 → 返回仍有效轮次，调用方整批候选作废后重新抽取。
     */
    private suspend fun saveExtractionBatchUnderLock(
        batch: PendingExtractionBatch,
        turnsToExtract: List<PendingExtractionTurn>,
        candidates: List<MemoryExtractionService.MemoryCandidate>,
    ): SaveAttempt =
        SessionMutationLocks.withSessionLock(batch.sessionId) {
            val session = db.sessionDao().getById(batch.sessionId)
            if (session == null || session.ownerId != batch.ownerId) {
                android.util.Log.d(
                    "ChatRepository",
                    "抽取完成后会话 ${batch.sessionId} 已删除，丢弃本批 ${candidates.size} 条候选",
                )
                pendingExtractionStore.ack(batch.batchId)
                return@withSessionLock SaveAttempt(null, emptyList())
            }

            // 锁内版本复查：逐轮核对消息存在 + 内容签名与入队快照一致
            val liveMessages =
                db.messageDao()
                    .getBySession(batch.sessionId, batch.ownerId)
                    .first()
                    .associateBy { it.id }
            val freshTurns = turnsToExtract.filter { turnContentMatches(it, liveMessages) }
            when {
                freshTurns.isEmpty() && turnsToExtract.isNotEmpty() -> {
                    android.util.Log.d("ChatRepository", "本批全部轮次在抽取期间被删除/编辑，ack 丢弃")
                    pendingExtractionStore.ack(batch.batchId)
                    SaveAttempt(null, emptyList())
                }
                freshTurns.size < turnsToExtract.size -> {
                    android.util.Log.w(
                        "ChatRepository",
                        "${turnsToExtract.size - freshTurns.size} 轮在抽取期间被删除/编辑，" +
                            "整批 ${candidates.size} 条候选作废，对剩余 ${freshTurns.size} 轮重新抽取",
                    )
                    SaveAttempt(freshTurns, emptyList())
                }
                else -> {
                    val saveResult =
                        memoryExtractionService.saveCandidatesLocked(
                            candidates = candidates,
                            ownerId = batch.ownerId,
                            characterId = batch.characterId,
                            sessionId = batch.sessionId,
                            // 归因只使用实际有效轮次的消息 id（整批共用归因的
                            // 单条事实来源覆盖是已知限制，属后续优化，此处不假装已解决）
                            messageIds = freshTurns.flatMap { it.messageIds }.distinct(),
                        )
                    android.util.Log.d(
                        "ChatRepository",
                        "Memory extraction completed, saved ${saveResult.savedCount} memories",
                    )
                    pendingExtractionStore.ack(batch.batchId)
                    SaveAttempt(null, saveResult.vectorFollowUps)
                }
            }
        }

    /** 缓冲轮次 → 抽取服务的对话轮（纯映射） */
    private fun List<PendingExtractionTurn>.toDialogueTurns() =
        map {
            MemoryExtractionService.DialogueTurn(
                userMessage = it.userMessage,
                assistantMessage = it.assistantMessage,
                messageIds = it.messageIds,
            )
        }

    // 解析记忆抽取用的 LLM 配置：会话指定配置 → api_configs 默认配置 → 活跃 LLM 配置兜底；都没有则返回 null（抽取跳过）
    private suspend fun resolveExtractionConfig(providerId: String): LlmConfig? {
        val dao = db.apiConfigDao()
        val matched = if (providerId.isNotBlank()) dao.getById(providerId) else null
        val fallback = matched ?: dao.getDefault()
        if (fallback != null) {
            android.util.Log.d("ChatRepository", "Memory extraction using config: ${fallback.name}")
            return settingsRepo.getLlmConfig(fallback)
        }

        val llmConfig = loadLlmConfig()
        return if (
            llmConfig.baseUrl.isNotBlank() &&
            llmConfig.apiKey.isNotBlank() &&
            llmConfig.model.isNotBlank()
        ) {
            android.util.Log.i("ChatRepository", "Using active LLM config for memory extraction")
            llmConfig
        } else {
            android.util.Log.w("ChatRepository", "No LLM configs available, memory extraction disabled")
            null
        }
    }
}
