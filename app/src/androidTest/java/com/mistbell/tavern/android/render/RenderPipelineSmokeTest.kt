package com.mistbell.tavern.android.render

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.render.engine.BitmapCache
import com.mistbell.tavern.android.render.engine.BitmapCacheKey
import com.mistbell.tavern.android.render.engine.EngineBootstrap
import com.mistbell.tavern.android.render.engine.PanelRenderer
import com.mistbell.tavern.android.render.engine.RenderRequest
import com.mistbell.tavern.android.render.model.ContentSegment
import com.mistbell.tavern.android.render.model.ThemeVars
import com.mistbell.tavern.android.render.parse.CssParser
import com.mistbell.tavern.android.render.parse.ParsedStylesheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap as AndroidBitmap

/**
 * M3 渲染管线冒烟 instrumented 测试：真实 MVU 面板 + Markdown 叙述段经
 * PanelRenderer（SlotFiller → InlineCollector → LayoutSpec → Yoga → Skia → BitmapBridge）
 * 全链光栅到 android.graphics.Bitmap。
 *
 * 断言：bitmap 尺寸（400dp × density 2.75 = 1100px 宽）、像素方差（多色存在）、隐藏行剪枝
 * （专属红色不出现）、PNG 落盘（getExternalFilesDir，供人工查看：mvu_panel.png / narrative.png）。
 * 走 M1 spike 验证过的 API 路径（Surface.makeRasterN32Premul + readPixels 回传），bootstrap
 * 统一经 EngineBootstrap（SoLoader.init + FontCollection）。
 *
 * 运行（保持安装状态以便拉取 PNG，勿用 connected 任务——其结束会卸载应用）：
 * ```
 * gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -e class com.mistbell.tavern.android.render.RenderPipelineSmokeTest \
 *   com.mistbell.tavern.android.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/com.mistbell.tavern.android/files/mvu_panel.png .
 * adb pull /sdcard/Android/data/com.mistbell.tavern.android/files/narrative.png .
 * ```
 * skiko 原生库只有 x86_64/arm64 产物：模拟器（x86_64）与真机（arm64）可跑。
 */
@RunWith(AndroidJUnit4::class)
class RenderPipelineSmokeTest {
    companion object {
        private const val TAG = "RenderPipelineSmoke"
        private const val WIDTH_DP = 400f
        private const val DENSITY = 2.75f

        /** 隐藏行专属背景红：若剪枝正确，全图不应出现该颜色 */
        private const val HIDDEN_ROW_RED = 0xFFE02020.toInt()

        /** 主题主色（badge 前景）：--mt-primary 注入值 */
        private const val PRIMARY_HEX = "#7c5cff"
        private const val PRIMARY_RGB = 0xFF7C5CFF.toInt()

        private val PANEL_CSS =
            """
            .mvu { display: flex; flex-direction: column; padding: 12px; background-color: #ffffff;
                   border: 1px solid var(--mt-primary, #999999); border-radius: 12px; gap: 8px; }
            .topbar { display: flex; flex-direction: row; justify-content: space-between; align-items: center; }
            .title { font-size: 18px; font-weight: 700; color: #111111; }
            .subtitle { font-size: 12px; color: #888888; }
            .grid { display: flex; flex-direction: column; gap: 6px; }
            .row { display: flex; flex-direction: row; justify-content: space-between; padding: 6px 10px;
                   background-color: #f2f3f5; border-radius: 8px; }
            .label { font-size: 14px; color: #333333; }
            .badge { font-size: 13px; font-weight: 700; }
            .good { color: var(--mt-primary); }
            .warn { color: #c1843b; }
            .info { color: #2563eb; }
            .secret { color: #ffffff; background-color: #e02020; }
            .hidden { background-color: #e02020; }
            .footer { font-size: 12px; color: #666666; }
            """.trimIndent()

        /** 真实感 MVU 面板：顶栏 + flex 网格状态行（中文标签 + 彩色数值徽章）+ 隐藏行 + inline style */
        private val PANEL_HTML =
            """
            <div class="mvu">
              <div class="topbar"><span class="title">状态面板</span><span class="subtitle">深度之界 · 第 3 天</span></div>
              <div class="grid">
                <div class="row"><span class="label">好感度</span><span class="badge good">+12</span></div>
                <div class="row"><span class="label">精力</span><span class="badge warn">72/100</span></div>
                <div class="row"><span class="label">金币</span><span class="badge info">1,208</span></div>
                <div class="row hidden" data-bind:visible="state.显示隐藏行"><span class="label">隐匿标记</span><span class="badge secret">机密</span></div>
              </div>
              <div class="footer" style="color: var(--mt-on-surface, #666666)">数值每回合更新 · 由 MVU 驱动</div>
            </div>
            """.trimIndent()

        private val NARRATIVE_MARKDOWN =
            """
            ## 夜色下的码头

            她握紧栏杆，望向远处的水面（指尖微微发白）。"你真的要走吗？" 她问。

            - 清点物资
            - 修补船帆
            1. 先离港
            2. 再谈航线

            **逆风** 适合夜航，详情见 [航海日志](https://example.com/log)。
            """.trimIndent()

        private const val WHITE = 0xFFFFFFFF.toInt()

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            // Yoga（SoLoader.loadLibrary）+ skiko 字体收集器统一经 EngineBootstrap 引导
            EngineBootstrap.init(ApplicationProvider.getApplicationContext<Context>())
        }

