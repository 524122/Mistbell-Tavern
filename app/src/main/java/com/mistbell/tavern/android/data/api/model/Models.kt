package com.mistbell.tavern.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StateResponse(
    val characters: List<Character> = emptyList(),
    @SerialName("activeSessionId") val activeSessionId: String = "",
    val sessions: List<SessionSummary> = emptyList(),
    @SerialName("recentSessions") val recentSessions: List<SessionSummary> = emptyList(),
    val conversation: List<Message> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val worldBook: WorldBook? = null
)

@Serializable
data class Character(
    val id: String = "",
    val name: String = "",
    val role: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    val color: String = "",
    @SerialName("avatarData") val avatarData: String = "",
    @SerialName("worldBookId") val worldBookId: String = "",
    val data: CharacterData? = null
)

@Serializable
data class CharacterData(
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "1.0"
)

@Serializable
data class SessionSummary(
    val id: String = "",
    val title: String = "",
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("updatedAt") val updatedAt: String = "",
    @SerialName("messageCount") val messageCount: Int = 0,
    @SerialName("characterId") val characterId: String? = null,
    @SerialName("characterName") val characterName: String? = null
)

@Serializable
data class Message(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val thinking: String? = null,
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("memoryIds") val memoryIds: List<String>? = null,
    val swipes: List<String>? = null,
    @SerialName("swipeIndex") val swipeIndex: Int = 0
)

@Serializable
data class Memory(
    val id: String = "",
    val content: String = "",
    val type: String = "",
    val layer: String = "",
    val subject: String = "",
    val relation: String = "",
    val `object`: String = "",
    val importance: Double = 0.5,
    val stability: Double = 1.0,
    val status: String = "active",
    @SerialName("accessCount") val accessCount: Int = 0,
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList()
)

@Serializable
data class WorldBook(
    val id: String = "",
    val name: String = "",
    val entries: List<WorldBookEntry> = emptyList(),
    val books: List<WorldBook> = emptyList()
)

@Serializable
data class WorldBookEntry(
    val id: String = "",
    val comment: String = "",
    val key: List<String> = emptyList(),
    val content: String = "",
    val constant: Boolean = false,
    val disable: Boolean = false,
    val order: Int = 100,
    @SerialName("insertPosition") val insertPosition: String = "before_prompt",
    val depth: Int = 1
)

@Serializable
data class ProviderConfig(
    val id: String = "",
    val name: String = "",
    val type: String = "openai",
    val endpoint: String = "",
    @SerialName("apiKey") val apiKey: String = "",
    val models: List<String> = emptyList(),
    @SerialName("selectedModel") val selectedModel: String = "",
    @SerialName("embeddingModel") val embeddingModel: String = "",
    @SerialName("summaryModel") val summaryModel: String = "",
    @SerialName("memoryModel") val memoryModel: String = "",
    @SerialName("customParams") val customParams: Map<String, String> = emptyMap(),
    @SerialName("context1M") val context1M: Boolean = false
)

@Serializable
data class SessionConfig(
    @SerialName("providerId") val providerId: String = "",
    @SerialName("modelId") val modelId: String = "",
    @SerialName("worldBookId") val worldBookId: String = ""
)
