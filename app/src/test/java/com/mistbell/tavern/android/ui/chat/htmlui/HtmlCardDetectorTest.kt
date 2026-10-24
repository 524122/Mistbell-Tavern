package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HtmlCardDetector 的 JVM 单测：围栏/裸 HTML/非 HTML/混合/未闭合。 */
class HtmlCardDetectorTest {
    private val fenced = "```html\n<html><body>hi</body></html>\n```"

    @Test
    fun `纯文本不产生 HTML 段`() {
        val parts = HtmlCardDetector.split("普通消息，没有代码")
        assertEquals(1, parts.size)
        assertTrue(parts.first() is HtmlCardDetector.Part.Markdown)
        assertFalse(HtmlCardDetector.containsHtml("普通消息"))
    }

    @Test
    fun `html 围栏块被提取为 HTML 文档`() {
        val parts = HtmlCardDetector.split(fenced)
        assertEquals(1, parts.size)
        val doc = parts.first() as HtmlCardDetector.Part.HtmlDoc
        assertTrue(doc.html.contains("<body>hi</body>"))
    }

    @Test
    fun `围栏外的文本保持 Markdown 段`() {
        val parts = HtmlCardDetector.split("前文\n$fenced\n后文")
        assertEquals(3, parts.size)
        assertTrue(parts[0] is HtmlCardDetector.Part.Markdown)
        assertTrue(parts[1] is HtmlCardDetector.Part.HtmlDoc)
        assertTrue(parts[2] is HtmlCardDetector.Part.Markdown)
        assertEquals("前文", (parts[0] as HtmlCardDetector.Part.Markdown).text)
        assertEquals("后文", (parts[2] as HtmlCardDetector.Part.Markdown).text)
    }

    @Test
    fun `裸 HTML 整段识别（卡级正则产物）`() {
        val bare = "<!DOCTYPE html>\n<html><body>界面</body></html>"
        val parts = HtmlCardDetector.split(bare)
        assertEquals(1, parts.size)
        assertTrue(parts.first() is HtmlCardDetector.Part.HtmlDoc)
    }

    @Test
    fun `未闭合围栏按普通文本处理`() {
        val unclosed = "```html\n<html><body>没闭合"
        val parts = HtmlCardDetector.split(unclosed)
        assertEquals(1, parts.size)
        assertTrue(parts.first() is HtmlCardDetector.Part.Markdown)
        assertTrue((parts.first() as HtmlCardDetector.Part.Markdown).text.contains("```html"))
    }

    @Test
    fun `非 html 语言围栏不渲染为 HTML`() {
        val parts = HtmlCardDetector.split("```js\nconsole.log(1)\n```")
        assertTrue(parts.none { it is HtmlCardDetector.Part.HtmlDoc })
    }

    @Test
    fun `多个 html 块各自成段`() {
        val parts = HtmlCardDetector.split("$fenced\n间隔\n$fenced")
        assertEquals(3, parts.size)
        assertEquals(2, parts.count { it is HtmlCardDetector.Part.HtmlDoc })
    }

    @Test
    fun `containsHtml 大小写不敏感`() {
        assertTrue(HtmlCardDetector.containsHtml("```HTML\n<x>\n```"))
        assertTrue(HtmlCardDetector.containsHtml("<!doctype html><html></html>"))
    }

    @Test
    fun `叙事行内 HTML 整段包装为 narrative 文档`() {
        val content =
            """
            她看着User。
            <span class="cxi-wrap"><span class="cxi-text">心声</span></span>
            <style>.cxi-wrap{color:gold}</style>
            """.trimIndent()
        val parts = HtmlCardDetector.split(content)
        assertEquals(1, parts.size)
        val doc = parts.first() as HtmlCardDetector.Part.HtmlDoc
        assertTrue(doc.narrative)
        assertTrue(doc.html.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.html.contains("她看着User"))
        assertTrue(doc.html.contains("<style>"))
    }

    @Test
    fun `带 class 的 span 也判定为叙事 HTML`() {
        val parts = HtmlCardDetector.split("文本 <span class=\"x\">内容</span>")
        assertTrue((parts.first() as HtmlCardDetector.Part.HtmlDoc).narrative)
    }

    @Test
    fun `普通文本不触发叙事 HTML`() {
        val parts = HtmlCardDetector.split("只是普通对话，没有标签")
        assertTrue(parts.first() is HtmlCardDetector.Part.Markdown)
    }

    @Test
    fun `裸 HTML 文档不标记 narrative`() {
        val parts = HtmlCardDetector.split("<!DOCTYPE html><html><body><span class=\"x\">a</span></body></html>")
        assertFalse((parts.first() as HtmlCardDetector.Part.HtmlDoc).narrative)
    }
}
