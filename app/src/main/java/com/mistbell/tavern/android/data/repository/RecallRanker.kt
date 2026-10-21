package com.mistbell.tavern.android.data.repository

import kotlin.math.exp
import kotlin.math.ln

/**
 * 混合召回归并排序器（纯函数，无 Android/Room 依赖，JVM 可测）。
 *
 * 向量召回与词法召回此前是二选一（有 embedding 走向量，否则词法），两路信号从不同时使用。
 * 这里把两路候选用 **RRF（Reciprocal Rank Fusion）** 融合成一个排序：
 *
 * - 向量路擅长语义相近但措辞不同的命中；词法路擅长专有名词、稀有词的精确命中——
 *   同一条消息两路都中时名次叠加，天然排在单路命中之前；
 * - 词法路不需要分数语义（DAO 按最新排序），RRF 只用名次、不用原始分数，
 *   正好绕开"cosine 分数与 LIKE 命中不可比"的问题。
 *
 * 阈值政策（保持旧行为语义 + 一个刻意放宽）：
 * - 仅向量命中且分数低于阈值 → 丢弃（与旧路径一致）；
 * - 低于阈值但有词法命中 corroborate → **保留**（混合检索的核心收益：向量搜不准的
 *   专有名词由词法兜底，此时低 cosine 不代表不相关）；
 * - 纯词法命中 → 保留（无 embedding 的旧路径本就信任词法结果）。
 *
 * 权重只作轻量调整，不允许压倒相关性：
 * - 时间因子：半衰期 [RECENCY_HALF_LIFE_DAYS] 天的指数衰减，权重 [RECENCY_WEIGHT]——
 *   只够当平局裁决，老记忆不会因为"旧"被相关性差的新消息顶掉（长期记忆的意义正是捞旧的）；
 * - summary 类型 × [SUMMARY_BOOST]：高重要度记忆的事实摘要优先于原始闲聊。
 *
 * 最后做归一化内容去重（折叠空白 + 小写）：同一段内容被两路以略不同截断捞回时只留一份，
 * 充当轻量 MMR 的替代（完整 MMR 需要候选两两 cosine，复杂度不值）。
 */
object RecallRanker {
    /** RRF 平滑常数（惯例取 60）：压低头名差距，让两路叠加比单路头名更有优势 */
    const val RRF_K = 60

    /** 时间衰减的最大加成比例：recencyFactor=1（此刻）时 fusedScore × (1 + 本值) */
    const val RECENCY_WEIGHT = 0.15f

    /** 时间因子半衰期（天）：每过这么久 recencyFactor 减半 */
    const val RECENCY_HALF_LIFE_DAYS = 14f

    /** summary（重要度≥7 记忆的事实副本）的排序加成 */
    const val SUMMARY_BOOST = 1.1f

    /** ln(2)：时间衰减的半衰期底数（kotlin.math 无 LN2 常量） */
    private val LN2_FLOAT = ln(2.0).toFloat()

    /** 一天的毫秒数：半衰期天数 → 毫秒换算 */
    private const val DAY_MS = 24f * 60f * 60f * 1000f

    /** 一路召回的一名候选：list 顺序即名次（向量路按分数降序，词法路按最新优先） */
    data class RecallCandidate(
        val messageId: String,
        val content: String,
        /** cosine 相似度；null = 纯词法命中（词法路没有分数语义） */
        val vectorScore: Float? = null,
        /** 消息/记忆的创建时间（epoch 毫秒）；未知 → 时间因子取中性 0.5 */
        val createdAtEpochMs: Long? = null,
        /** 是否重要度≥7 记忆的 summary 副本（[VectorStore.SUMMARY_CONTENT_TYPE]） */
        val isSummary: Boolean = false,
    )

    /** 融合排序后的一名结果；[fusedScore] 仅用于排序与观测，不进 prompt 文本 */
    data class RankedRecall(
        val candidate: RecallCandidate,
        val fusedScore: Float,
        val fromVector: Boolean,
        val fromLexical: Boolean,
    )

