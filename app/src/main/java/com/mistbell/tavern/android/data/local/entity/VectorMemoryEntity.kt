package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.VectorMemory
import kotlinx.serialization.Serializable

/**
 * 向量记忆的**元数据**表（遗留）。
 *
 * 注意：本表不存向量本身（只有 [vectorId] 等引用字段），**无法重建向量索引**。
 * 向量召回的权威来源是 `vector_store.json`（[com.mistbell.tavern.android.data.vector.InMemoryVectorStore]），
 * 运行时写入/检索/删除都只走那里。本表定义保留只为 schema 稳定（删表要走迁移，风险大于收益），
 * 不再作为任何一份向量数据的来源，也不再进入备份。
 */
@Entity(
    tableName = "vector_memory",
    indices = [
        Index(value = ["owner_id", "character_id", "session_id"]),
        Index(value = ["message_id"]),
        Index(value = ["vector_id"]),
        Index(value = ["created_at"]),
    ],
)
@Serializable
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
    // user_message, ai_message, summary
    @ColumnInfo(name = "content_type")
    val contentType: String,
    // 向量数据引用
    // Chroma中的ID
    @ColumnInfo(name = "vector_id")
    val vectorId: String?,
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
