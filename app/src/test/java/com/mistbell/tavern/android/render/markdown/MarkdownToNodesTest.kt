package com.mistbell.tavern.android.render.markdown

import com.mistbell.tavern.android.render.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MarkdownToNodes 的 JVM 单测：标题字号档、列表前缀、行内装饰、代码块与颜色参数。 */
class MarkdownToNodesTest {
    private val palette =
        MarkdownToNodes.Palette(
            quoteColorArgb = QUOTE_COLOR,
            actionColorArgb = ACTION_COLOR,
            linkColorArgb = LINK_COLOR,
            codeBackgroundArgb = CODE_BG,
        )

    private fun convert(markdown: String) = MarkdownToNodes.convert(markdown, palette)

    @Test
    fun `标题字号档位递减加粗`() {
        val blocks = convert("# 一级\n\n### 三级\n\n###### 六级")
        assertEquals(3, blocks.size)
        assertEquals(24f, blocks[0].fontSizePx)
        assertEquals(18f, blocks[1].fontSizePx)
        assertEquals(13f, blocks[2].fontSizePx)
        blocks.forEach { block -> assertTrue(block.spans.all { it.bold }) }
    }

    @Test
    fun `无序列表加项目符号前缀`() {
        val blocks = convert("- 清点物资\n\n- 修补船帆")
        assertEquals(listOf("• 清点物资", "• 修补船帆"), blocks.map { it.text })
    }

    @Test
    fun `有序列表恢复原始序号`() {
        val blocks = convert("1. 先离港\n2. 再谈航线")
        assertEquals(listOf("1. 先离港", "2. 再谈航线"), blocks.map { it.text })
    }

    @Test
    fun `嵌套列表带缩进前缀`() {
        val blocks = convert("- 顶层\n\n  - 次级")
        assertEquals(2, blocks.size)
        assertEquals("• 顶层", blocks[0].text)
        assertEquals("  • 次级", blocks[1].text)
    }

    @Test
    fun `段落吸收的列表行不加前缀`() {
        // 与 parseMarkdown 同款行为：紧跟段落的列表行被段落吸收（原渲染器观感一致）
        val blocks = convert("正文一段\n- 被吸收行")
        assertEquals(1, blocks.size)
        assertEquals("正文一段 - 被吸收行", blocks[0].text)
    }

    @Test
    fun `粗体映射为 bold span`() {
        val spans = convert("她**握紧**栏杆。").single().spans
        assertEquals(3, spans.size)
        assertEquals("她", spans[0].text)
        assertTrue(!spans[0].bold)
        assertEquals("握紧", spans[1].text)
        assertTrue(spans[1].bold)
        assertEquals("栏杆。", spans[2].text)
    }

    @Test
    fun `行内代码映射为等宽小号 span`() {
        val spans = convert("数值 `x = 1` 结束").single().spans
        val code = spans.first { it.mono }
        assertEquals("x = 1", code.text)
        assertEquals(13f, code.fontSizePx)
    }

    @Test
    fun `链接映射为下划线颜色与 href`() {
        val spans = convert("详情见 [航海日志](https://example.com/log)。").single().spans
        val link = spans.first { it.href != null }
        assertEquals("航海日志", link.text)
        assertTrue(link.underline)
        assertEquals(LINK_COLOR, link.colorArgb)
        assertEquals("https://example.com/log", link.href)
    }

    @Test
    fun `引号对话应用传入颜色`() {
        // 与原渲染器 QUOTE_PATTERN 一致：支持英文直引号对与 CJK 引号，弯引号对不在白名单
        val spans = convert("她说\"你真的要走吗？\"然后沉默。").single().spans
        val quote = spans.first { it.colorArgb == QUOTE_COLOR }
        assertEquals("\"你真的要走吗？\"", quote.text)
        assertTrue(!quote.italic)
    }

    @Test
    fun `动作括号应用传入颜色与斜体`() {
        val spans = convert("她望向水面（指尖微微发白）。").single().spans
        val action = spans.first { it.colorArgb == ACTION_COLOR }
        assertEquals("（指尖微微发白）", action.text)
        assertTrue(action.italic)
    }

    @Test
    fun `代码块为等宽块并携带背景`() {
        val block = convert("```\ncode line\n```").single()
        assertEquals(1, block.spans.size)
        assertTrue(block.spans.single().mono)
        assertEquals(12f, block.fontSizePx)
        assertEquals(18f, block.lineHeightPx)
        assertEquals(CODE_BG, block.codeBackgroundArgb)
    }

    @Test
    fun `br 转为段内换行`() {
        val block = convert("第一行<br>第二行<br/>第三行").single()
        assertEquals("第一行\n第二行\n第三行", block.text)
    }

    @Test
    fun `默认调色板取 MarkdownRenderer 现用色`() {
        assertEquals(0xFF4D8064L, MarkdownToNodes.DEFAULT_QUOTE_COLOR)
        assertEquals(0xFFC1843BL, MarkdownToNodes.DEFAULT_ACTION_COLOR)
        assertEquals(0xFF2563EBL, MarkdownToNodes.DEFAULT_LINK_COLOR)
    }

    @Test
    fun `块默认字号与行高基线`() {
        val block = convert("普通段落").single()
        assertEquals(16f, block.fontSizePx)
        assertEquals(24f, block.lineHeightPx)
        assertEquals(TextAlign.LEFT, block.align)
        assertNotNull(block.spans.single())
    }

    companion object {
        private const val QUOTE_COLOR = 0xFF112233L
        private const val ACTION_COLOR = 0xFF445566L
        private const val LINK_COLOR = 0xFF778899L
        private const val CODE_BG = 0xFFAABBCCL
    }
}
