package com.mistbell.tavern.android.ui.chat.render

import com.mistbell.tavern.android.data.repository.ResolvedLayoutPack
import com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 降级路由决策全路径单测（THEME_FRAMEWORK_DESIGN.md §7 层级 2/3）：
 * 开关关 / 无包 / 模板版本不支持 / 引擎未引导 / 危险卡段 → legacy；其余 → engine。
 * 纯函数、纯 JVM。
 */
class EngineRenderPathTest {
    private val pack =
        ResolvedLayoutPack(
            packId = "ink",
            templateHtml = "<div class=\"msg\"><slot name=\"narrative\"></slot><slot name=\"panels\"></slot></div>",
            styleCss = ".ink { color: #222222; }",
            templateVersion = 1,
            themeVarsLight = emptyMap(),
            themeVarsDark = emptyMap(),
        )

    private fun md(text: String) = HtmlCardDetector.Part.Markdown(text)

    private fun html(text: String) = HtmlCardDetector.Part.HtmlDoc(text)

    @Test
    fun `switch off falls back to legacy`() {
        val path =
            resolveRenderPath(
                engineEnabled = false,
                pack = pack,
                segments = listOf(md("正文"), html("<div>面板</div>")),
                engineReady = true,
            )
        assertEquals(RenderPath.Legacy, path)
    }

    @Test
    fun `no layout pack falls back to legacy`() {
        val path =
            resolveRenderPath(
                engineEnabled = true,
                pack = null,
                segments = listOf(md("正文")),
                engineReady = true,
            )
        assertEquals(RenderPath.Legacy, path)
    }

    @Test
    fun `unsupported template version falls back to legacy`() {
        val future = pack.copy(templateVersion = 2)
        val path =
            resolveRenderPath(
                engineEnabled = true,
                pack = future,
                segments = listOf(md("正文")),
                engineReady = true,
            )
        assertEquals(RenderPath.Legacy, path)
    }

    @Test
    fun `engine not bootstrapped falls back to legacy`() {
        val path =
            resolveRenderPath(
                engineEnabled = true,
                pack = pack,
                segments = listOf(md("正文")),
                engineReady = false,
            )
        assertEquals(RenderPath.Legacy, path)
    }

    @Test
    fun `dangerous card segment falls back to legacy for webview rendering`() {
        // table 在白名单外：引擎会整棵丢掉。路由把整条消息送回 legacy，
        // 由 litehtml 画表格；带脚本的卡段才继续走 HtmlBubble。
        val path =
            resolveRenderPath(
                engineEnabled = true,
                pack = pack,
                segments = listOf(md("叙述"), html("<table><tr><td>属性表</td></tr></table>")),
                engineReady = true,
            )
        assertEquals(RenderPath.Legacy, path)
    }

    @Test
    fun `whitelist card segment renders engine`() {
        val path =
            resolveRenderPath(
                engineEnabled = true,
                pack = pack,
                segments = listOf(md("叙述"), html("<div class=\"panel\"><b>HP</b> 80</div>")),
                engineReady = true,
            )
        assertEquals(RenderPath.Engine(pack), path)
    }

    @Test
    fun `markdown only message renders engine`() {
        val path =
            resolveRenderPath(
                engineEnabled = true,
                pack = pack,
                segments = listOf(md("**加粗** 与 *斜体*")),
                engineReady = true,
            )
        assertEquals(RenderPath.Engine(pack), path)
    }

    @Test
    fun `unclosed fence stays markdown and renders engine`() {
        // 未闭合的 ```html 围栏按普通文本还原（HtmlCardDetector 语义），不触发卡段回退
        val segments = HtmlCardDetector.split("开场文本\n```html\n<div>未闭合")
        val path =
            resolveRenderPath(
                engineEnabled = true,
                pack = pack,
                segments = segments,
                engineReady = true,
            )
        assertEquals(RenderPath.Engine(pack), path)
    }
}
