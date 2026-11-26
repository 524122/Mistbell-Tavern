package com.mistbell.tavern.android.render.engine

import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextRect
import com.mistbell.tavern.android.render.model.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 交互会话的命中几何纯函数单测（不触 skiko JNI——Paragraph 以 null 参与几何断言）：
 * 点在矩形内 / 最近行选择 / 链接区间判定 / 词边界收敛 / px→dp 换算 / release 幂等。
 */
class RenderSessionTest {
    /** 三行堆叠的块（左对齐，内容框即行区域） */
    private val stacked =
        listOf(
            rect(left = 0f, top = 0f, width = 100f, height = 24f),
            rect(left = 0f, top = 30f, width = 80f, height = 24f),
            rect(left = 0f, top = 60f, width = 90f, height = 24f),
        )

    @Suppress("LongParameterList") // 命中矩形全字段显式构造，测试可读性优先
    private fun rect(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        spans: List<TextSpan> = listOf(TextSpan("文本内容")),
        contentWidth: Float = width,
        align: TextAlign = TextAlign.LEFT,
    ) = TextRect(
        left = left,
        top = top,
        width = width,
        height = height,
        spans = spans,
        contentWidth = contentWidth,
        align = align,
    )

    // ---- lineStartX：对齐折算 ----

    @Test
    fun `左对齐行起点即内容框左缘`() {
        assertEquals(
            10f,
            RenderSession.lineStartX(rect(left = 10f, top = 0f, width = 50f, height = 20f)),
            0.001f,
        )
    }

    @Test
    fun `居中对齐行起点水平居中`() {
        val r = rect(left = 10f, top = 0f, width = 50f, height = 20f, contentWidth = 110f, align = TextAlign.CENTER)
        assertEquals(40f, RenderSession.lineStartX(r), 0.001f)
    }

    @Test
    fun `右对齐行起点贴内容框右缘`() {
        val r = rect(left = 10f, top = 0f, width = 50f, height = 20f, contentWidth = 110f, align = TextAlign.RIGHT)
        assertEquals(70f, RenderSession.lineStartX(r), 0.001f)
    }

    // ---- containsPoint / blockIndexAt：点在矩形内与最近行选择 ----

    @Test
    fun `点在行区域内命中`() {
        assertTrue(RenderSession.containsPoint(stacked[1], x = 40f, y = 40f))
        assertFalse(RenderSession.containsPoint(stacked[1], x = 120f, y = 40f))
        assertFalse(RenderSession.containsPoint(stacked[1], x = 40f, y = 100f))
    }

    @Test
    fun `点精确落在第二行命中第二块`() {
        assertEquals(1, RenderSession.blockIndexAt(stacked, x = 40f, y = 40f, snapDp = RenderSession.SNAP_DP))
    }

    @Test
    fun `行间空隙落点吸附到最近行`() {
        // y=28 在第 0/1 行之间：距第 0 行底（24）为 4、距第 1 行顶（30）为 2 → 第 1 行
        assertEquals(1, RenderSession.blockIndexAt(stacked, x = 40f, y = 28f, snapDp = RenderSession.SNAP_DP))
        // y=56 距第 2 行顶 60 为 4、距第 1 行底 54 为 2 → 第 1 行
        assertEquals(1, RenderSession.blockIndexAt(stacked, x = 40f, y = 56f, snapDp = RenderSession.SNAP_DP))
        // y=59 距第 2 行顶为 1、距第 1 行底为 5 → 第 2 行
        assertEquals(2, RenderSession.blockIndexAt(stacked, x = 40f, y = 59f, snapDp = RenderSession.SNAP_DP))
    }

    @Test
    fun `超出吸附容差的空白返回未命中`() {
        assertEquals(
            -1,
            RenderSession.blockIndexAt(stacked, x = 40f, y = 60f + 24f + 9f, snapDp = RenderSession.SNAP_DP),
        )
    }

    @Test
    fun `垂直近但水平远的落点不吸附`() {
        // y 在第 1 行旁、x 距行右缘（80）10dp > snap 8 → 不吸附
        assertEquals(-1, RenderSession.blockIndexAt(stacked, x = 90f, y = 40f, snapDp = RenderSession.SNAP_DP))
        // x 距右缘 6dp ≤ snap → 吸附
        assertEquals(1, RenderSession.blockIndexAt(stacked, x = 86f, y = 40f, snapDp = RenderSession.SNAP_DP))
    }

    @Test
    fun `居中对齐按折算后的行区域命中`() {
        val centered =
            listOf(rect(left = 10f, top = 0f, width = 50f, height = 20f, contentWidth = 110f, align = TextAlign.CENTER))
        // 行起点 40..90；x=45 在行内命中；x=20 在内容框内但行区域外 → 未命中
        assertEquals(0, RenderSession.blockIndexAt(centered, x = 45f, y = 10f, snapDp = 0f))
        assertEquals(-1, RenderSession.blockIndexAt(centered, x = 20f, y = 10f, snapDp = 0f))
    }

    // ---- linkRangeAt：链接区间判定 ----

