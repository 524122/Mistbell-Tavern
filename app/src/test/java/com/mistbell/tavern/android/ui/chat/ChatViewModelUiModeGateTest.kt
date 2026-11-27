package com.mistbell.tavern.android.ui.chat

import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.regex.CardRegexEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页界面模式闸纯函数（P3 执行边界，docs/UI_MODE.md）JVM 单测：
 * 生效正则（展示/用户输入/HTML 界面脚本三路的源头）与 htmlUiEnabled 的模式并入判定。
 */
class ChatViewModelUiModeGateTest {
    private val scripts =
        listOf(
            CardRegexEngine.CardRegexScript(
                name = "rewrite",
                findRegex = "SECRET",
                replaceString = "REVEALED",
                disabled = false,
                placement = listOf(1, 2),
            ),
        )

    // ---- regexScriptsForUiMode ----

    @Test
    fun `高级模式原样透传同一列表`() {
        assertSame(scripts, regexScriptsForUiMode(scripts, UiMode.ADVANCED))
    }

    @Test
    fun `简洁模式吐空表`() {
        assertTrue(regexScriptsForUiMode(scripts, UiMode.SIMPLE).isEmpty())
    }

    @Test
    fun `简洁模式空表下正则应用逐字节原样`() {
        val text = "SECRET <b>&amp;</b>"
        val gated = regexScriptsForUiMode(scripts, UiMode.SIMPLE)
        assertEquals(text, CardRegexEngine.applyToDisplay(text, "assistant", gated, 1))
        assertEquals(text, CardRegexEngine.applyToUserInput(text, gated))
    }

    // ---- htmlUiEnabledForUiMode ----

    @Test
    fun `设置开着但模式为简洁时 htmlUiEnabled 为假`() {
        assertFalse(htmlUiEnabledForUiMode("1", UiMode.SIMPLE))
    }

    @Test
    fun `设置开着且模式为高级时 htmlUiEnabled 为真`() {
        assertTrue(htmlUiEnabledForUiMode("1", UiMode.ADVANCED))
    }

    @Test
    fun `设置关着时无论模式都是假`() {
        assertFalse(htmlUiEnabledForUiMode("0", UiMode.ADVANCED))
        assertFalse(htmlUiEnabledForUiMode(null, UiMode.ADVANCED))
        assertFalse(htmlUiEnabledForUiMode("0", UiMode.SIMPLE))
    }
}
