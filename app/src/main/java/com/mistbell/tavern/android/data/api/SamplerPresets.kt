package com.mistbell.tavern.android.data.api

/**
 * S1: 采样参数三档预设（纯函数，供单测）。
 * 预设只提供「未显式设置」时的兜底值；用户/提供商级显式值优先级更高。
 */
data class SamplingParams(
    val temperature: Double,
    val topP: Double? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
)

object SamplerPresets {
    val CREATIVE = SamplingParams(1.1, 0.95, 40, 0.0) // 创意
    val BALANCED = SamplingParams(0.8, 0.90, null, 0.0) // 平衡（默认档）
    val PRECISE = SamplingParams(0.4, 0.70, null, 0.3) // 精确

    /**
     * 按名称取预设：creative/balanced/precise；
     * 其余（含 custom / null / 未知）→ null，表示无预设兜底（保持 base 原值）。
     */
    fun byName(name: String?): SamplingParams? =
        when (name?.lowercase()) {
            "creative" -> CREATIVE
            "balanced" -> BALANCED
            "precise" -> PRECISE
            else -> null
        }

    /**
     * 预设解析: 每个输出字段 = base 显式值 > 预设值 > 保持 base 默认。
     * 显式判定:
     *  - topP/topK/frequencyPenalty: base 对应字段非 null 即视为显式；
     *  - temperature: 由调用方通过 [temperatureExplicit] 明确告知来源，不再用“是否等于 0.8”猜测。
     *    默认 true 表示保留 base.temperature（调用方未声明“未设置”时不能擅自换掉显式 0.8）。
     */
    fun resolve(
        base: LlmConfig,
        params: SamplingParams?,
        temperatureExplicit: Boolean = true,
    ): LlmConfig {
        if (params == null) return base
        return base.copy(
            temperature = if (temperatureExplicit) base.temperature else params.temperature,
            topP = base.topP ?: params.topP,
            topK = base.topK ?: params.topK,
            frequencyPenalty = base.frequencyPenalty ?: params.frequencyPenalty,
        )
    }
}
