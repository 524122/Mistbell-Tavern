package com.mistbell.tavern.android.service

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.local.entity.MemoryEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.service.models.*

/**
 * 本地提示词构建服务
 *
 * 移植自后端 PromptService.java
 * 负责将角色、消息、记忆、世界书等信息组合成完整的系统提示词
 */
class LocalPromptService(private val context: Context) {

    companion object {
        private const val SESSION_SUMMARY_MAX_INPUT_CHARS = 14000
        private const val SESSION_SUMMARY_TARGET_CHARS = 2200
    }

    /**
     * 构建提示词段落
     *
     * @param character 角色信息
     * @param recentMessages 最近的消息历史
     * @param memories 相关记忆列表
     * @param activatedEntries 激活的世界书条目
     * @param sessionSummary 会话总结（可选）
     * @return 提示词段落对象
     */
    fun buildPromptSections(
        character: Character,
        recentMessages: List<Message>,
        memories: List<MemoryEntity> = emptyList(),
        activatedEntries: List<ActivatedEntry> = emptyList(),
        sessionSummary: String? = null
    ): PromptSections {
        return PromptSections(
            mainPrompt = buildMainPrompt(character),
            worldInfoBefore = buildWorldInfoByPosition(activatedEntries, "before"),
            characterDescription = character.description,
            characterPersonality = character.personality,
            scenario = character.scenario,
            sessionSummary = sessionSummary ?: "",
            structuredMemoryContext = "",  // TODO: 实现结构化记忆
            memoryContext = buildMemoryContext(memories),
            vectorMemoryContext = "",  // TODO: 实现向量记忆
            worldInfoAfter = buildWorldInfoByPosition(activatedEntries, "after"),
            exampleBefore = buildWorldInfoByPosition(activatedEntries, "exampleBefore"),
            exampleDialogue = character.mesExample,
            exampleAfter = buildWorldInfoByPosition(activatedEntries, "exampleAfter"),
            authorsNoteTop = buildWorldInfoByPosition(activatedEntries, "authorTop"),
            authorsNoteBottom = buildWorldInfoByPosition(activatedEntries, "authorBottom"),
            postHistory = character.data?.postHistoryInstructions ?: "",
            depthEntries = buildDepthEntries(activatedEntries),
            recentMessages = recentMessages,
            activatedWorldInfo = activatedEntries
        )
    }

    /**
     * 构建聊天消息列表
     *
     * @param sections 提示词段落
     * @param includePostHistory 是否包含后置指令
     * @return 聊天消息列表
     */
    fun buildChatMessages(
        sections: PromptSections,
        includePostHistory: Boolean = true
    ): List<LocalChatMessage> {
        val messages = mutableListOf<LocalChatMessage>()

        // 系统消息
        val systemPrompt = sections.composeSystemPrompt()
        messages.add(LocalChatMessage(role = "system", content = systemPrompt))

        // 插入带深度注入的消息
        val messagesWithDepth = insertDepthEntries(sections.recentMessages, sections.depthEntries)
        messages.addAll(messagesWithDepth.map { msg ->
            LocalChatMessage(role = msg.role, content = msg.content)
        })

        // 后置指令
        if (includePostHistory && sections.postHistory.isNotBlank()) {
            messages.add(
                LocalChatMessage(
                    role = "system",
                    content = "## Post-History Instructions\n${sections.postHistory}"
                )
            )
        }

        return messages
    }

    /**
     * 构建主提示词
     */
    private fun buildMainPrompt(character: Character): String {
        val systemPrompt = character.data?.systemPrompt
        return if (!systemPrompt.isNullOrBlank()) {
            systemPrompt
        } else {
            """
            你正在扮演 ${character.name}。
            请保持角色一致性，根据角色设定、性格和场景进行回复。
            不要打破角色设定或提及你是 AI。
            """.trimIndent()
        }
    }

    /**
     * 构建记忆上下文
     */
    private fun buildMemoryContext(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return ""

        return buildString {
            appendLine("## 相关记忆")
            memories.forEach { memory ->
                appendLine("- ${memory.content}")
            }
        }
    }

