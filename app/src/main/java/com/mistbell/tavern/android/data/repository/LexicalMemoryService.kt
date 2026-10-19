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
class LexicalMemoryService(
    private val context: Context,
    private val topK: Int = DEFAULT_TOP_K,
    private val recentWindow: Int = DEFAULT_RECENT_WINDOW,
) {
    private val db get() = TavernApplication.instance.container.database

    /**
     * 关键词召回历史消息：
     * 1) excluded = **当前会话**最近 [recentWindow] 条消息 id（近期内容本来就在上下文里，不重复召回）；
     * 2) 对 query 分词，无有效词项 → 空表；
     * 3) 在 [window] 允许的会话集合内按 LIKE 多词项 OR 匹配，最新优先，取 [topK] 条。
     *
     * **作用域完全来自 [window]**，本方法与 DAO 都不再自己解释档位：窗口恒为 owner + 当前会话
     * （见 RecallWindow 的产品决策），且**永远不按说话者过滤**——群聊里 NPC 与主角色的消息同属
     * 一个会话，会话边界已覆盖角色隔离，按 `character_id` 切分只会把同一次对话切碎。
     * 历史缺陷正是这里把 CHARACTER 档按说话者收窄（与注释矛盾），现固定传 null。
     *
     * 排除窗口始终基于当前会话（`window.currentSessionId`）——当前会话的近期消息都在上下文中，
     * 不该被重复召回。
     */
    suspend fun searchRelevantHistory(
        window: RecallWindow,
        query: String,
    ): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            // 防御性收口（不信任调用方装配的窗口）：ownerId / currentSessionId 为空 → 拒绝召回
            if (window.ownerId.isBlank() || window.currentSessionId.isBlank()) {
                return@withContext emptyList()
            }
            // 只认当前会话：窗口若携带多会话集合（未来误用）记 warning 并收窄，不做无条件信任
            if ((window.sessionIds ?: setOf(window.currentSessionId)).any { it != window.currentSessionId }) {
                android.util.Log.w(
                    "LexicalMemoryService",
                    "Recall window spans multiple sessions; narrowing to current session",
                )
            }
            val scopedSessionIds = listOf(window.currentSessionId)
            if (window.sessionLimitExceeded) {
                // 历史兜底分支（现窗口恒为 false）：词法层无法表达 owner 级扫描，
                // 这时**宁可不召回**也不退回 owner 级——那正是"本角色看见别的角色对话"的成因。
                return@withContext emptyList()
            }

            val terms = TermExtractor.extract(query)
            if (terms.isEmpty()) return@withContext emptyList()

            val excludedIds =
                db.messageDao().latestIdsBySession(window.currentSessionId, window.ownerId, recentWindow)

            // 契约：固定 6 个词位，未用词位填充 "~~nomatch~~%"（LIKE 不匹配任何真实内容）
            val slots = List(6) { i -> terms.getOrNull(i) ?: NO_MATCH }
            db.messageDao().searchByContentTermsInSessions(
                sessionIds = scopedSessionIds,
                // 当前会话由专门的排除窗口负责：近期消息不召回，更早的照常召回
                excludeSessionId = null,
                ownerId = window.ownerId,
                // 固定不按说话者过滤（见方法注释）：群聊 NPC 与主角色同属一次对话
                characterId = null,
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
                .append(msg.content.take(MAX_RECALLED_CONTENT_CHARS))
                .append('\n')
        }
        return sb.toString().trim()
    }

    private fun like(term: String): String = "%$term%"

    private companion object {
        const val NO_MATCH = "~~nomatch~~"

        /** 单条召回注入的正文上限：**只是注入投影**，消息本体在 Room 里完整保留 */
        const val MAX_RECALLED_CONTENT_CHARS = 150

        /** 召回条数与当前会话排除窗口的默认值（调用方未指定时） */
        const val DEFAULT_TOP_K = 5
        const val DEFAULT_RECENT_WINDOW = 40

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
