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
import com.mistbell.tavern.android.service.MemoryExtractionService
import com.mistbell.tavern.android.util.SecureStore
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
    private val db get() = TavernApplication.instance.database
    private val api get() = ApiClient.getApi(context)
    private val providerRepo = ProviderRepository(context)
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

            // 1.5. 向量化用户消息（异步，不阻塞主流程）
            storeUserMessageVector(
                content = message,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = msgId,
            )

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
            try {
                val llmConfig = loadLlmConfig()
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
                        )
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
                throw e
            }
        }
    }

    /**
     * 群聊推动（跨代理契约 4）：不插入用户消息，注入系统级推动语让最合适的下一位角色接话。
     * 回复归属解析/落库/向量化/会话计数回写与 sendMessage 共用 persistAssistantReply 公共尾部。
     *
     * @param worldBookId 预留参数（与 sendMessage 一致，实际世界书由 PromptBuilder 按会话/角色解析）
     */
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
                    ?: throw IllegalStateException("会话不存在或不是群聊模式，无法推动群聊")
            var insertedAssistantId: String? = null
            val scope = SessionScope(ownerId, characterId, sessionId)
            val sb = StringBuilder()
            try {
                val llmConfig = loadLlmConfig()
                if (llmConfig.baseUrl.isBlank() || llmConfig.apiKey.isBlank()) {
                    throw Exception("LLM 未配置：请在设置中配置 API 密钥")
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
                // LLM 调用或收尾失败：无用户消息可回滚，仅删除已插入的助手回复并按真实行数重算计数
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
            // 事务保证删除与计数回写原子完成，避免中途失败导致计数漂移
            db.withTransaction {
                val messages = db.messageDao().getBySession(sessionId, ownerId).first()
                if (messages.isNotEmpty()) {
                    db.messageDao().deleteById(messages.last().id)
                    val session = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (session != null) {
                        db.sessionDao().upsert(session.copy(messageCount = messages.size - 1))
                    }
                }
            }
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
                db.messageDao().deleteAfter(sessionId, messageId, ownerId)
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
            val llmConfig = loadLlmConfig()
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
                )
            // SSE 真流式：逐增量收集累计全文，onPartial 每次回调累计全文供 UI 渲染
            val sb = StringBuilder()
            try {
                if (settingsRepo.isStreamingEnabled()) {
                    // 流式开：SSE 真流式，逐增量收集累计全文，onPartial 每次回调累计全文供 UI 渲染
                    LlmClient.chatStream(llmConfig, prompt).collect { delta ->
                        sb.append(delta)
                        onPartial?.invoke(sb.toString())
                    }
                } else {
                    // 流式关：整包返回，不调 onPartial
                    sb.append(LlmClient.chat(llmConfig, prompt))
                }
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
        }
    }

    suspend fun continueMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // Continue message 功能需要 LLM 实现
            // TODO: 实现本地 LLM 的 continue 功能
        }
    }

    suspend fun swipeMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        direction: String,
    ) {
        withContext(Dispatchers.IO) {
            // Swipe message 功能需要本地实现
            // TODO: 实现本地的 swipe 功能
        }
    }

    suspend fun clearConversation(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 会话级删除：群聊 NPC 消息与主角色消息同属一个归属单元，一并清空
            db.messageDao().deleteBySession(sessionId, ownerId)
        }
    }

    suspend fun createSession(
        ownerId: String,
        characterId: String,
        title: String = "",
        providerId: String = "",
        enableLongTermMemory: Boolean = false,
        worldBookId: String = "",
    ): String {
        return withContext(Dispatchers.IO) {
            val sessionId = UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            // 如果没有指定 providerId，尝试获取默认的 provider
            val actualProviderId =
                if (providerId.isBlank()) {
                    providerRepo.observeProviders().first().firstOrNull()?.id ?: ""
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
            // 删除该会话的所有记忆
            db.memoryDao().deleteBySession(ownerId, characterId, sessionId)
            structuredMemoryRepo.deleteMemoriesBySession(ownerId, sessionId)
            db.vectorMemoryDao().deleteBySession(ownerId, sessionId)
            android.util.Log.d("ChatRepository", "Deleted memories for session: $sessionId")

            // 删除消息和会话（会话级删除，含群聊 NPC 消息）
            db.messageDao().deleteBySession(sessionId, ownerId)
            db.sessionDao().delete(sessionId, ownerId, characterId)
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
        if (settingsRepo.isStreamingEnabled()) {
            LlmClient.chatStream(llmConfig, promptMessages).collect { delta ->
                sb.append(delta)
                onPartial?.invoke(sb.toString())
            }
        } else {
            sb.append(LlmClient.chat(llmConfig, promptMessages))
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
     */
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
            content = partialReply
            thinking = null
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
            // 替换场景：删除旧消息与落库部分回复在同一事务内原子完成，计数按真实行数自愈重算
            db.withTransaction {
                db.messageDao().deleteAfter(scope.sessionId, replaceFromMessageId, scope.ownerId)
                db.messageDao().deleteById(replaceFromMessageId)
                db.messageDao().upsert(
                    MessageEntity.fromDomain(partialMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                )
                val session = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
                if (session != null) {
                    db.sessionDao().upsert(
                        session.copy(
                            messageCount =
                                db.messageDao().getBySession(scope.sessionId, scope.ownerId).first().size,
                        ),
                    )
                }
            }
        } else {
            db.messageDao().upsert(
                MessageEntity.fromDomain(partialMsg, scope.sessionId, scope.ownerId, storedCharacterId),
            )
            val sessionAfterPartial = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
            if (sessionAfterPartial != null) {
                db.sessionDao().upsert(
                    sessionAfterPartial.copy(
                        messageCount = sessionAfterPartial.messageCount + 1,
                        updatedAt = partialMsg.createdAt,
                    ),
                )
            }
        }
    }

    /**
     * sendMessage / continueGroupChat / regenerateMessage 共用的回复落库公共尾部（避免复制粘贴）：
     * 1) 回复清洗（think 块提取）；2) 群聊归属解析（resolveReplyAttribution）；3) 助手消息落库；
     * 4) 向量化；5) 会话计数回写；6) 记忆提取（按 mode 裁剪，群聊 MVP 仍归属主角色）。
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

        when (mode) {
            is ReplyTailMode.Replace -> {
                // 替换场景：删除旧消息、落库新回复、按真实行数回写计数在同一事务内原子完成
                // （自愈式计数，不依赖增量加减）；失败回滚语义由调用方保证——删除只发生在新回复已到手之后
                db.withTransaction {
                    db.messageDao().deleteAfter(scope.sessionId, mode.fromMessageId, scope.ownerId)
                    db.messageDao().deleteById(mode.fromMessageId)
                    db.messageDao().upsert(
                        MessageEntity.fromDomain(assistantMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                    )
                    val session = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                messageCount =
                                    db.messageDao().getBySession(scope.sessionId, scope.ownerId).first().size,
                            ),
                        )
                    }
                }
            }
            is ReplyTailMode.Append -> {
                db.messageDao().upsert(
                    MessageEntity.fromDomain(assistantMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                )
            }
        }

        // 3.5. 向量化 AI 回复（异步，不阻塞主流程）——群聊归属实际说话角色
        storeAssistantMessageVector(
            content = storedContent,
            ownerId = scope.ownerId,
            characterId = storedCharacterId,
            sessionId = scope.sessionId,
            messageId = assistantMsg.id,
        )

        val updatedSession = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
        if (updatedSession != null) {
            when (mode) {
                is ReplyTailMode.Append -> {
                    // 追加场景：计数 +1 并触发记忆提取
                    val sessionAfterReply =
                        updatedSession.copy(
                            messageCount = updatedSession.messageCount + 1,
                            updatedAt = assistantMsg.createdAt,
                        )
                    db.sessionDao().upsert(sessionAfterReply)

                    // 每一轮对话都提取记忆（开场白已经在创建会话时插入，不参与这里的逻辑）。
                    // 群聊 MVP：记忆提取仍归属主角色 characterId（按说话者 witness 分账是后续增强）
                    extractMemoriesInBackground(
                        enabled = sessionAfterReply.enableLongTermMemory,
                        providerId = sessionAfterReply.providerId,
                        userMessage = mode.userMessageForMemory,
                        assistantMessage = storedContent,
                        ownerId = scope.ownerId,
                        characterId = scope.characterId,
                        sessionId = scope.sessionId,
                        messageIds = listOfNotNull(mode.userMessageId, assistantMsg.id),
                    )
                }
                is ReplyTailMode.Replace -> {
                    // 替换场景：计数已在替换事务内按真实行数回写，仅刷新 updatedAt
                    db.sessionDao().upsert(updatedSession.copy(updatedAt = assistantMsg.createdAt))
                }
            }
        }

        return assistantMsg
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

    private suspend fun loadLlmConfig(): LlmConfig {
        val settingsDao = db.settingsDao()
        return LlmConfig(
            baseUrl = settingsDao.getValue("llm_base_url") ?: "",
            apiKey = SecureStore.unwrap(settingsDao.getValue("llm_api_key") ?: ""),
            model = settingsDao.getValue("llm_model") ?: "",
            temperature = settingsDao.getValue("temperature")?.toDoubleOrNull() ?: 0.8,
            maxTokens = settingsDao.getValue("max_tokens")?.toIntOrNull() ?: 1024,
        )
    }

    private fun extractMemoriesInBackground(
        enabled: Boolean,
        providerId: String,
        userMessage: String,
        assistantMessage: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
    ) {
        if (!enabled) {
            android.util.Log.d("ChatRepository", "Long-term memory disabled for this session")
            return
        }

        backgroundScope.launch {
            try {
                android.util.Log.d("ChatRepository", "Long-term memory enabled, extracting memories...")

                val providers = providerRepo.observeProviders().first()
                android.util.Log.d("ChatRepository", "Available providers: ${providers.map { "${it.id}:${it.name}" }}")
                android.util.Log.d("ChatRepository", "Looking for providerId: '$providerId'")

                var provider = providers.find { it.id == providerId }

                // 如果找不到，尝试使用第一个可用的 provider
                if (provider == null) {
                    android.util.Log.w("ChatRepository", "Provider '$providerId' not found")
                    provider = providers.firstOrNull()

                    if (provider != null) {
                        android.util.Log.i("ChatRepository", "Falling back to first available provider: ${provider.name}")
                    } else {
                        val llmConfig = loadLlmConfig()
                        provider =
                            if (
                                llmConfig.baseUrl.isNotBlank() &&
                                llmConfig.apiKey.isNotBlank() &&
                                llmConfig.model.isNotBlank()
                            ) {
                                android.util.Log.i("ChatRepository", "Using active LLM config for memory extraction")
                                ProviderConfig(
                                    id = "active_llm_config",
                                    name = "Active LLM Config",
                                    endpoint = llmConfig.baseUrl,
                                    apiKey = llmConfig.apiKey,
                                    selectedModel = llmConfig.model,
                                    memoryModel = llmConfig.model,
                                )
                            } else {
                                android.util.Log.w("ChatRepository", "No LLM providers configured, memory extraction disabled")
                                null
                            }
                    }
                }

                val savedCount =
                    memoryExtractionService.extractAndSaveMemories(
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        ownerId = ownerId,
                        characterId = characterId,
                        sessionId = sessionId,
                        messageIds = messageIds,
                        provider = provider,
                    )
                android.util.Log.d("ChatRepository", "Memory extraction completed, saved $savedCount memories")
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "Memory extraction failed: ${e.message}", e)
            }
        }
    }
}