    /**
     * 按位置构建世界书内容
     */
    private fun buildWorldInfoByPosition(
        entries: List<ActivatedEntry>,
        position: String
    ): String {
        val filtered = entries.filter { it.position == position && it.enabled }
            .sortedBy { it.order }

        if (filtered.isEmpty()) return ""

        return buildString {
            filtered.forEach { entry ->
                if (entry.comment.isNotBlank()) {
                    appendLine("## ${entry.comment}")
                }
                appendLine(entry.content)
                appendLine()
            }
        }
    }

    /**
     * 构建深度注入条目
     */
    private fun buildDepthEntries(entries: List<ActivatedEntry>): List<DepthEntry> {
        return entries
            .filter { it.depth != null && it.depth > 0 && it.enabled }
            .map { DepthEntry(depth = it.depth!!, content = it.content) }
            .sortedBy { it.depth }
    }

    /**
     * 将深度注入条目插入到消息历史中
     */
    private fun insertDepthEntries(
        messages: List<Message>,
        depthEntries: List<DepthEntry>
    ): List<Message> {
        if (depthEntries.isEmpty()) return messages

        val result = mutableListOf<Message>()
        val depthMap = depthEntries.groupBy { it.depth }

        messages.forEachIndexed { index, message ->
            // 从后往前数的深度
            val depthFromEnd = messages.size - index

            // 插入该深度的条目
            depthMap[depthFromEnd]?.forEach { entry ->
                result.add(
                    Message(
                        id = "depth_${depthFromEnd}",
                        role = "system",
                        content = entry.content,
                        createdAt = message.createdAt
                    )
                )
            }

            result.add(message)
        }

        return result
    }

    /**
     * 估算 token 数量
     *
     * 粗略估计：
     * - 中文：约 1.5 字符/token
     * - 英文：约 4 字符/token
     */
    fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it.code in 0x4E00..0x9FA5 }
        val otherChars = text.length - chineseChars
        return (chineseChars / 1.5 + otherChars / 4.0).toInt()
    }

    /**
     * 生成提示词审计报告
     */
    fun generatePromptAudit(
        sections: PromptSections,
        finalMessages: List<LocalChatMessage>
    ): PromptAudit {
        val sectionAudits = mutableListOf<SectionAudit>()
        var stableTokens = 0
        var dynamicTokens = 0

        // 审计各个段落
        fun auditSection(name: String, label: String, type: String, content: String) {
            val chars = content.length
            val tokens = estimateTokens(content)
            val included = content.isNotBlank()

            sectionAudits.add(
                SectionAudit(
                    name = name,
                    label = label,
                    type = type,
                    chars = chars,
                    estimatedTokens = tokens,
                    included = included
                )
            )

            if (included) {
                if (type == "stable") stableTokens += tokens else dynamicTokens += tokens
            }
        }

        // 稳定段落
        auditSection("mainPrompt", "主提示词", "stable", sections.mainPrompt)
        auditSection("characterDescription", "角色描述", "stable", sections.characterDescription)
        auditSection("characterPersonality", "角色性格", "stable", sections.characterPersonality)
        auditSection("scenario", "场景", "stable", sections.scenario)
        auditSection("exampleDialogue", "示例对话", "stable", sections.exampleDialogue)
        auditSection("postHistory", "后置指令", "stable", sections.postHistory)

        // 动态段落
        auditSection("worldInfoBefore", "世界书前置", "dynamic", sections.worldInfoBefore)
        auditSection("worldInfoAfter", "世界书后置", "dynamic", sections.worldInfoAfter)
        auditSection("sessionSummary", "会话总结", "dynamic", sections.sessionSummary)
        auditSection("memoryContext", "长期记忆", "dynamic", sections.memoryContext)

        // 审计消息
        val messageAudits = finalMessages.mapIndexed { index, message ->
            val chars = message.content.length
            val tokens = estimateTokens(message.content)
            MessageAudit(
                index = index,
                role = message.role,
                chars = chars,
                estimatedTokens = tokens,
                dynamic = message.role != "system"
            )
        }

        val messageDynamicTokens = messageAudits
            .filter { it.dynamic }
            .sumOf { it.estimatedTokens }

        return PromptAudit(
            sections = sectionAudits,
            messages = messageAudits,
            stableTokens = stableTokens,
            dynamicTokens = dynamicTokens + messageDynamicTokens,
            totalTokens = stableTokens + dynamicTokens + messageDynamicTokens
        )
    }
}