    private class Merged(
        val candidate: RecallCandidate,
        var vectorRank: Int? = null,
        var lexicalRank: Int? = null,
    )

    /**
     * 融合两路候选并排序。
     *
     * @param vectorHits 向量路候选（已按 cosine 降序），各自的 [RecallCandidate.vectorScore] 必填
     * @param lexicalHits 词法路候选（已按 DAO 最新优先排序）
     * @param topK 最终返回条数
     * @param similarityThreshold 向量分数阈值：仅对"无词法 corroborate"的候选起丢弃作用
     * @param nowEpochMs 当前时间（注入以便测试时间衰减）
     */
    fun fuse(
        vectorHits: List<RecallCandidate>,
        lexicalHits: List<RecallCandidate>,
        topK: Int,
        similarityThreshold: Float,
        nowEpochMs: Long,
    ): List<RankedRecall> {
        // 1. 按 messageId 归并：同一条消息两路都中 → 名次叠加
        val merged = linkedMapOf<String, Merged>()
        vectorHits.forEachIndexed { index, candidate ->
            val entry = merged.getOrPut(candidate.messageId) { Merged(candidate) }
            entry.vectorRank = index
        }
        lexicalHits.forEachIndexed { index, candidate ->
            val existing = merged[candidate.messageId]
            if (existing != null) {
                existing.lexicalRank = index
            } else {
                merged[candidate.messageId] = Merged(candidate, lexicalRank = index)
            }
        }

        // 2. 阈值政策：仅向量命中且低于阈值 → 丢弃；有词法 corroborate 或纯词法 → 保留
        val survivors =
            merged.values.filter { entry ->
                val score = entry.candidate.vectorScore
                entry.lexicalRank != null || (score != null && score >= similarityThreshold)
            }

        // 3. RRF × 时间/类型权重
        val halfLifeMs = RECENCY_HALF_LIFE_DAYS * DAY_MS
        val scored =
            survivors.map { entry ->
                val rrf =
                    (entry.vectorRank?.let { 1f / (RRF_K + it) } ?: 0f) +
                        (entry.lexicalRank?.let { 1f / (RRF_K + it) } ?: 0f)
                val recencyFactor =
                    entry.candidate.createdAtEpochMs?.let { createdAt ->
                        val ageMs = (nowEpochMs - createdAt).coerceAtLeast(0).toFloat()
                        exp(-LN2_FLOAT * ageMs / halfLifeMs).toFloat()
                    } ?: NEUTRAL_RECENCY
                var fused = rrf * (1f + RECENCY_WEIGHT * recencyFactor)
                if (entry.candidate.isSummary) fused *= SUMMARY_BOOST
                RankedRecall(
                    candidate = entry.candidate,
                    fusedScore = fused,
                    fromVector = entry.vectorRank != null,
                    fromLexical = entry.lexicalRank != null,
                )
            }

        // 4. 排序：融合分 → 向量分（未知垫底）→ 创建时间（未知垫底）
        val sorted =
            scored.sortedWith(
                compareByDescending<RankedRecall> { it.fusedScore }
                    .thenByDescending { it.candidate.vectorScore ?: Float.MIN_VALUE }
                    .thenByDescending { it.candidate.createdAtEpochMs ?: Long.MIN_VALUE },
            )

        // 5. 归一化内容去重（两路可能以略不同截断捞回同一段内容），再截断 topK
        val seenContents = mutableSetOf<String>()
        return sorted
            .filter { ranked -> seenContents.add(normalizeContent(ranked.candidate.content)) }
            .take(topK)
    }

    private fun normalizeContent(content: String): String = content.lowercase().replace(Regex("\\s+"), " ").trim()

    /** 时间戳未知时的中性时间因子：不吃满加成也不垫底 */
    private const val NEUTRAL_RECENCY = 0.5f
}
