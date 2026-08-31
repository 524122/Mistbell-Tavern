package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.mistbell.tavern.android.data.api.model.SessionSummary

@Entity(
    tableName = "sessions",
    primaryKeys = ["id", "owner_id", "character_id"],
    indices = [
        Index(value = ["owner_id", "updated_at"]),
        Index(value = ["owner_id", "is_pinned", "updated_at"]),
        Index(value = ["owner_id", "character_id", "updated_at"])
    ]
)
data class SessionEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "world_book_id") val worldBookId: String,
    @ColumnInfo(name = "summary_json") val summaryJson: String,
    @ColumnInfo(name = "unread_count", defaultValue = "0") val unreadCount: Int = 0,
    @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(name = "pinned_at") val pinnedAt: String? = null,
    @ColumnInfo(name = "is_muted", defaultValue = "0") val isMuted: Boolean = false,
    @ColumnInfo(name = "enable_long_term_memory", defaultValue = "0") val enableLongTermMemory: Boolean = false,
    @ColumnInfo(name = "context_token_limit", defaultValue = "4096") val contextTokenLimit: Int = 4096,
    @ColumnInfo(name = "participant_character_ids_json", defaultValue = "") val participantCharacterIdsJson: String = "",
    // 会话级主题包 id：空 = 跟随角色 / 全局；迁移 DDL 的 DEFAULT '' 必须与此一致（见 AppDatabase.MIGRATION_10_11）
    @ColumnInfo(name = "theme_id", defaultValue = "") val themeId: String = "",
    // 会话附加指令（作者注释）：非空时经宏渲染注入到历史之后、最终用户消息之前；
    // 迁移 DDL 的 DEFAULT '' 必须与此 defaultValue 一致（见 AppDatabase.MIGRATION_11_12）
    @ColumnInfo(name = "author_note", defaultValue = "") val authorNote: String = ""
) {
    fun participantCharacterIds(): List<String> {
        val decoded = try {
            if (participantCharacterIdsJson.isNotBlank()) {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(participantCharacterIdsJson)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        return (decoded.ifEmpty { listOf(characterId) })
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PARTICIPANT_CHARACTERS)
            .ifEmpty { listOf(characterId) }
    }

    fun toDomain(): SessionSummary = SessionSummary(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messageCount = messageCount,
        characterId = characterId,
        characterName = null
    )

    companion object {
        private const val MAX_PARTICIPANT_CHARACTERS = 4

        fun encodeParticipantCharacterIds(ids: Collection<String>): String {
            val normalized = ids
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_PARTICIPANT_CHARACTERS)
            val serializer = kotlinx.serialization.builtins.ListSerializer(
                kotlinx.serialization.serializer<String>()
            )
            return kotlinx.serialization.json.Json.encodeToString(serializer, normalized)
        }

        fun fromDomain(s: SessionSummary, ownerId: String, characterId: String): SessionEntity {
            return SessionEntity(
                id = s.id,
                ownerId = ownerId,
                characterId = characterId,
                title = s.title,
                createdAt = s.createdAt,
                updatedAt = s.updatedAt,
                messageCount = s.messageCount,
                providerId = "",
                modelId = "",
                worldBookId = "",
                summaryJson = ""
            )
        }
    }
}
