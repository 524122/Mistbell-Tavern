package com.mistbell.tavern.android.data.api.model

data class VectorMemory(
    val id: Long = 0,
    val ownerId: String,
    val characterId: String,
    val sessionId: String,
    val messageId: String?,
    val content: String,
    val contentType: String,
    val vectorId: String?, // Chroma vector ID
    val embeddingModel: String = "text-embedding-3-small",
    val importanceScore: Float = 1.0f,
    val tokenCount: Int?,
    val createdAt: String
) {
    companion object {
        // 内容类型
        object ContentType {
            const val USER_MESSAGE = "user_message"
            const val AI_MESSAGE = "ai_message"
            const val SUMMARY = "summary"

            fun getDescription(type: String): String = when (type) {
                USER_MESSAGE -> "用户消息"
                AI_MESSAGE -> "AI回复"
                SUMMARY -> "摘要"
                else -> "未知"
            }

            fun all() = listOf(
                USER_MESSAGE,
                AI_MESSAGE,
                SUMMARY
            )
        }
    }
}
