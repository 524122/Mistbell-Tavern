package com.mistbell.tavern.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTML 兜底纯文本（P3 关掉后的兜底，docs/UI_MODE.md）单测：
 * 标签剥离、块级换行、style/script 内容删除、实体还原、纯文本直通。
 */
class HtmlPlainTextTest {
    @Test
    fun `纯文本逐字节原样`() {
        assertEquals("普通消息", HtmlPlainText.toPlainText("普通消息"))
        assertEquals("a\tb\nc", HtmlPlainText.toPlainText("a\tb\nc"))
    }

    @Test
    fun `标签剥掉且纯文本内容保留`() {
        assertEquals("加粗与斜体", HtmlPlainText.toPlainText("<b>加粗</b>与<i>斜体</i>"))
        assertEquals("自闭合占位", HtmlPlainText.toPlainText("<span data-x=\"1\"/>自闭合<img src=\"y\">占位"))
    }

    @Test
    fun `块级标签与 br 转换行`() {
        assertEquals("第一段\n第二段", HtmlPlainText.toPlainText("<p>第一段</p><p>第二段</p>"))
        assertEquals("一行\n二行\n三行", HtmlPlainText.toPlainText("一行<br>二行<br/>三行"))
        assertEquals("标题\n正文", HtmlPlainText.toPlainText("<h1>标题</h1><div>正文</div>"))
    }

    @Test
    fun `列表转行并保留项目文本`() {
        assertEquals("甲\n乙", HtmlPlainText.toPlainText("<ul><li>甲</li><li>乙</li></ul>"))
    }

    @Test
    fun `表格行转换行单元格留空格`() {
        val html = "<table><tr><td>甲</td><td>乙</td></tr><tr><td>丙</td><td>丁</td></tr></table>"
        assertEquals("甲 乙\n丙 丁", HtmlPlainText.toPlainText(html))
    }

    @Test
    fun `style 与 script 整块内容删除`() {
        assertEquals("正文", HtmlPlainText.toPlainText("<style>body { color: red; }</style>正文"))
        assertEquals("正文", HtmlPlainText.toPlainText("<script>alert(\"x\");</script>正文"))
        assertEquals("A与B", HtmlPlainText.toPlainText("<style>.a { color: red; }</style>A与<script>alert(1);</script>B"))
        assertEquals("前", HtmlPlainText.toPlainText("前<style media=\"print\">.x { }"))
        assertEquals("前", HtmlPlainText.toPlainText("前<script src=\"a.js\">var x = 1;"))
    }

    @Test
    fun `常见命名实体还原`() {
        val html = "a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;"
        assertEquals("a & b < c > d \"e\" 'f'", HtmlPlainText.toPlainText(html))
        assertEquals("空 格", HtmlPlainText.toPlainText("空&nbsp;格"))
        assertEquals("©®™…—–", HtmlPlainText.toPlainText("&copy;&reg;&trade;&hellip;&mdash;&ndash;"))
        assertEquals("“引”", HtmlPlainText.toPlainText("&ldquo;引&rdquo;"))
    }

    @Test
    fun `数字实体还原`() {
        assertEquals("A中😀", HtmlPlainText.toPlainText("&#65;&#20013;&#128512;"))
        assertEquals("A中", HtmlPlainText.toPlainText("&#x41;&#x4E2D;"))
    }

    @Test
    fun `未知实体原样保留`() {
        assertEquals("&nosuch; &amp", HtmlPlainText.toPlainText("&nosuch; &amp"))
    }

    @Test
    fun `实体解码不会引入新标签`() {
        // &lt;b&gt; 还原为字面 "<b>"，不能再被当作标签剥掉
        assertEquals("<b>", HtmlPlainText.toPlainText("&lt;b&gt;"))
    }

    @Test
    fun `块级标签连排换行不堆叠`() {
        assertEquals("甲\n乙", HtmlPlainText.toPlainText("<div>甲</div>\n\n<div>乙</div>"))
        assertEquals("第一段\n第二段", HtmlPlainText.toPlainText("<p>第一段</p>  <p>第二段</p>"))
    }
}
