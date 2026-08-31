package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SamplerPresets 纯函数单元测试（S1 采样预设）。
 * 覆盖：byName 三档与 custom/null/未知；resolve 各字段优先级（base 显式 > 预设 > 默认）；
 * temperature 以 != 0.8 视为显式的启发式行为；空预设（custom）只保留 base。
 *
 * 注：temperature 启发式存在已知限制——用户显式填 0.8 时会被视为"未显式"，被预设温度覆盖。
 */
class SamplerPresetsTest {
    // ---------- byName ----------

    @Test
    fun `byName返回创意档`() {
        assertEquals(SamplerPresets.CREATIVE, SamplerPresets.byName("creative"))
    }

    @Test
    fun `byName返回平衡档`() {
        assertEquals(SamplerPresets.BALANCED, SamplerPresets.byName("balanced"))
    }

    @Test
    fun `byName返回精确档`() {
        assertEquals(SamplerPresets.PRECISE, SamplerPresets.byName("precise"))
    }

    @Test
    fun `byName对custom返回null`() {
        assertNull(SamplerPresets.byName("custom"))
    }

    @Test
    fun `byName对null和未知值返回null`() {
        assertNull(SamplerPresets.byName(null))
        assertNull(SamplerPresets.byName("aggressive"))
        assertNull(SamplerPresets.byName(""))
    }

    // ---------- resolve：字段优先级 ----------

    /** base 全默认（temperature=0.8 视为未显式，其余 null），预设应全部生效。 */
    @Test
    fun `base全默认时预设参数全部生效`() {
        val base = LlmConfig(baseUrl = "https://x", apiKey = "k", model = "m")
        val out = SamplerPresets.resolve(base, SamplerPresets.CREATIVE)

        assertEquals(1.1, out.temperature, 1e-9)
        assertEquals(0.95, out.topP!!, 1e-9)
        assertEquals(40, out.topK)
        assertEquals(0.0, out.frequencyPenalty!!, 1e-9)
        // 与采样无关的字段保持 base 原值
        assertEquals("https://x", out.baseUrl)
        assertEquals(1024, out.maxTokens)
    }

    /** base.topP/topK/frequencyPenalty 非 null 即显式：覆盖预设。 */
    @Test
    fun `base显式细项覆盖预设`() {
        val base =
            LlmConfig(
                baseUrl = "https://x",
                apiKey = "k",
                model = "m",
                topP = 0.5,
                topK = 10,
                frequencyPenalty = 0.9,
            )
        val out = SamplerPresets.resolve(base, SamplerPresets.CREATIVE)

        // 显式字段保留 base 原值
        assertEquals(0.5, out.topP!!, 1e-9)
        assertEquals(10, out.topK)
        assertEquals(0.9, out.frequencyPenalty!!, 1e-9)
        // temperature=0.8 未显式 → 取预设值
        assertEquals(1.1, out.temperature, 1e-9)
    }

    /** base.temperature != 0.8 视为显式：不被预设覆盖。 */
    @Test
    fun `base温度非08视为显式不被预设覆盖`() {
        val base = LlmConfig(baseUrl = "x", apiKey = "k", model = "m", temperature = 0.3)
        val out = SamplerPresets.resolve(base, SamplerPresets.PRECISE)

        assertEquals(0.3, out.temperature, 1e-9)
        // 预设其余字段仍生效
        assertEquals(0.70, out.topP!!, 1e-9)
        assertEquals(0.3, out.frequencyPenalty!!, 1e-9)
    }

    /** temperature=0.8 视为未显式的启发式行为：即使"用户想填 0.8"也会被预设覆盖（文档已注明的限制）。 */
    @Test
    fun `base温度恰为08视为未显式被预设覆盖`() {
        val base = LlmConfig(baseUrl = "x", apiKey = "k", model = "m", temperature = 0.8)
        val out = SamplerPresets.resolve(base, SamplerPresets.BALANCED)

        assertEquals(0.8, out.temperature, 1e-9) // BALANCED 本身是 0.8，结果一致
        val creative = SamplerPresets.resolve(base, SamplerPresets.CREATIVE)
        assertEquals(1.1, creative.temperature, 1e-9) // 显式 0.8 被 CREATIVE 的 1.1 覆盖
    }

    /** 空预设（params=null，即 custom）：只保留 base，字段不额外改动。 */
    @Test
    fun `空预设只保留base`() {
        val base =
            LlmConfig(
                baseUrl = "x",
                apiKey = "k",
                model = "m",
                temperature = 0.6,
                topP = 0.9,
                topK = 30,
                frequencyPenalty = 0.1,
                maxTokens = 512,
            )
        val out = SamplerPresets.resolve(base, null)

        assertEquals(base, out)
    }

    /** 空预设 + base 全默认：无预设可兜底，保持 LlmConfig 各字段默认。 */
    @Test
    fun `空预设且base默认时保持默认值`() {
        val base = LlmConfig(baseUrl = "x", apiKey = "k", model = "m")
        val out = SamplerPresets.resolve(base, null)

        assertEquals(0.8, out.temperature, 1e-9)
        assertNull(out.topP)
        assertNull(out.topK)
        assertNull(out.frequencyPenalty)
        assertEquals(1024, out.maxTokens)
    }
}
