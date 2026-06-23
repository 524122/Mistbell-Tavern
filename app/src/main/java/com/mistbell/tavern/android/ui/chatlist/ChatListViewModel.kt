package com.mistbell.tavern.android.ui.chatlist

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.api.model.SessionSummary
import com.mistbell.tavern.android.util.SessionExportFormat
import com.mistbell.tavern.android.util.SessionExportResult
import com.mistbell.tavern.android.util.SessionExporter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class ChatListItem(
    val sessionId: String,
    val characterId: String,
    val sessionTitle: String,
    val characterName: String,
    val characterColor: String,
    val characterAvatarData: String? = null,
    val participantCharacters: List<Character> = emptyList(),
    val lastMessage: String,
    val lastMessageTime: String,
    val unreadCount: Int = 0,
    val isOnline: Boolean = false,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val lastMessageSender: String = ""
) {
    // 使用 equals/hashCode 优化，避免不必要的重组
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChatListItem
        // 只比较影响显示的关键字段
        return sessionId == other.sessionId &&
                lastMessage == other.lastMessage &&
                lastMessageTime == other.lastMessageTime &&
                unreadCount == other.unreadCount &&
                isPinned == other.isPinned &&
                isMuted == other.isMuted
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + lastMessage.hashCode()
        result = 31 * result + lastMessageTime.hashCode()
        result = 31 * result + unreadCount
        result = 31 * result + isPinned.hashCode()
        result = 31 * result + isMuted.hashCode()
        return result
    }
}

class ChatListViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.database
    private val ownerId = "local-user"

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedSessions = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    val selectedSessions: StateFlow<Set<Pair<String, String>>> = _selectedSessions.asStateFlow()

    // 缓存时间戳格式化结果，避免重复计算
    private val timestampCache = mutableMapOf<String, String>()

    val chatListItems: StateFlow<List<ChatListItem>> = combine(
        db.sessionDao().getRecent("local-user"),
        db.characterDao().getAll(),
        _searchQuery
    ) { sessions, characters, query ->
        val characterMap = characters.associateBy { it.id }

        // 批量获取所有会话的最后消息（修复 N+1 查询）
        val lastMessages = try {
            db.messageDao().getLatestMessagesByOwner(ownerId)
                .associateBy { it.sessionId }
        } catch (e: Exception) {
            emptyMap()
        }

        val items = sessions.mapNotNull { session ->
            val character = characterMap[session.characterId] ?: return@mapNotNull null
            val participantCharacters = session.participantCharacterIds()
                .mapNotNull { characterMap[it]?.toDomain() }
                .ifEmpty { listOf(character.toDomain()) }

            // 从批量查询结果中获取最后消息
            val lastMsg = lastMessages[session.id]
            val lastMessage = lastMsg?.content?.take(50) ?: ""
            val lastMessageRole = lastMsg?.role ?: ""

            // Filter by search query
            if (query.isNotBlank() &&
                !character.name.contains(query, ignoreCase = true) &&
                !lastMessage.contains(query, ignoreCase = true)) {
                return@mapNotNull null
            }

            // Determine sender label
            val senderLabel = when (lastMessageRole) {
                "user" -> "我"
                "assistant" -> character.name
                else -> ""
            }

            ChatListItem(
                sessionId = session.id,
                characterId = character.id,
                sessionTitle = session.title,
                characterName = character.name,
                characterColor = character.color,
                characterAvatarData = character.avatarData,
                participantCharacters = participantCharacters,
                lastMessage = lastMessage.ifBlank { session.title },
                lastMessageTime = timestampCache.getOrPut(session.updatedAt) {
                    formatTimestamp(session.updatedAt)
                },
                unreadCount = session.unreadCount,
                isOnline = false,
                isPinned = session.isPinned,
                isMuted = session.isMuted,
                lastMessageSender = senderLabel
            )
        }

        // If no sessions, return sample data for preview
        if (items.isEmpty() && query.isBlank()) {
            emptyList()
        } else {
            items
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun navigateToChat(sessionId: String, characterId: String) {
        // Will be handled by navigation callback
    }

    private fun formatTimestamp(timestamp: String): String {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = dateFormat.parse(timestamp) ?: return "未知"

            val now = Calendar.getInstance()
            val messageTime = Calendar.getInstance().apply { time = date }

            val diffMillis = now.timeInMillis - messageTime.timeInMillis
            val diffMinutes = diffMillis / (60 * 1000)
            val diffHours = diffMillis / (60 * 60 * 1000)
            val diffDays = diffMillis / (24 * 60 * 60 * 1000)

            when {
                diffMinutes < 1 -> "刚刚"
                diffMinutes < 60 -> "${diffMinutes}分钟前"
                diffHours < 24 && now.get(Calendar.DAY_OF_YEAR) == messageTime.get(Calendar.DAY_OF_YEAR) -> {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
                }
                diffDays == 1L -> "昨天"
                diffDays < 7 -> {
                    val weekDays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
                    weekDays[messageTime.get(Calendar.DAY_OF_WEEK) - 1]
                }
                else -> SimpleDateFormat("M/d", Locale.getDefault()).format(date)
            }
        } catch (e: Exception) {
            "未知"
        }
    }

    fun togglePin(sessionId: String, characterId: String) {
        viewModelScope.launch {
            val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return@launch
            val newPinned = !session.isPinned
            val pinnedAt = if (newPinned) nowUtc() else null
            db.sessionDao().updatePinned(sessionId, ownerId, characterId, newPinned, pinnedAt)
        }
    }

    fun toggleMute(sessionId: String, characterId: String) {
        viewModelScope.launch {
            val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return@launch
            db.sessionDao().updateMuted(sessionId, ownerId, characterId, !session.isMuted)
        }
    }

    fun markAsRead(sessionId: String, characterId: String) {
        viewModelScope.launch {
            db.messageDao().markAsRead(sessionId, ownerId, characterId)
            db.sessionDao().updateUnreadCount(sessionId, ownerId, characterId, 0)
        }
    }

    fun renameSession(sessionId: String, characterId: String, title: String) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return

        viewModelScope.launch {
            db.sessionDao().updateTitle(sessionId, ownerId, characterId, trimmedTitle, nowUtc())
        }
    }

    fun deleteSession(sessionId: String, characterId: String) {
        viewModelScope.launch {
            deleteSessionData(sessionId, characterId)
        }
    }

    private suspend fun deleteSessionData(sessionId: String, characterId: String) {
        db.memoryDao().deleteBySession(ownerId, characterId, sessionId)
        db.structuredMemoryDao().deleteBySession(ownerId, sessionId)
        db.vectorMemoryDao().deleteBySession(ownerId, sessionId)
        db.messageDao().deleteBySession(sessionId, ownerId, characterId)
        db.sessionDao().delete(sessionId, ownerId, characterId)
    }

    fun deleteAllSessions() {
        viewModelScope.launch {
            db.memoryDao().deleteAll()
            db.structuredMemoryDao().deleteAll()
            db.vectorMemoryDao().deleteAll()
            db.messageDao().deleteAll()
            db.sessionDao().deleteAll()
        }
    }

    fun copySession(context: Context, sessionId: String, characterId: String) {
        viewModelScope.launch {
            try {
                val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return@launch
                val characterName = db.characterDao().getById(characterId)?.name ?: "AI"
                val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
                val title = session.title.ifBlank { characterName }
                val content = buildString {
                    appendLine(title)
                    appendLine()
                    messages.forEach { message ->
                        val speaker = when (message.role) {
                            "user" -> "我"
                            "assistant" -> characterName
                            else -> message.role.ifBlank { "消息" }
                        }
                        appendLine("$speaker: ${message.content}")
                        if (!message.thinking.isNullOrBlank()) {
                            appendLine("思考: ${message.thinking}")
                        }
                        appendLine()
                    }
                }.trim()

                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText(title, content))
                Toast.makeText(context, "已复制会话", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportSession(
        context: Context,
        sessionId: String,
        characterId: String,
        format: SessionExportFormat,
        fileName: String,
        onComplete: (SessionExportResult?) -> Unit
    ) {
        viewModelScope.launch {
            onComplete(exportSessionFile(context, sessionId, characterId, format, fileName))
        }
    }

    private suspend fun exportSessionFile(
        context: Context,
        sessionId: String,
        characterId: String,
        format: SessionExportFormat,
        fileName: String = SessionExporter.buildFileName("session", sessionId, format.extension)
    ): SessionExportResult? {
        return try {
            val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return null
            val messages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
            val characterName = db.characterDao().getById(characterId)?.name ?: "AI"

            val sessionSummary = SessionSummary(
                id = session.id,
                title = session.title.ifBlank { characterName },
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                messageCount = messages.size,
                characterId = session.characterId,
                characterName = null
            )

            val apiMessages = messages.map { msg ->
                Message(
                    id = msg.id,
                    role = msg.role,
                    content = msg.content,
                    thinking = msg.thinking,
                    createdAt = msg.createdAt,
                    memoryIds = try {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(msg.memoryIdsJson)
                    } catch (e: Exception) { null },
                    swipes = try {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(msg.swipesJson)
                    } catch (e: Exception) { null },
                    swipeIndex = msg.swipeIndex
                )
            }

            SessionExporter.exportToJson(
                context = context,
                session = sessionSummary,
                messages = apiMessages,
                fileName = fileName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun enterMultiSelectMode() {
        _isMultiSelectMode.value = true
        _selectedSessions.value = emptySet()
    }

    fun exitMultiSelectMode() {
        _isMultiSelectMode.value = false
        _selectedSessions.value = emptySet()
    }

    fun toggleSessionSelection(sessionId: String, characterId: String) {
        val key = Pair(sessionId, characterId)
        _selectedSessions.value = if (_selectedSessions.value.contains(key)) {
            _selectedSessions.value - key
        } else {
            _selectedSessions.value + key
        }
    }

    fun selectAllSessions() {
        val allKeys = chatListItems.value.map { Pair(it.sessionId, it.characterId) }.toSet()
        _selectedSessions.value = allKeys
    }

    fun deleteSelectedSessions() {
        viewModelScope.launch {
            _selectedSessions.value.forEach { (sessionId, characterId) ->
                deleteSessionData(sessionId, characterId)
            }
            exitMultiSelectMode()
        }
    }

    fun exportSelectedSessions(context: android.content.Context, onComplete: (List<android.net.Uri>) -> Unit) {
        viewModelScope.launch {
            val uris = mutableListOf<android.net.Uri>()
            _selectedSessions.value.forEach { (sessionId, characterId) ->
                exportSessionFile(context, sessionId, characterId, SessionExportFormat.JSON)?.let { uris.add(it.uri) }
            }
            onComplete(uris)
            exitMultiSelectMode()
        }
    }

    private fun nowUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
