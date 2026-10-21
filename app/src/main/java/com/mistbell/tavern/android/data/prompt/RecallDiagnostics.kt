package com.mistbell.tavern.android.data.prompt

/**
 * 一轮长期记忆召回的诊断（只读投影，不参与装配决策）。
 *
 * 产生链路与装配同源：`PromptBuilder.buildMemoryContext` 装配记忆块时顺手记录，
 * 挂在 `PromptTrace` 上——真实发送与「查看提示词」预览看到的是同一次召回的实情。
 *
 * 用途：核对混合召回（向量 ∩ 词法 → RRF 融合）到底召回了什么、为什么没召回，
 * 以及缓存命中率——排查"该想起来的没想起"时先看这一行。
 */
data class RecallDiagnostics(
    /** 实际注入的结构化记忆条数（"## Known Information" 块内条数） */
    val structuredCount: Int = 0,
    /** 结构化块的 token 预算（裁剪依据；被裁掉的不计入 structuredCount） */
    val structuredBudgetTokens: Int = 0,
    /** embedding 是否可用（false = 无 embedding 的词法回退模式） */
    val vectorAvailable: Boolean = true,
    /** 向量路返回的候选条数（Validator 校验后、阈值/融合前） */
    val vectorCandidates: Int = 0,
    /** 词法路候选条数（混合模式与回退模式都记） */
    val lexicalCandidates: Int = 0,
    /** RRF 融合后（取 topK 后）条数 */
    val fusedCount: Int = 0,
    /** 去重（被结构化块覆盖）后实际注入的召回行数 */
    val injectedRecallLines: Int = 0,
    /** 向量检索缓存命中情况；底层非 CachedVectorStore 或未检索时为 null */
    val vectorCacheHit: Boolean? = null,
    /** 向量检索耗时（含查询向量化）；词法回退模式为 0 */
    val vectorLatencyMs: Long = 0,
    /** 词法检索耗时；向量路不可用/未调用时为 0 */
    val lexicalLatencyMs: Long = 0,
    /** 召回块未注入的原因；已注入为 null（取值见伴生常量） */
    val droppedReason: String? = null,
    /** 词法回退模式（无 embedding）：召回只有词法一路 */
    val lexicalOnly: Boolean = false,
) {
    /** 预览/复制文本用的一行摘要 */
    fun summaryLine(): String {
        val parts = mutableListOf<String>()
        if (structuredCount > 0) parts += "结构化 $structuredCount 条"
        val reason = droppedReason
        if (reason != null) {
            parts += "召回未注入：$reason"
        } else if (injectedRecallLines > 0) {
            parts +=
                if (lexicalOnly) {
                    "词法召回 $injectedRecallLines 条"
                } else {
                    "召回 $injectedRecallLines 条（向量 $vectorCandidates ∩ 词法 $lexicalCandidates → 融合 $fusedCount）"
                }
        }
        val timing = timingPart()
        return when {
            parts.isEmpty() && timing.isEmpty() -> "本轮无长期记忆注入"
            timing.isEmpty() -> parts.joinToString("；")
            else -> parts.joinToString("；") + "；$timing"
        }
    }

    private fun timingPart(): String {
        val segments = mutableListOf<String>()
        if (!lexicalOnly && (vectorLatencyMs > 0 || vectorCacheHit != null)) {
            var text = "向量 ${vectorLatencyMs}ms"
            vectorCacheHit?.let { text += if (it) "·缓存命中" else "·缓存未中" }
            segments += text
        }
        if (lexicalLatencyMs > 0) segments += "词法 ${lexicalLatencyMs}ms"
        return segments.joinToString(" / ")
    }

    companion object {
        const val DROP_GATE_CLOSED = "记忆闸门未通过"
        const val DROP_NO_SESSION = "召回窗口缺会话边界"
        const val DROP_HISTORY_INTACT = "历史未裁剪（召回内容已在上下文中）"
        const val DROP_NO_HITS = "向量与词法均无命中"
        const val DROP_ALL_DUPLICATED = "命中均被结构化块覆盖"
        const val DROP_RECALL_FAILED = "检索异常降级"
    }
}
