package com.mistbell.tavern.android.render

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.render.engine.EngineBootstrap
import com.mistbell.tavern.android.render.engine.PanelRenderer
import com.mistbell.tavern.android.render.engine.RenderRequest
import com.mistbell.tavern.android.render.engine.RenderResult
import com.mistbell.tavern.android.render.engine.RenderSession
import com.mistbell.tavern.android.render.model.ContentSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5 交互会话冒烟 instrumented 测试（真实 skia Paragraph 命中查询）：
 * 渲染含 Markdown 链接的已知叙述面板 → session.linkHitAt 命中预设链接（href 与按压矩形），
 * session.wordRangeAt 返回非空选区与正数文本/矩形，release 幂等且查询失效。
 *
 * 只测会话几何查询，不测 UI 手势（主会话已有人工路径）；运行方式与 M3 冒烟一致：
 * ```
 * gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -e class com.mistbell.tavern.android.render.RenderInteractionSmokeTest \
 *   com.mistbell.tavern.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RenderInteractionSmokeTest {
    companion object {
        private const val WIDTH_DP = 400f
        private const val DENSITY = 2.75f

        /** 预设链接（叙述段 Markdown 链接 → TextSpan.href） */
        private const val LINK_HREF = "https://example.com/log"

        private val NARRATIVE =
            """
            ## 航行日志

            夜航的补给与航线已就绪，详情见 [航海日志]($LINK_HREF)。

            **逆风** 适合夜航，明日清晨离港。
            """.trimIndent()

        /** 命中扫描步长（dp）：远小于链接文本尺寸，网格必命中 */
        private const val SCAN_STEP_DP = 8f

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            EngineBootstrap.init(ApplicationProvider.getApplicationContext<Context>())
        }

        /** 渲染一次含链接的叙述面板 */
        private fun renderOnce(): RenderResult =
            PanelRenderer().render(
                RenderRequest(
                    narrative = listOf(ContentSegment.Markdown(NARRATIVE)),
                    widthDp = WIDTH_DP,
                    density = DENSITY,
                ),
            )

        /** 位图高度（画布 dp） */
        private fun bitmapHeightDpOf(result: RenderResult): Float = (result.bitmap?.height ?: 0) / DENSITY

        /** 网格扫描直到 predicate 命中（返回扫描点坐标 dp）；用尽返回 null */
        private fun scanPoint(
            session: RenderSession,
            maxYDp: Float,
            predicate: (Float, Float) -> Boolean,
        ): Pair<Float, Float>? {
            var y = SCAN_STEP_DP
            while (y < maxYDp) {
                var x = SCAN_STEP_DP
                while (x < WIDTH_DP) {
                    if (predicate(x, y)) return x to y
                    x += SCAN_STEP_DP
                }
                y += SCAN_STEP_DP
            }
            return null
        }
    }

    @Test
    fun `linkHitAt命中预设链接`() {
        val result = renderOnce()
        assertFalse("渲染不应失败", result.fallback)
        assertNotNull("渲染成功应携带交互会话", result.session)
        val session = result.session!!
        val bitmapHeightDp = bitmapHeightDpOf(result)
        assertTrue(bitmapHeightDp > 0f)

        val scan = scanPoint(session, bitmapHeightDp) { x, y -> session.linkHitAt(x, y) != null }
        assertNotNull("网格扫描应在链接文本处命中", scan)
        val link = session.linkHitAt(scan!!.first, scan.second)
        assertEquals(LINK_HREF, link?.href)
        val press = link?.pressRect
        assertNotNull("按压矩形应随命中返回", press)
        assertTrue("按压矩形应为正面积", press!!.width() > 0f && press.height() > 0f)
        assertTrue("按压矩形应在画布内", press.left >= 0f && press.top >= 0f && press.right <= WIDTH_DP + 1f)

        // 画布右下远端空白区不命中链接
        assertNull(session.linkHitAt(WIDTH_DP - 1f, bitmapHeightDp - 1f))
    }

    @Test
    fun `wordRangeAt返回非空选区与正数矩形`() {
        val result = renderOnce()
        assertNotNull("渲染成功应携带交互会话", result.session)
        val session = result.session!!
        val bitmapHeightDp = bitmapHeightDpOf(result)

        val scan = scanPoint(session, bitmapHeightDp) { x, y -> session.wordRangeAt(x, y) != null }
        assertNotNull("网格扫描应在文本处选出词", scan)
        val selection = session.wordRangeAt(scan!!.first, scan.second)
        assertNotNull(selection)
        assertTrue("选中文本应为正数非空白", selection!!.selectedText.isNotBlank())
        assertTrue("选区矩形不应为空", selection.selectionRects.isNotEmpty())
        assertEquals("两套坐标数量一致", selection.selectionRects.size, selection.selectionRectsDp.size)
        val bitmapWidthPx = result.bitmap?.width ?: 0
        val bitmapHeightPx = result.bitmap?.height ?: 0
        for (rect in selection.selectionRects) {
            assertTrue("选区矩形应为正面积", rect.width() > 0f && rect.height() > 0f)
            assertTrue("选区应为位图物理像素坐标", rect.left >= 0f && rect.top >= 0f)
            assertTrue(rect.right <= bitmapWidthPx + 1f)
            assertTrue(rect.bottom <= bitmapHeightPx + 1f)
        }
        for (rect in selection.selectionRectsDp) {
            assertTrue(rect.left >= 0f && rect.top >= 0f && rect.right <= WIDTH_DP + 1f)
        }
    }

    @Test
    fun `release幂等且查询失效`() {
        val result = renderOnce()
        assertNotNull("渲染成功应携带交互会话", result.session)
        val session = result.session!!
        assertTrue(session.hitsTextAt(20f, 20f))
        session.release()
        session.release() // 幂等
        assertFalse(session.hitsTextAt(20f, 20f))
        assertNull(session.linkHitAt(20f, 20f))
        assertNull(session.wordRangeAt(20f, 20f))
    }
}
