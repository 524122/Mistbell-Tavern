package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.util.TermExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * F3-FTS：词法（LIKE 关键词）长期记忆召回服务。
 *
 * 借鉴 OMate 的"永久记忆=历史全文检索"思路：无 embedding API 时，
 * 用诚实的词法匹配替代 BM25 伪向量——不再假装有语义相似度。
 */
class LexicalMemoryService(private val context: Context) {
    private val db get() = TavernApplication.instance.database

    /**
     * 关键词召回历史消息：
     * 1) excluded = 最近 recentWindow 条消息 id（近期内容本来就在上下文里，不重复召回）；
     * 2) 对 query 分词，无有效词项 → 空表；
     * 3) 按 LIKE 多词项 OR 匹配，最新优先，取 topK 条。
     */
    suspend fun searchRelevantHistory(
        ownerId: String,
        // 会话级召回后 characterId 不再参与过滤（保留参数位以免破坏调用方，
        // 未来按 NPC 分账召回时会重新启用）
        @Suppress("UnusedParameter") characterId: String,
        sessionId: String,
        query: String,
        topK: Int = 5,
        recentWindow: Int = 40,
    ): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            // 会话级召回（跨代理契约 1）：消息读写一律 (session_id, owner_id)，
            // character_id 仅作说话方元数据，词法召回覆盖群聊 NPC 与主角色的全部消息
            val excludedIds = db.messageDao().latestIdsBySession(sessionId, ownerId, recentWindow)
            val terms = TermExtractor.extract(query)
            if (terms.isEmpty()) return@withContext emptyList()

            // 契约：固定 6 个词位，未用词位填充 "~~nomatch~~%"（LIKE 不匹配任何真实内容）
            val slots = List(6) { i -> terms.getOrNull(i) ?: NO_MATCH }
            db.messageDao().searchByContentTerms(
                sessionId = sessionId,
                ownerId = ownerId,
                excludedIds = excludedIds,
                t1 = like(slots[0]),
                t2 = like(slots[1]),
                t3 = like(slots[2]),
                t4 = like(slots[3]),
                t5 = like(slots[4]),
                t6 = like(slots[5]),
                resultLimit = topK,
            )
        }

    /**
     * 将召回结果格式化为注入 prompt 的文本；
     * 空结果返回 ""（调用方据此跳过注入）。
     */
    fun formatHistory(items: List<MessageEntity>): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder("## 往事回响（关键词召回）\n")
        items.forEach { msg ->
            sb.append('[').append(formatTimestamp(msg.createdAt)).append("] ")
                .append(msg.role).append(": ")
                .append(msg.content.take(150))
                .append('\n')
        }
        return sb.toString().trim()
    }

    private fun like(term: String): String = "%$term%"

    private companion object {
        const val NO_MATCH = "~~nomatch~~"
        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }

    // createdAt 存的是 ISO-8601 字符串；解析失败时原样输出
    private fun formatTimestamp(createdAt: String): String =
        try {
            TIME_FORMAT.format(Instant.parse(createdAt).atZone(ZoneId.systemDefault()))
        } catch (_: Exception) {
            createdAt
        }
}
