package com.mistbell.tavern.android.service

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.first

/**
 * 本地记忆服务
 *
 * 负责本地记忆的检索和管理
 * 使用简单的关键词匹配替代向量搜索
 */
class LocalMemoryService(private val context: Context) {

    private val db get() = TavernApplication.instance.database

    /**
     * 搜索相关记忆
     *
     * @param query 查询文本
     * @param ownerId 所有者 ID
     * @param characterId 角色 ID
     * @param limit 返回数量限制
     * @return 相关记忆列表
     */
    suspend fun searchMemories(
        query: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        limit: Int = 10
    ): List<MemoryEntity> {
        // 获取所有记忆
        val allMemories = db.memoryDao()
            .getBySession(ownerId, characterId, sessionId)
            .first()

        if (allMemories.isEmpty()) return emptyList()

        // 提取查询关键词
        val keywords = extractKeywords(query)

        // 计算每个记忆的相关性分数
        val scoredMemories = allMemories.map { memory ->
            val score = calculateRelevanceScore(memory, keywords, query)
            memory to score
        }

        // 按分数排序并返回前 N 个
        return scoredMemories
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * 按重要性获取记忆
     *
     * @param ownerId 所有者 ID
     * @param characterId 角色 ID
     * @param threshold 重要性阈值 (0.0 - 1.0)
     * @param limit 返回数量限制
     * @return 重要记忆列表
     */
    suspend fun getImportantMemories(
        ownerId: String,
        characterId: String,
        sessionId: String,
        threshold: Double = 0.7,
        limit: Int = 10
    ): List<MemoryEntity> {
        val allMemories = db.memoryDao()
            .getBySession(ownerId, characterId, sessionId)
            .first()

        return allMemories
            .filter { it.importance >= threshold }
            .sortedByDescending { it.importance }
            .take(limit)
    }

    /**
     * 按标签搜索记忆
     *
     * @param tags 标签列表
     * @param ownerId 所有者 ID
     * @param characterId 角色 ID
     * @return 匹配的记忆列表
     */
    suspend fun searchByTags(
        tags: List<String>,
        ownerId: String,
        characterId: String,
        sessionId: String
    ): List<MemoryEntity> {
        val allMemories = db.memoryDao()
            .getBySession(ownerId, characterId, sessionId)
            .first()

        return allMemories.filter { memory ->
            val memoryTags = decodeList(memory.tags)
            // 与 calculateRelevanceScore 保持一致：大小写不敏感、忽略空标签
            tags.filter { it.isNotBlank() }.any { tag ->
                memoryTags.any { it.lowercase().contains(tag.lowercase()) }
            }
        }
    }

    /**
     * 按层级获取记忆
     *
     * @param layer 记忆层级（profile, relationship, episodic, core）
     * @param ownerId 所有者 ID
     * @param characterId 角色 ID
     * @return 该层级的记忆列表
     */
    suspend fun getMemoriesByLayer(
        layer: String,
        ownerId: String,
        characterId: String,
        sessionId: String
    ): List<MemoryEntity> {
        val allMemories = db.memoryDao()
            .getBySession(ownerId, characterId, sessionId)
            .first()

        return allMemories.filter { it.layer == layer }
    }

    /**
     * 解码 JSON 编码的字符串列表（如 ["a","b"]）
     *
     * 空串/解析失败返回空列表（与 MemoryEntity.toDomain 的解码语义一致）
     */
    private fun decodeList(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 提取关键词
     *
     * 简单实现：分词 + 过滤停用词
     */
    private fun extractKeywords(text: String): List<String> {
        // 停用词列表（简化版）
        val stopWords = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没",
            "看", "好", "自己", "这", "那", "什么", "怎么", "为什么",
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "are", "was", "were", "be", "been"
        )

        return text
            .split(Regex("[\\s\\p{Punct}]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it !in stopWords && it.length > 1 }
    }

    /**
     * 计算相关性分数
     *
     * 基于以下因素：
     * 1. 关键词匹配数量
     * 2. 完整匹配（包含原始查询文本）
     * 3. 重要性权重
     * 4. 稳定性权重
     */
    private fun calculateRelevanceScore(
        memory: MemoryEntity,
        keywords: List<String>,
        originalQuery: String
    ): Double {
        val contentLower = memory.content.lowercase()
        val queryLower = originalQuery.lowercase()

        var score = 0.0

        // 完整匹配（最高权重）
        if (contentLower.contains(queryLower)) {
            score += 10.0
        }

        // 关键词匹配
        val matchedKeywords = keywords.count { keyword ->
            contentLower.contains(keyword)
        }
        score += matchedKeywords * 2.0

        // 别名匹配（先解码 JSON 列表）
        val aliasList = decodeList(memory.aliases)
        val aliasMatches = aliasList.count { alias ->
            queryLower.contains(alias.lowercase()) || alias.lowercase().contains(queryLower)
        }
        score += aliasMatches * 3.0

        // 标签匹配（先解码 JSON 列表）
        val tagMatches = decodeList(memory.tags).count { tag ->
            queryLower.contains(tag.lowercase())
        }
        score += tagMatches * 2.0

        // 重要性加成
        score *= (1.0 + memory.importance)

        // 稳定性加成（高稳定性记忆更相关）
        score *= (0.5 + memory.stability * 0.5)

        return score
    }

    /**
     * 计算 TF-IDF 权重（可选的高级实现）
     *
     * Term Frequency - Inverse Document Frequency
     * 用于更精确的文本相关性计算
     */
    private suspend fun calculateTfIdf(
        term: String,
        document: String,
        allDocuments: List<String>
    ): Double {
        // TF: 词频
        val termCount = document.lowercase().split(Regex("\\s+"))
            .count { it == term.lowercase() }
        val tf = termCount.toDouble() / document.split(Regex("\\s+")).size

        // IDF: 逆文档频率
        val documentsContainingTerm = allDocuments.count { doc ->
            doc.lowercase().contains(term.lowercase())
        }
        val idf = if (documentsContainingTerm > 0) {
            kotlin.math.ln(allDocuments.size.toDouble() / documentsContainingTerm)
        } else {
            0.0
        }

        return tf * idf
    }

    /**
     * 格式化记忆用于提示词
     *
     * @param memories 记忆列表
     * @return 格式化的记忆文本
     */
    fun formatMemoriesForPrompt(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return ""

        return buildString {
            appendLine("## 相关记忆")
            appendLine()
            memories.forEachIndexed { index, memory ->
                appendLine("${index + 1}. ${memory.content}")

                // 如果有三元组信息，也显示
                if (memory.subject.isNotBlank() && memory.relation.isNotBlank() && memory.`object`.isNotBlank()) {
                    appendLine("   (${memory.subject} - ${memory.relation} - ${memory.`object`})")
                }

                appendLine()
            }
        }
    }
}
