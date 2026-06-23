package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.vector.VectorStore
import java.time.Instant
import kotlinx.coroutines.flow.first

object PromptBuilder {

    suspend fun buildPrompt(
        db: AppDatabase,
        ownerId: String,
        characterId: String,
        sessionId: String,
        userMessage: String
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        val session = db.sessionDao().get(sessionId, ownerId, characterId)
        val contextTokenLimit = session?.contextTokenLimit?.coerceIn(1024, 1_000_000) ?: 4096
        val participantCharacterIds = session?.participantCharacterIds() ?: listOf(characterId)
        val participantCharacters = participantCharacterIds
            .mapNotNull { db.characterDao().getById(it) }
            .ifEmpty { db.characterDao().getById(characterId)?.let { listOf(it) } ?: emptyList() }
        val character = participantCharacters.firstOrNull() ?: db.characterDao().getById(characterId)
        if (character != null) {
            val systemParts = mutableListOf<String>()
            if (participantCharacters.size > 1) {
                systemParts.add(
                    "This is a multi-character chat. Primary speaker: ${character.name}. " +
                        "Other selected characters may participate when appropriate: " +
                        participantCharacters.drop(1).joinToString(", ") { it.name } + "."
                )
            }
            participantCharacters.forEachIndexed { index, participant ->
                val roleLabel = if (index == 0) "Primary character" else "Participant character"
                val characterParts = mutableListOf<String>()
                characterParts.add("$roleLabel: ${participant.name}")
                if (participant.description.isNotBlank()) characterParts.add(participant.description)
                if (participant.personality.isNotBlank()) characterParts.add("Personality: ${participant.personality}")
                if (participant.scenario.isNotBlank()) characterParts.add("Scenario: ${participant.scenario}")
                if (participant.dataJson.isNotBlank()) {
                    try {
                        val charData =
                            kotlinx.serialization.json.Json.decodeFromString<com.mistbell.tavern.android.data.api.model.CharacterData>(
                                participant.dataJson
                            )
                        if (charData.systemPrompt.isNotBlank()) characterParts.add(1, charData.systemPrompt)
                    } catch (_: Exception) {
                    }
                }
                systemParts.add(characterParts.joinToString("\n"))
            }
            if (systemParts.isNotEmpty()) {
                messages.add(ChatMessage(role = "system", content = systemParts.joinToString("\n\n")))
            }
        }

        if (session?.enableLongTermMemory == true) {
            val memories = db.structuredMemoryDao()
                .getByCharacter(ownerId, characterId)
                .first()
                .map { it.toDomain() }

            val recalledMemories = selectRelevantMemories(memories, userMessage)
            if (recalledMemories.isNotEmpty()) {
                val accessedAt = Instant.now().toString()
                recalledMemories.forEach { memory ->
                    if (memory.id > 0) {
                        db.structuredMemoryDao().incrementAccessCount(memory.id, accessedAt)
                    }
                }
                messages.add(
                    ChatMessage(
                        role = "system",
                        content = "## Known Information\n${formatStructuredMemoryContext(recalledMemories)}"
                    )
                )
            }

            // 向量记忆检索
            try {
                val vectorMemoryService = TavernApplication.instance.vectorMemoryService
                val vectorResults = vectorMemoryService.searchRelevantMemories(
                    query = userMessage,
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = sessionId,
                    topK = 5
                )

                if (vectorResults.isNotEmpty()) {
                    val vectorContext = buildVectorMemoryContextForPrompt(vectorResults)
                    if (vectorContext.isNotBlank()) {
                        messages.add(
                            ChatMessage(
                                role = "system",
                                content = vectorContext
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // 向量检索失败不应阻塞对话
                android.util.Log.e("PromptBuilder", "Vector memory search failed: ${e.message}", e)
            }
        }

        // 会话级世界书优先；其次回退到角色卡默认；最后回退到全局 "main"。
        val worldBookId = session?.worldBookId?.takeIf { it.isNotBlank() }
            ?: character?.worldBookId?.takeIf { it.isNotBlank() }
            ?: "main"
        val entries = db.worldBookDao().getEntriesList(worldBookId)
        val constantEntries = entries.filter { it.constant && !it.disable }
        if (constantEntries.isNotEmpty()) {
            val worldContent = constantEntries.joinToString("\n\n") { "[${it.comment}] ${it.content}" }
            messages.add(ChatMessage(role = "system", content = "World Info:\n$worldContent"))
        }

        val activatedEntries = entries.filter { entry ->
            !entry.constant && !entry.disable && entry.toDomain().key.any { keyword ->
                userMessage.contains(keyword, ignoreCase = true)
            }
        }
        if (activatedEntries.isNotEmpty()) {
            val activatedContent = activatedEntries.joinToString("\n\n") { "[${it.comment}] ${it.content}" }
            messages.add(ChatMessage(role = "system", content = "Activated World Info:\n$activatedContent"))
        }

        val recentMessages = db.messageDao().getBySession(sessionId, ownerId, characterId).first()
        val history: List<MessageEntity> = selectHistoryWithinBudget(
            recentMessages = recentMessages,
            currentMessages = messages,
            currentUserMessage = userMessage,
            contextTokenLimit = contextTokenLimit
        )
        history.forEach { msg: MessageEntity ->
            messages.add(ChatMessage(role = msg.role, content = msg.content))
        }

        messages.add(ChatMessage(role = "user", content = userMessage))

        return messages
    }

    private fun selectHistoryWithinBudget(
        recentMessages: List<MessageEntity>,
        currentMessages: List<ChatMessage>,
        currentUserMessage: String,
        contextTokenLimit: Int
    ): List<MessageEntity> {
        val reservedForReply = 768
        val fixedTokens = currentMessages.sumOf { estimateTokens(it.content) }
        val currentUserTokens = estimateTokens(currentUserMessage)
        val historyBudget = (contextTokenLimit - fixedTokens - currentUserTokens - reservedForReply)
            .coerceAtLeast(256)

        val selected = ArrayDeque<MessageEntity>()
        var usedTokens = 0

        recentMessages.asReversed().forEach { message ->
            val messageTokens = estimateTokens(message.content) + 4
            if (selected.isNotEmpty() && usedTokens + messageTokens > historyBudget) {
                return@forEach
            }
            if (selected.isEmpty() || usedTokens + messageTokens <= historyBudget) {
                selected.addFirst(message)
                usedTokens += messageTokens
            }
        }

        return selected.toList()
    }

    private fun selectRelevantMemories(
        memories: List<StructuredMemory>,
        userMessage: String
    ): List<StructuredMemory> {
        if (memories.isEmpty()) return emptyList()

        val result = linkedMapOf<Long, StructuredMemory>()

        memories
            .filter { it.importance >= 8 }
            .sortedWith(memoryComparator())
            .take(5)
            .forEach { result[it.stableKey()] = it }

        memories
            .filter { it.stableKey() !in result }
            .filter { it.importance >= 6 && it.memoryType.lowercase() in profileMemoryTypes }
            .sortedWith(memoryComparator())
            .take(3)
            .forEach { result[it.stableKey()] = it }

        val keywordTokens = extractQueryTokens(userMessage)
        if (keywordTokens.isNotEmpty()) {
            memories
                .filter { it.stableKey() !in result }
                .mapNotNull { memory ->
                    val score = keywordMatchScore(memory, keywordTokens)
                    if (score > 0) memory to score else null
                }
                .sortedWith(
                    compareByDescending<Pair<StructuredMemory, Int>> { it.second }
                        .thenByDescending { it.first.importance }
                        .thenByDescending { it.first.updatedAt }
                )
                .take(3)
                .forEach { result[it.first.stableKey()] = it.first }
        }

        return result.values
            .sortedWith(memoryComparator())
            .take(10)
    }

    private fun formatStructuredMemoryContext(memories: List<StructuredMemory>): String {
        val groups = linkedMapOf(
            "用户信息" to setOf("character_info", "identity", "preference"),
            "关系" to setOf("relationship"),
            "重要事件" to setOf("event", "core", "goal"),
            "情绪与边界" to setOf("emotion"),
            "相关物品" to setOf("item"),
            "相关地点" to setOf("location")
        )

        val usedKeys = mutableSetOf<Long>()
        val lines = mutableListOf<String>()

        groups.forEach { (label, types) ->
            val items = memories.filter { it.memoryType.lowercase() in types }
            if (items.isNotEmpty()) {
                lines.add("$label：")
                items.forEach { memory ->
                    usedKeys.add(memory.stableKey())
                    lines.add("  - ${memory.content}（重要度：${memory.importance}/10）")
                }
                lines.add("")
            }
        }

        val facts = memories.filter { it.stableKey() !in usedKeys }
        if (facts.isNotEmpty()) {
            lines.add("其他事实：")
            facts.forEach { memory ->
                lines.add("  - ${memory.content}（重要度：${memory.importance}/10）")
            }
        }

        return lines.joinToString("\n").trim()
    }

    private fun keywordMatchScore(memory: StructuredMemory, queryTokens: Set<String>): Int {
        val haystack = buildString {
            append(memory.title.orEmpty()).append(' ')
            append(memory.content).append(' ')
            append(memory.tags.joinToString(" ")).append(' ')
            append(memory.keywords.joinToString(" "))
        }.lowercase()

        var score = 0
        queryTokens.forEach { token ->
            if (token.length >= 2 && haystack.contains(token)) score += 1
        }

        memory.tags.forEach { tag ->
            val normalized = tag.lowercase().trim()
            if (normalized.length >= 2 && queryTokens.any { it.contains(normalized) || normalized.contains(it) }) {
                score += 2
            }
        }
        memory.keywords.forEach { keyword ->
            val normalized = keyword.lowercase().trim()
            if (normalized.length >= 2 && queryTokens.any { it.contains(normalized) || normalized.contains(it) }) {
                score += 2
            }
        }

        return score
    }

    private fun extractQueryTokens(text: String): Set<String> {
        val tokens = linkedSetOf<String>()
        Regex("""[\u4e00-\u9fff]{2,}|[a-z0-9_]{3,}""").findAll(text.lowercase()).forEach { match ->
            val value = match.value.trim()
            if (value.length in 2..24) tokens.add(value)
            if (value.any { it.isCjk() } && value.length > 4) {
                value.windowed(2).forEach { tokens.add(it) }
                value.windowed(3).forEach { tokens.add(it) }
            }
        }
        return tokens
    }

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var ascii = 0
        var nonAscii = 0
        text.forEach { char ->
            if (char.code <= 127) ascii++ else nonAscii++
        }
        return maxOf(1, kotlin.math.ceil(ascii / 4.0 + nonAscii / 1.6).toInt())
    }

    private fun memoryComparator(): Comparator<StructuredMemory> =
        compareByDescending<StructuredMemory> { it.importance }
            .thenByDescending { it.accessCount }
            .thenByDescending { it.updatedAt }

    private fun StructuredMemory.stableKey(): Long =
        if (id > 0) id else content.hashCode().toLong()

    private fun Char.isCjk(): Boolean = this in '\u4e00'..'\u9fff'

    private val profileMemoryTypes = setOf(
        "character_info",
        "identity",
        "preference",
        "relationship"
    )

    /**
     * 构建向量记忆上下文（用于 Prompt 注入）
     */
    private fun buildVectorMemoryContextForPrompt(results: List<VectorStore.SearchResult>): String {
        val relevantResults = results.filter { it.score > 0.5 }
        if (relevantResults.isEmpty()) return ""

        return buildString {
            appendLine("## Relevant Past Conversations")
            relevantResults.forEachIndexed { index, result ->
                val similarityPercent = (result.score * 100).toInt()
                appendLine("${index + 1}. ${result.content} (similarity: ${similarityPercent}%)")
            }
        }
    }
}
