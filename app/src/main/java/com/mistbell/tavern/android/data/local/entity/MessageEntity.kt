package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.Message

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["session_id", "created_at"]),
        Index(value = ["session_id", "owner_id", "character_id"]),
        // F3 词法召回性能索引（v13 迁移创建；v14 起声明于实体——Room 校验要求索引全集合相等，
        // 未声明的额外索引同样会触发 "Migration didn't properly handle"）
        Index(value = ["owner_id", "character_id", "created_at"]),
        // 消息窗口分页索引（v16 迁移创建）：getLatestBySession/getOlderBySession 的
        // 等值过滤 + created_at 排序扫描，避免长会话分页查询退化为全表排序
        Index(value = ["session_id", "owner_id", "character_id", "created_at"]),
        // 会话列表"最后一条消息"按 owner 维度聚合的排序索引
        Index(value = ["owner_id", "session_id", "created_at"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    val role: String,
    val content: String,
    val thinking: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "memory_ids_json") val memoryIdsJson: String,
    @ColumnInfo(name = "swipes_json") val swipesJson: String,
    @ColumnInfo(name = "swipe_index") val swipeIndex: Int,
    @ColumnInfo(name = "thinking_swipes_json") val thinkingSwipesJson: String,
    @ColumnInfo(name = "is_read", defaultValue = "1") val isRead: Boolean = true,
) {
    fun toDomain(): Message {
        val memoryIds =
            try {
                if (memoryIdsJson.isNotBlank()) {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(memoryIdsJson)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

        val swipes =
            try {
                if (swipesJson.isNotBlank()) {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(swipesJson)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

        return Message(
            id = id,
            role = role,
            content = content,
            thinking = thinking,
            createdAt = createdAt,
            memoryIds = memoryIds,
            swipes = swipes,
            swipeIndex = swipeIndex,
        )
    }

    companion object {
        fun fromDomain(
            m: Message,
            sessionId: String,
            ownerId: String,
            characterId: String,
        ): MessageEntity {
            val json = kotlinx.serialization.json.Json
            val stringListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
            val memIds = m.memoryIds?.let { json.encodeToString(stringListSerializer, it) } ?: ""
            val swipes = m.swipes?.let { json.encodeToString(stringListSerializer, it) } ?: ""
            return MessageEntity(
                id = m.id,
                sessionId = sessionId,
                ownerId = ownerId,
                characterId = characterId,
                role = m.role,
                content = m.content,
                thinking = m.thinking,
                createdAt = m.createdAt,
                memoryIdsJson = memIds,
                swipesJson = swipes,
                swipeIndex = m.swipeIndex,
                thinkingSwipesJson = "",
            )
        }
    }
}