        private fun stylesheet(): ParsedStylesheet = CssParser.parse(PANEL_CSS, ThemeVars(mapOf("--mt-primary" to PRIMARY_HEX)))

        private fun writePng(
            context: Context,
            name: String,
            bitmap: AndroidBitmap,
        ): File {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val png = File(dir, name)
            FileOutputStream(png).use { out -> bitmap.compress(AndroidBitmap.CompressFormat.PNG, 100, out) }
            return png
        }
    }

    /** 1) MVU 面板全管线：尺寸、像素方差、隐藏行剪枝、renderMs、PNG 落盘。 */
    @Test
    fun mvuPanelFullPipelineRender() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val renderer = PanelRenderer()
        val result =
            renderer.render(
                RenderRequest(
                    panels = listOf(PANEL_HTML),
                    widthDp = WIDTH_DP,
                    density = DENSITY,
                    themeStylesheet = stylesheet(),
                    themeVars = ThemeVars(mapOf("--mt-primary" to PRIMARY_HEX)),
                    canvasBackgroundArgb = WHITE,
                ),
            )
        assertTrue("渲染不应回退：${result.diagnostics}", !result.fallback)
        val bitmap = result.bitmap
        assertNotNull("无位图产出", bitmap)
        bitmap!!

        // 尺寸：宽 400dp × 2.75 = 1100px；高自适应（预期面板 ~150dp，容差 200..900px）
        assertEquals(1100, bitmap.width)
        assertTrue("高度超出容差：${bitmap.height}px", bitmap.height in 200..900)

        // 像素方差 + 隐藏行剪枝：全图扫描
        val distinct = HashSet<Int>()
        var redPixels = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                distinct += pixel
                if (pixel == HIDDEN_ROW_RED) redPixels++
            }
        }
        Log.i(TAG, "MVU renderMs=${result.renderMs} distinct=${distinct.size} red=$redPixels size=${bitmap.width}x${bitmap.height}")
        assertTrue("像素方差不足（distinct=${distinct.size}）", distinct.size >= 5)
        assertEquals("隐藏行红色出现（data-bind:visible 未剪枝）", 0, redPixels)

        val png = writePng(context, "mvu_panel.png", bitmap)
        Log.i(TAG, "MVU png=${png.absolutePath} bytes=${png.length()}")
        assertTrue("PNG 未写成功", png.exists() && png.length() > 0)
    }

    /** 2) Markdown 叙述段（动作/引号/粗体/链接/列表）走 SlotFiller + 管线：像素非空 + PNG 落盘。 */
    @Test
    fun markdownNarrativeRender() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val renderer = PanelRenderer()
        val result =
            renderer.render(
                RenderRequest(
                    narrative = listOf(ContentSegment.Markdown(NARRATIVE_MARKDOWN)),
                    widthDp = WIDTH_DP,
                    density = DENSITY,
                    canvasBackgroundArgb = WHITE,
                ),
            )
        assertTrue("渲染不应回退：${result.diagnostics}", !result.fallback)
        val bitmap = result.bitmap
        assertNotNull("无位图产出", bitmap)
        bitmap!!

        var nonWhite = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                if (bitmap.getPixel(x, y) != WHITE) nonWhite++
            }
        }
        Log.i(TAG, "Narrative renderMs=${result.renderMs} nonWhite=$nonWhite size=${bitmap.width}x${bitmap.height}")
        assertTrue("叙述段无任何文本像素", nonWhite > 0)

        val png = writePng(context, "narrative.png", bitmap)
        Log.i(TAG, "Narrative png=${png.absolutePath} bytes=${png.length()}")
        assertTrue("PNG 未写成功", png.exists() && png.length() > 0)
    }

    /** 3) 位图缓存：同请求二次渲染命中缓存（不重新光栅），varsVersion 变化即失效。 */
    @Test
    fun bitmapCacheHitAndInvalidation() {
        val cache = BitmapCache<BitmapCacheKey, AndroidBitmap>(BitmapCache.DEFAULT_MAX_BYTES) { it.byteCount }
        val renderer = PanelRenderer(cache = cache)
        val request =
            RenderRequest(
                panels = listOf(PANEL_HTML),
                widthDp = WIDTH_DP,
                density = DENSITY,
                themeStylesheet = stylesheet(),
                themeVars = ThemeVars(mapOf("--mt-primary" to PRIMARY_HEX)),
                canvasBackgroundArgb = WHITE,
            )
        val first = renderer.render(request)
        assertTrue(!first.fallback && !first.fromCache)
        val second = renderer.render(request)
        assertTrue("同请求二次渲染应命中缓存", second.fromCache)
        val changed = renderer.render(requestWithVersion(request, varsVersion = 1L))
        assertTrue("变量版本变化应重新渲染", !changed.fromCache)
    }
}

/** 测试辅助：RenderRequest 非 data class（缓存 hash 排除 imageLoader），按需替换单字段的极简构造器 */
private fun requestWithVersion(
    base: RenderRequest,
    varsVersion: Long,
): RenderRequest =
    RenderRequest(
        template = base.template,
        narrative = base.narrative,
        panels = base.panels,
        widthDp = base.widthDp,
        density = base.density,
        themeStylesheet = base.themeStylesheet,
        themeVars = base.themeVars,
        bindingContext = base.bindingContext,
        varsVersion = varsVersion,
        canvasBackgroundArgb = base.canvasBackgroundArgb,
        imageLoader = base.imageLoader,
        markdownPalette = base.markdownPalette,
    )
