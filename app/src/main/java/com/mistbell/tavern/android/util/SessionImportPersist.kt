package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** 会话导入的结果（两条既有文案的载体；两个调用方各自选提示通道） */
sealed interface SessionImportOutcome {
    /** 成功：summary 与既有成功文案逐字节一致 */
    data class Success(
        val sessionTitle: String,
        val messageCount: Int,
    ) : SessionImportOutcome {
        val summary: String
            get() = "导入成功：$sessionTitle\n包含 $messageCount 条消息"
    }

    /** 失败：reason 与既有失败文案逐字节一致（解析失败 / 找不到角色） */
    data class Failure(val reason: String) : SessionImportOutcome
}

/**
 * 聊天记录（会话导出文件）的共享导入落库核心（P3 导入层：聊天 Tab 与「导入」页共用一份，
 * 不复制逻辑）。从 ChatListViewModel 的导入分支原样提取，行为不变：
 * - [SessionImporter.importFromAny] 自动识别 JSON 包 / JSONL；
 * - 角色按 id 或名字匹配（找不到即失败，提示先导入对应角色卡）；
 * - 会话与消息生成全新 id 落库，群聊逐条解析说话者归属。
 */
object SessionImportPersist {
    /** 与 ChatListViewModel 等处保持一致的会话所有者 */
    private const val OWNER_ID = "local-user"

    /** 会话 / 消息 id 的随机后缀上界（与提取前完全一致） */
    private const val RANDOM_SUFFIX_UPPER = 9999

    /** 导入会话的落库目标（消参：三条标量合成一个语义单元） */
    private data class ImportTarget(
        val characterId: String,
        val sessionId: String,
        val now: String,
    )

    /** 三段既有失败文案的早退守卫链，原样保留（解析失败 → 找不到角色 → 成功） */
    @Suppress("ReturnCount")
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        db: AppDatabase,
    ): SessionImportOutcome {
        // 1. 解析 JSON 文件
        val exportData =
            SessionImporter.importFromAny(context, uri)
                ?: return SessionImportOutcome.Failure("无法解析导入文件，请检查文件格式")

        // 2. 检查角色是否存在
        val characterKey =
            exportData.session.characterId?.takeIf { it.isNotBlank() }
                ?: exportData.messages.firstOrNull { it.role == "assistant" }?.characterId.orEmpty()
        val character =
            findImportCharacter(db, characterKey)
                ?: return SessionImportOutcome.Failure(
                    "导入失败：找不到角色 ${characterKey.ifBlank { "ID 未提供" }}，请先导入对应角色卡",
                )
        val knownCharacters = db.characterDao().getAll().first()

        // 3. 生成新的会话 ID（避免冲突）
        val target =
            ImportTarget(
                characterId = character.id,
                sessionId = "session-${System.currentTimeMillis()}-${(0..RANDOM_SUFFIX_UPPER).random()}",
                now = nowUtc(),
            )

        // 4. 创建并插入会话
        val sessionEntity = createImportedSession(exportData, target)
        db.sessionDao().upsert(sessionEntity)

        // 5. 插入消息（生成新的消息 ID）
        importMessages(db, exportData.messages, knownCharacters, target)

        // 6. 成功结果
        return SessionImportOutcome.Success(exportData.session.title, exportData.messages.size)
    }

    private fun nowUtc(): String = java.time.Instant.now().toString()

    private suspend fun findImportCharacter(
        db: AppDatabase,
        characterKey: String,
    ): CharacterEntity? =
        db.characterDao().getById(characterKey)
            ?: db.characterDao().getAll().first().firstOrNull {
                it.name.equals(characterKey, ignoreCase = true)
            }

    private fun createImportedSession(
        exportData: SessionExportData,
        target: ImportTarget,
    ): SessionEntity =
        SessionEntity(
            id = target.sessionId,
            ownerId = OWNER_ID,
            characterId = target.characterId,
            title = exportData.session.title + " (导入)",
            createdAt = target.now,
            updatedAt = target.now,
            // 使用默认提供商与模型
            messageCount = exportData.messages.size,
            providerId = "",
            modelId = "",
            worldBookId = "",
            summaryJson = "",
            unreadCount = 0,
            isPinned = false,
            pinnedAt = null,
            isMuted = false,
            enableLongTermMemory = null,
            contextTokenLimit = null,
            participantCharacterIdsJson = "",
            themeId = "",
            authorNote = "",
            mode = exportData.session.mode,
            modeConfigJson = "",
        )

    private suspend fun importMessages(
        db: AppDatabase,
        messages: List<Message>,
        knownCharacters: List<CharacterEntity>,
        target: ImportTarget,
    ) {
        val json = Json
        val stringListSerializer = ListSerializer(serializer<String>())
        messages.forEach { message ->
            val newMessageId = "msg-${System.currentTimeMillis()}-${(0..RANDOM_SUFFIX_UPPER).random()}"
            val messageCharacterId =
                resolveImportedCharacterId(message, knownCharacters, target.characterId)
            val swipesJson = encodeImportStringList(message.swipes, json, stringListSerializer)
            val thinkingSwipesJson = encodeImportStringList(message.thinkingSwipes, json, stringListSerializer)
            val memoryIdsJson = encodeImportStringList(message.memoryIds, json, stringListSerializer)
            val messageEntity =
                MessageEntity(
                    id = newMessageId,
                    ownerId = OWNER_ID,
                    // Preserve per-message speaker attribution for group chats;
                    // classic/legacy exports without it fall back to the session character.
                    characterId = messageCharacterId,
                    sessionId = target.sessionId,
                    role = message.role,
                    content = message.content,
                    thinking = message.thinking,
                    createdAt = message.createdAt.ifBlank { target.now },
                    memoryIdsJson = memoryIdsJson,
                    swipesJson = swipesJson,
                    swipeIndex = message.swipeIndex,
                    thinkingSwipesJson = thinkingSwipesJson,
                    isRead = true,
                )
            db.messageDao().upsert(messageEntity)
        }
    }

    private fun resolveImportedCharacterId(
        message: Message,
        knownCharacters: List<CharacterEntity>,
        fallbackCharacterId: String,
    ): String =
        if (message.role == "assistant" && message.characterId.isNotBlank()) {
            knownCharacters.firstOrNull {
                it.id == message.characterId || it.name.equals(message.characterId, ignoreCase = true)
            }?.id ?: fallbackCharacterId
        } else {
            fallbackCharacterId
        }

    private fun encodeImportStringList(
        values: List<String>?,
        json: Json,
        serializer: KSerializer<List<String>>,
    ): String =
        if (values != null && values.isNotEmpty()) {
            json.encodeToString(serializer, values)
        } else {
            ""
        }
}
