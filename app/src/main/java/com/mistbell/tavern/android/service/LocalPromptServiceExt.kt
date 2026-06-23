package com.mistbell.tavern.android.service

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.vector.VectorStore

/**
 * LocalPromptService 扩展
 *
 * 添加结构化记忆和向量记忆的集成
 */

/**
 * 构建结构化记忆上下文
 *
 * @param memories 结构化记忆列表
 * @return 格式化的记忆上下文
 */
fun buildStructuredMemoryContext(memories: List<StructuredMemory>): String {
    if (memories.isEmpty()) return ""

    val sb = StringBuilder()
    sb.append("## 重要记忆\n\n")

    memories
        .sortedByDescending { it.importance }
        .forEach { memory ->
            sb.append("### ${memory.title ?: "记忆片段"}\n")
            sb.append("- **类型**: ${getMemoryTypeDescription(memory.memoryType)}\n")
            sb.append("- **内容**: ${memory.content}\n")
            sb.append("- **重要性**: ${memory.importance}/10\n")

            if (memory.tags.isNotEmpty()) {
                sb.append("- **标签**: ${memory.tags.joinToString(", ")}\n")
            }

            sb.append("\n")
        }

    return sb.toString()
}

/**
 * 构建向量记忆上下文
 *
 * @param searchResults 向量检索结果
 * @return 格式化的向量记忆上下文
 */
fun buildVectorMemoryContext(searchResults: List<VectorStore.SearchResult>): String {
    if (searchResults.isEmpty()) return ""

    val relevantResults = searchResults.filter { it.score > 0.5 }
    if (relevantResults.isEmpty()) return ""

    val sb = StringBuilder()
    sb.append("## 相关历史对话\n\n")

    relevantResults.forEach { result ->
        val similarityPercent = (result.score * 100).toInt()
        sb.append("- ${result.content} (相似度: $similarityPercent%)\n")
    }

    sb.append("\n")

    return sb.toString()
}

/**
 * 获取记忆类型的中文描述
 */
private fun getMemoryTypeDescription(type: String): String {
    return when (type) {
        "character_info" -> "角色信息"
        "event" -> "事件"
        "relationship" -> "关系"
        "fact" -> "事实"
        "item" -> "物品"
        "location" -> "地点"
        else -> type
    }
}
