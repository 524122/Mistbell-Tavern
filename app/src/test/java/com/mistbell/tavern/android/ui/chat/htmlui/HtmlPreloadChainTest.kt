package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 门面预注入的注入串契约（EXTENSION_PLATFORM_DESIGN.md §5.1）：
 * 目标文档 <head> 内最终顺序 = CSP meta（最前，治理其后一切脚本）→ 基础垫片
 * → 原生事件总线段（EVENT_BUS_JS，不随扩展包启停）→ 已启用扩展包 webviewPreload
 * （loadingOrder 升序、同序按 id 稳定）→ 卡片自身内容。
 *
 * 门面（TavernHelper 组装）已迁出为 tavernhelper-compat 制品，本类锁定原生段与装配顺序。
 */
class HtmlPreloadChainTest {
    private fun document(preloads: List<String>): String =
        HtmlUiSandbox.injectCsp(
            HtmlPrereqs.injectWithPrereq(
                "<!DOCTYPE html><html><head><title>t</title></head><body><script>CARD()</script></body>",
                HtmlPrereqs.headBlock("/*JQUERY*/", HtmlWebViewFactory.EVENT_BUS_JS, preloads),
            ),
        )

    @Test
    fun `注入链顺序 CSP meta 最前 其后垫片 原生事件总线 扩展 preload 最后卡片内容`() {
        val doc = document(listOf("/*PRELOAD_1*/", "/*PRELOAD_2*/"))
        val cspAt = doc.indexOf("Content-Security-Policy")
        val jqueryAt = doc.indexOf("/*JQUERY*/")
        val eventBusAt = doc.indexOf("__tavernEmit")
        val preload1At = doc.indexOf("/*PRELOAD_1*/")
        val preload2At = doc.indexOf("/*PRELOAD_2*/")
        val cardAt = doc.indexOf("CARD()")

        assertTrue(cspAt in 0 until jqueryAt)
        assertTrue(jqueryAt < eventBusAt)
        assertTrue(eventBusAt in 0 until preload1At)
        assertTrue(preload1At < preload2At)
        assertTrue(preload2At < cardAt)
        assertTrue("卡片内容必须还在 </body> 语义区（未被注入截断）", doc.indexOf("</body>") > cardAt)
    }

    @Test
    fun `无扩展 preload 时注入串与现状等价（垫片在前 内容在后）`() {
        val doc = document(emptyList())
        val cspAt = doc.indexOf("Content-Security-Policy")
        val jqueryAt = doc.indexOf("/*JQUERY*/")
        val cardAt = doc.indexOf("CARD()")
        assertTrue(cspAt < jqueryAt)
        assertTrue(jqueryAt < cardAt)
    }

    @Test
    fun `原生事件总线段定义 __tavernListeners 与 __tavernEmit（不依赖任何扩展制品）`() {
        val js = HtmlWebViewFactory.EVENT_BUS_JS
        assertTrue(js.contains("window.__tavernListeners = {};"))
        assertTrue(js.contains("window.__tavernEmit = function"))
        // 与迁出前的 FACADE_JS 事件段逐字等价（原生推事件语义不变）
        assertTrue(js.contains("JSON.parse(payloadJson)"))
    }

    @Test
    fun `preload 源码含 script 闭合序列时被内联安全转义（语义不变 防提前闭合）`() {
        val escaped = HtmlPrereqs.escapeInlineScript("var a = '</script>'; var b = '</SCRIPT';")
        // 大小写变体（HTML 结束标签不区分大小写）一律替换为统一形态 `<\/script`
        assertEquals(2, escaped.split("<\\/script").size - 1)
        assertTrue("原文闭合序列不再残留", !escaped.contains("</script") && !escaped.contains("</SCRIPT"))
        assertTrue("无该序列的源码原样通过", HtmlPrereqs.escapeInlineScript("var x = 1;") == "var x = 1;")
    }

    @Test
    fun `iframe srcdoc 补丁的垫片串同步覆盖原生段与扩展 preload`() {
        val shim = HtmlPrereqs.iframeShimCode("/*JQUERY*/", "/*EVENT_BUS*/", listOf("/*PRELOAD*/"))
        val jqueryAt = shim.indexOf("/*JQUERY*/")
        val eventBusAt = shim.indexOf("/*EVENT_BUS*/")
        val preloadAt = shim.indexOf("/*PRELOAD*/")
        assertTrue("iframe 内顺序与主文档一致：垫片 → 原生段 → preload", jqueryAt < eventBusAt && eventBusAt < preloadAt)
        assertTrue(shim.contains(HtmlPrereqs.escapeInlineScript("/*PRELOAD*/")))
    }

    @Test
    fun `原生段为空时不产生空 script 块`() {
        val block = HtmlPrereqs.headBlock("/*JQUERY*/", "", emptyList())
        assertTrue("jquery 垫片 script 块仍在", block.contains("<script>/*JQUERY*/</script>"))
        assertEquals(-1, block.indexOf("<script></script>"))
    }
}
