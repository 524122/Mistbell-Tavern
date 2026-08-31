package com.mistbell.tavern.android.service.models

import com.mistbell.tavern.android.data.api.model.Message

/**
 * 提示词段落
 * 对应后端 PromptService.buildPromptSections 的返回结果
 */
data class PromptSections(
    val mainPrompt: String,
    val worldInfoBefore: String,
    val characterDescription: String,
    val characterPersonality: String,
    val scenario: String,
    val sessionSummary: String,
    val structuredMemoryContext: String,
    val memoryContext: String,
    val vectorMemoryContext: String,
    val worldInfoAfter: String,
    val exampleBefore: String,
    val exampleDialogue: String,
    val exampleAfter: String,
    val authorsNoteTop: String,
    val authorsNoteBottom: String,
    val postHistory: String,
    val depthEntries: List<DepthEntry>,
    val recentMessages: List<Message>,
    val activatedWorldInfo: List<ActivatedEntry>,
) {
    /**
     * 组合最终的系统提示词
     */
    fun composeSystemPrompt(): String {
        return buildString {
            // 主提示词
            if (mainPrompt.isNotBlank()) {
                appendLine(mainPrompt)
                appendLine()
            }

            // 世界书前置
            if (worldInfoBefore.isNotBlank()) {
                appendLine(worldInfoBefore)
                appendLine()
            }

            // 角色描述
            if (characterDescription.isNotBlank()) {
                appendLine("## 角色描述")
                appendLine(characterDescription)
                appendLine()
            }

            // 角色性格
            if (characterPersonality.isNotBlank()) {
                appendLine("## 性格特点")
                appendLine(characterPersonality)
                appendLine()
            }

            // 场景
            if (scenario.isNotBlank()) {
                appendLine("## 场景")
                appendLine(scenario)
                appendLine()
            }

            // 会话总结
            if (sessionSummary.isNotBlank()) {
                appendLine("## 会话总结")
                appendLine(sessionSummary)
                appendLine()
            }

            // 结构化记忆
            if (structuredMemoryContext.isNotBlank()) {
                appendLine(structuredMemoryContext)
                appendLine()
            }

            // 长期记忆
            if (memoryContext.isNotBlank()) {
                appendLine(memoryContext)
                appendLine()
            }

            // 向量记忆
            if (vectorMemoryContext.isNotBlank()) {
                appendLine(vectorMemoryContext)
                appendLine()
            }

            // 世界书后置
            if (worldInfoAfter.isNotBlank()) {
                appendLine(worldInfoAfter)
                appendLine()
            }

            // 示例对话前的世界书
            if (exampleBefore.isNotBlank()) {
                appendLine(exampleBefore)
                appendLine()
            }

            // 示例对话
            if (exampleDialogue.isNotBlank()) {
                appendLine("## 对话示例")
                appendLine(exampleDialogue)
                appendLine()
            }

            // 示例对话后的世界书
            if (exampleAfter.isNotBlank()) {
                appendLine(exampleAfter)
                appendLine()
            }

            // 作者注释（顶部）
            if (authorsNoteTop.isNotBlank()) {
                appendLine("## 作者注释")
                appendLine(authorsNoteTop)
                appendLine()
            }
        }
    }
}

/**
 * 深度注入条目
 * 世界书条目可以在特定深度插入到消息历史中
 */
data class DepthEntry(
    val depth: Int,
    val content: String,
)

/**
 * 激活的世界书条目
 */
data class ActivatedEntry(
    val id: String,
    val comment: String,
    val content: String,
    val keys: List<String>,
    val position: String, // "before", "after", "exampleBefore", "exampleAfter", "authorTop", "authorBottom"
    val depth: Int?,
    val order: Int,
    val probability: Double,
    val enabled: Boolean,
)

/**
 * 聊天消息（用于本地服务）
 */
data class LocalChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String,
)

/**
 * 提示词审计结果
 */
data class PromptAudit(
    val sections: List<SectionAudit>,
    val messages: List<MessageAudit>,
    val stableTokens: Int,
    val dynamicTokens: Int,
    val totalTokens: Int,
)

data class SectionAudit(
    val name: String,
    val label: String,
    val type: String, // "stable" or "dynamic"
    val chars: Int,
    val estimatedTokens: Int,
    val included: Boolean,
)

data class MessageAudit(
    val index: Int,
    val role: String,
    val chars: Int,
    val estimatedTokens: Int,
    val dynamic: Boolean,
)
