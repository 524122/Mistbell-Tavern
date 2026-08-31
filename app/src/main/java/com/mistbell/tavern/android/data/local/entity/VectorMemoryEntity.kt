package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.VectorMemory

@Entity(
    tableName = "vector_memory",
    indices = [
        Index(value = ["owner_id", "character_id", "session_id"]),
        Index(value = ["message_id"]),
        Index(value = ["vector_id"]),
        Index(value = ["created_at"]),
    ],
)
data class VectorMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    @ColumnInfo(name = "character_id")
    val characterId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    // 内容
    val content: String,
    @ColumnInfo(name = "content_type")
    val contentType: String, // user_message, ai_message, summary
    // 向量数据引用
    @ColumnInfo(name = "vector_id")
    val vectorId: String?, // Chroma中的ID
    @ColumnInfo(name = "embedding_model")
    val embeddingModel: String = "text-embedding-3-small",
    // 元数据
    @ColumnInfo(name = "importance_score")
    val importanceScore: Float = 1.0f,
    @ColumnInfo(name = "token_count")
    val tokenCount: Int?,
    // 时间信息
    @ColumnInfo(name = "created_at")
    val createdAt: String,
) {
    fun toDomain(): VectorMemory {
        return VectorMemory(
            id = id,
            ownerId = ownerId,
            characterId = characterId,
            sessionId = sessionId,
            messageId = messageId,
            content = content,
            contentType = contentType,
            vectorId = vectorId,
            embeddingModel = embeddingModel,
            importanceScore = importanceScore,
            tokenCount = tokenCount,
            createdAt = createdAt,
        )
    }

    companion object {
        fun fromDomain(memory: VectorMemory): VectorMemoryEntity {
            return VectorMemoryEntity(
                id = memory.id,
                ownerId = memory.ownerId,
                characterId = memory.characterId,
                sessionId = memory.sessionId,
                messageId = memory.messageId,
                content = memory.content,
                contentType = memory.contentType,
                vectorId = memory.vectorId,
                embeddingModel = memory.embeddingModel,
                importanceScore = memory.importanceScore,
                tokenCount = memory.tokenCount,
                createdAt = memory.createdAt,
            )
        }
    }
}
