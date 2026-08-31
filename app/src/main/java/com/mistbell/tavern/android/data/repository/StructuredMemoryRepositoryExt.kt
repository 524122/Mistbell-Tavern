package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 结构化记忆检索扩展
 *
 * 移植自后端 StructuredMemoryService.java 的 retrieveForConversation() 方法
 */

/**
 * 为对话检索相关记忆
 *
 * 返回最重要和最相关的记忆用于注入提示词
 *
 * 检索策略：
 * 1. 高重要性记忆（重要性 >= 8）：始终包含，最多5条
 * 2. 角色基础信息（重要性 >= 6）：优先包含，最多3条
 * 3. 关键词匹配：根据用户消息内容匹配，最多3条
 * 4. 总数限制：最多10条记忆
 *
 * @param ownerId 用户ID
 * @param characterId 角色ID
 * @param userMessage 用户消息（用于关键词匹配）
 * @return 相关记忆列表
 */
suspend fun StructuredMemoryRepository.retrieveForConversation(
    ownerId: String,
    characterId: String,
    userMessage: String,
): List<StructuredMemory> =
    withContext(Dispatchers.IO) {
        // 获取所有记忆
        val allMemories = getMemoriesByCharacter(ownerId, characterId).first()
        val result = mutableListOf<StructuredMemory>()

        // 1. 始终包含高重要性记忆（重要性 >= 8）
        allMemories
            .filter { it.importance >= 8 }
            .sortedByDescending { it.importance }
            .take(5)
            .forEach { result.add(it) }

        // 2. 包含角色基础信息（重要性 >= 6）
        allMemories
            .filter { it.memoryType == "character_info" }
            .filter { it.importance >= 6 }
            .filter { it !in result } // 避免重复
            .take(3)
            .forEach { result.add(it) }

        // 3. 关键词匹配（简单实现）
        if (userMessage.isNotBlank()) {
            val keywords = userMessage.lowercase().split("\\s+".toRegex())

            allMemories
                .filter { it !in result } // 避免重复
                .filter { memory ->
                    val content = memory.content.lowercase()
                    val title = memory.title?.lowercase() ?: ""
                    val tags = memory.tags.map { it.lowercase() }

                    keywords.any { keyword ->
                        keyword.length > 2 && (
                            content.contains(keyword) ||
                                title.contains(keyword) ||
                                tags.any { tag -> tag.contains(keyword) }
                        )
                    }
                }
                .take(3)
                .forEach { result.add(it) }
        }

        // 限制总数为10条
        result.take(10)
    }

/**
 * 检查记忆是否需要同步到向量数据库
 *
 * 同步阈值：重要性 >= 7
 */
fun StructuredMemory.shouldSyncToVector(): Boolean {
    return importance >= 7
}

/**
 * 构建向量内容
 *
 * 格式：标题: 内容 [类型]
 */
fun StructuredMemory.buildVectorContent(): String {
    val sb = StringBuilder()

    if (!title.isNullOrBlank()) {
        sb.append(title).append(": ")
    }

    sb.append(content)

    sb.append(" [").append(memoryType).append("]")

    return sb.toString()
}
