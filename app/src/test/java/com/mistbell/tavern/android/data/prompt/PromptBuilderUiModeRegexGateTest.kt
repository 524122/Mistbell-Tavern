package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.regex.CardRegexEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词路径正则闸（P3 执行边界，docs/UI_MODE.md）JVM 单测：
 * 造一条必然改写文本的规则，高级模式下确实改写；简洁模式闸门给空表后，
 * 提示词 / 展示 / 用户输入三路的输出与输入逐字节相同。
 */
class PromptBuilderUiModeRegexGateTest {
    /** 一条命中即改写的规则（placement 覆盖用户输入与 AI 输出，所有模式都应用） */
    private val rewritingRule =
        CardRegexEngine.CardRegexScript(
            name = "rewrite",
            findRegex = "SECRET",
            replaceString = "REVEALED",
            disabled = false,
            placement = listOf(1, 2),
        )

    private val global = listOf(rewritingRule)
    private val card = listOf(rewritingRule.copy(name = "card-rewrite"))

    /** 含标签与实体的刁钻输入：逐字节比较比「不等」更严格 */
    private val tricky = "SECRET <b>&amp;</b>  SECRET\tSECRET\n"

    // ---- 闸门决策 ----

    @Test
    fun `高级模式两份脚本原样透传`() {
        val (gatedGlobal, gatedCard) = PromptBuilder.gatedRegexScriptsForUiMode("advanced", global, card)
        assertSame(global, gatedGlobal)
        assertSame(card, gatedCard)
    }

    @Test
    fun `缺省（未设置）与无法识别都按高级`() {
        assertSame(global, PromptBuilder.gatedRegexScriptsForUiMode(null, global, card).first)
        assertSame(global, PromptBuilder.gatedRegexScriptsForUiMode("", global, card).first)
        assertSame(global, PromptBuilder.gatedRegexScriptsForUiMode("garbage", global, card).first)
    }

    @Test
    fun `简洁模式两份脚本置空`() {
        for (raw in listOf("simple", "SIMPLE", "Simple")) {
            val (gatedGlobal, gatedCard) = PromptBuilder.gatedRegexScriptsForUiMode(raw, global, card)
            assertTrue(gatedGlobal.isEmpty())
            assertTrue(gatedCard.isEmpty())
        }
    }

    // ---- 高级模式：规则确实改写（证明闸门关掉的是真东西）----

    @Test
    fun `高级模式三路都改写`() {
        assertTrue(CardRegexEngine.applyToDisplay(tricky, "assistant", global, 1) != tricky)
        assertTrue(CardRegexEngine.applyToUserInput(tricky, global) != tricky)
        assertTrue(CardRegexEngine.applyToPrompt(tricky, "user", global, 0) != tricky)
    }

    // ---- 简洁模式：三路输出与输入逐字节相同 ----

    @Test
    fun `简洁模式提示词路逐字节原样`() {
        val (gatedGlobal, gatedCard) = PromptBuilder.gatedRegexScriptsForUiMode("simple", global, card)
        for (text in listOf(tricky, "普通消息 SECRET 结尾", "")) {
            assertEquals(
                text,
                CardRegexEngine.applyToPrompt(
                    text,
                    "user",
                    com.mistbell.tavern.android.data.regex.GlobalRegexStore.combine(gatedGlobal, gatedCard),
                    0,
                ),
            )
            assertEquals(
                text,
                CardRegexEngine.applyToPrompt(
                    text,
                    "assistant",
                    com.mistbell.tavern.android.data.regex.GlobalRegexStore.combine(gatedGlobal, gatedCard),
                    1,
                ),
            )
        }
    }

    @Test
    fun `简洁模式展示与用户输入路逐字节原样`() {
        val empty = PromptBuilder.gatedRegexScriptsForUiMode("simple", global, card)
        for (text in listOf(tricky, "SECRET")) {
            assertEquals(text, CardRegexEngine.applyToDisplay(text, "assistant", empty.first, 1))
            assertEquals(text, CardRegexEngine.applyToDisplay(text, "user", empty.second, 0))
            assertEquals(text, CardRegexEngine.applyToUserInput(text, empty.first))
        }
    }
}