    private val linkBlock =
        rect(
            left = 0f,
            top = 0f,
            width = 100f,
            height = 24f,
            spans =
                listOf(
                    TextSpan("前缀"),
                    TextSpan("链接文本", href = "https://example.com"),
                    TextSpan("后缀"),
                ),
        )

    @Test
    fun `命中链接 span 返回链接与字符区间`() {
        val link = RenderSession.linkRangeAt(linkBlock.spans, charIndex = 3)
        assertNotNull(link)
        assertEquals("https://example.com", link?.href)
        assertEquals(2, link?.start)
        assertEquals(6, link?.endExclusive)
    }

    @Test
    fun `命中区间左右边界字符都算链接`() {
        assertNotNull(RenderSession.linkRangeAt(linkBlock.spans, charIndex = 2))
        assertNotNull(RenderSession.linkRangeAt(linkBlock.spans, charIndex = 5))
    }

    @Test
    fun `非链接 span 返回 null`() {
        assertNull(RenderSession.linkRangeAt(linkBlock.spans, charIndex = 0))
        assertNull(RenderSession.linkRangeAt(linkBlock.spans, charIndex = 7))
    }

    @Test
    fun `越界字符返回 null`() {
        assertNull(RenderSession.linkRangeAt(linkBlock.spans, charIndex = -1))
        assertNull(RenderSession.linkRangeAt(linkBlock.spans, charIndex = 99))
    }

    @Test
    fun `相邻同 href 的多 span 合并为同一链接区间`() {
        val spans =
            listOf(
                TextSpan("ab", href = "https://x"),
                TextSpan("cd", bold = true, href = "https://x"),
                TextSpan("ef", href = "https://y"),
            )
        val link = RenderSession.linkRangeAt(spans, charIndex = 2)
        assertEquals("https://x", link?.href)
        assertEquals(0, link?.start)
        assertEquals(4, link?.endExclusive)
    }

    // ---- resolveWordRange：词边界收敛 ----

    @Test
    fun `正常词边界原样收敛`() {
        assertEquals(
            3 until 7,
            RenderSession.resolveWordRange(textLength = 10, pos = 4, boundaryStart = 3, boundaryEnd = 7),
        )
    }

    @Test
    fun `边界越界钳制到文本长度`() {
        assertEquals(
            8 until 10,
            RenderSession.resolveWordRange(textLength = 10, pos = 9, boundaryStart = 8, boundaryEnd = 99),
        )
        assertEquals(
            0 until 2,
            RenderSession.resolveWordRange(textLength = 10, pos = 0, boundaryStart = -3, boundaryEnd = 2),
        )
    }

    @Test
    fun `空边界退化为落点单字符`() {
        assertEquals(
            4 until 5,
            RenderSession.resolveWordRange(textLength = 10, pos = 4, boundaryStart = 4, boundaryEnd = 4),
        )
        assertEquals(
            4 until 5,
            RenderSession.resolveWordRange(textLength = 10, pos = 4, boundaryStart = 7, boundaryEnd = 3),
        )
    }

    @Test
    fun `落点在文本末尾时锚定最后一字符`() {
        assertEquals(
            9 until 10,
            RenderSession.resolveWordRange(textLength = 10, pos = 10, boundaryStart = 0, boundaryEnd = 0),
        )
    }

    @Test
    fun `空文本返回 null`() {
        assertNull(RenderSession.resolveWordRange(textLength = 0, pos = 0, boundaryStart = 0, boundaryEnd = 1))
    }

    // ---- pxToDp 换算 ----

    @Test
    fun `物理像素换算为画布dp`() {
        assertEquals(40f, RenderSession.pxToDp(px = 110f, density = 2.75f), 0.001f)
        assertEquals(110f, RenderSession.pxToDp(px = 110f, density = 0f), 0.001f)
    }

    // ---- 会话生命周期：release 幂等与失效 ----

    @Test
    fun `release 后查询失效且幂等`() {
        val session = RenderSession.fromRects(rects = stacked, bitmapWidthPx = 400, bitmapHeightPx = 200, density = 2f)
        assertTrue(session.hitsTextAt(40f, 40f))
        session.release()
        session.release() // 幂等：重复释放无异常
        assertFalse(session.hitsTextAt(40f, 40f))
        assertNull(session.linkHitAt(40f, 40f))
        assertNull(session.wordRangeAt(40f, 40f))
    }

    @Test
    fun `无 skia Paragraph 的块几何命中但交互查询安全返回 null`() {
        val session = RenderSession.fromRects(rects = stacked, bitmapWidthPx = 400, bitmapHeightPx = 200, density = 2f)
        assertTrue(session.hitsTextAt(40f, 40f))
        assertNull(session.linkHitAt(40f, 40f))
        assertNull(session.wordRangeAt(40f, 40f))
        assertFalse(session.hitsTextAt(500f, 500f))
    }

    @Test
    fun `会话尺寸记录物理像素`() {
        val session =
            RenderSession.fromRects(rects = stacked, bitmapWidthPx = 1100, bitmapHeightPx = 550, density = 2.75f)
        assertEquals(1100, session.bitmapWidthPx)
        assertEquals(550, session.bitmapHeightPx)
        assertEquals(2.75f, session.density, 0.001f)
    }
}
