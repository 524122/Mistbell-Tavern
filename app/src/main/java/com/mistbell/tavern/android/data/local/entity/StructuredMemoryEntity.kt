package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "structured_memory",
    indices = [
        Index(value = ["owner_id", "character_id"]),
        Index(value = ["memory_type"]),
        Index(value = ["importance"]),
        Index(value = ["created_at"]),
        Index(value = ["owner_id", "importance", "created_at"]),
        Index(value = ["owner_id", "session_id", "created_at"]),
    ],
)
data class StructuredMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "character_id")
    val characterId: String?,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    // 记忆分类: character_info, event, relationship, item, location, fact
    @ColumnInfo(name = "memory_type")
    val memoryType: String,
    // 记忆内容
    val title: String?,
    val content: String,
    @ColumnInfo(name = "structured_data")
    val structuredData: String?, // JSON string
    // 元数据
    val importance: Int = 5, // 1-10
    val tags: String?, // JSON array
    val keywords: String?, // JSON array
    // 时间信息
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: String?,
    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,
    // 关联信息
    @ColumnInfo(name = "related_message_ids")
    val relatedMessageIds: String?, // JSON array
    @ColumnInfo(name = "source_type")
    val sourceType: String = "manual", // manual, auto_extract, import
) {
    fun toDomain(): StructuredMemory {
        return StructuredMemory(
            id = id,
            ownerId = ownerId,
            characterId = characterId,
            sessionId = sessionId,
            memoryType = memoryType,
            title = title,
            content = content,
            structuredData = structuredData,
            importance = importance,
            tags = tags?.let { parseJsonArray(it) } ?: emptyList(),
            keywords = keywords?.let { parseJsonArray(it) } ?: emptyList(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastAccessedAt = lastAccessedAt,
            accessCount = accessCount,
            relatedMessageIds = relatedMessageIds?.let { parseJsonArray(it) } ?: emptyList(),
            sourceType = sourceType,
        )
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            Json.decodeFromString<List<String>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun fromDomain(memory: StructuredMemory): StructuredMemoryEntity {
            return StructuredMemoryEntity(
                id = memory.id,
                ownerId = memory.ownerId,
                characterId = memory.characterId,
                sessionId = memory.sessionId,
                memoryType = memory.memoryType,
                title = memory.title,
                content = memory.content,
                structuredData = memory.structuredData,
                importance = memory.importance,
                tags =
                    memory.tags.takeIf { it.isNotEmpty() }?.let {
                        Json.encodeToString(
                            ListSerializer(String.serializer()),
                            it,
                        )
                    },
                keywords =
                    memory.keywords.takeIf { it.isNotEmpty() }?.let {
                        Json.encodeToString(
                            ListSerializer(String.serializer()),
                            it,
                        )
                    },
                createdAt = memory.createdAt,
                updatedAt = memory.updatedAt,
                lastAccessedAt = memory.lastAccessedAt,
                accessCount = memory.accessCount,
                relatedMessageIds =
                    memory.relatedMessageIds.takeIf { it.isNotEmpty() }?.let {
                        Json.encodeToString(
                            ListSerializer(String.serializer()),
                            it,
                        )
                    },
                sourceType = memory.sourceType,
            )
        }
    }
}
