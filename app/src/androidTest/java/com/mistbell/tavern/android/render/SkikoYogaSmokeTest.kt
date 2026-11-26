package com.mistbell.tavern.android.render

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.soloader.SoLoader
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaConstants
import com.facebook.yoga.YogaFlexDirection
import com.facebook.yoga.YogaMeasureFunction
import com.facebook.yoga.YogaMeasureOutput
import com.facebook.yoga.YogaNodeFactory
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import android.graphics.Bitmap as AndroidBitmap
import org.jetbrains.skia.Bitmap as SkikoBitmap

/**
 * M1 spike：主题渲染引擎三依赖（skiko 软件光栅 / Yoga 布局）的冒烟 instrumented 测试。
 *
 * 只验证依赖接入可用 + 关键 API 路径（软件光栅文本、像素回传、CJK 字体探测、flex 布局、
 * 文本测量、回传耗时），不做引擎实现、不做聊天接线。android.graphics.Bitmap 与
 * org.jetbrains.skia.Bitmap 同名，以上用 import 别名区分。
 *
 * spike 关键未知项（结论写进 M1 报告）：
 *  - skiko-android 在 Android 上能否通过默认 FontMgr 拿到系统字体（尤其 CJK）；
 *  - 600x400 光栅 + 像素回传到 android.graphics.Bitmap 的单帧耗时（给 M3 帧预算提供数据）。
 *
 * 日志标记（logcat 按 tag "SkikoYogaSmoke" 过滤）：SKIKO-FONTPROBE / SKIKO-CJKPROBE / SKIKO-BENCH。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest --tests "com.mistbell.tavern.android.render.SkikoYogaSmokeTest"`（需模拟器）。
 * skiko 原生库只有 x86_64/arm64 产物：模拟器（x86_64）与真机（arm64）可跑，armeabi-v7a/x86 设备会加载失败。
 */
@RunWith(AndroidJUnit4::class)
class SkikoYogaSmokeTest {
    companion object {
        private const val TAG = "SkikoYogaSmoke"
        private const val WIDTH = 600
        private const val HEIGHT = 400
        private const val MIXED_TEXT = "好感度 88 Test123"
        private const val WHITE_BG = 0xFFFFFFFF.toInt()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Yoga 的 YogaNative static 块走 SoLoader.loadLibrary("yoga")，首次使用前必须 init
            SoLoader.init(ApplicationProvider.getApplicationContext<Context>(), 0)
        }
    }

    /** a) skiko 软件光栅文本：Surface → Paragraph → 像素回传 android.graphics.Bitmap → PNG 落盘。 */
    @Test
    fun skikoRasterTextAndPixelRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val canvas = surface.canvas
        canvas.clear(WHITE_BG)

        val fontCollection = FontCollection()
        fontCollection.setDefaultFontManager(FontMgr.default)
        val builder = ParagraphBuilder(ParagraphStyle(), fontCollection)
        val style = TextStyle()
        style.color = 0xFF000000.toInt()
        style.fontSize = 40f
        builder.pushStyle(style)
        builder.addText(MIXED_TEXT)
        builder.popStyle()
        val paragraph = builder.build()
        paragraph.layout(WIDTH.toFloat())
        paragraph.paint(canvas, 20f, 120f)

        // 回传路径：skiko Surface → skiko Bitmap → 字节数组 → android.graphics.Bitmap
        val skikoBitmap = SkikoBitmap()
        skikoBitmap.allocN32Pixels(WIDTH, HEIGHT, false)
        assertTrue("surface.readPixels 失败", surface.readPixels(skikoBitmap, 0, 0))
        val pixels = skikoBitmap.readPixels() ?: error("bitmap.readPixels 返回 null")
        val androidBitmap = AndroidBitmap.createBitmap(WIDTH, HEIGHT, AndroidBitmap.Config.ARGB_8888)
        androidBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))

        var nonBackground = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                // 背景是 premul 白，文本像素（黑/抗锯齿灰）与其不同
                if (androidBitmap.getPixel(x, y) != WHITE_BG) nonBackground++
            }
        }
        Log.i(
            TAG,
            "SKIKO-RENDER nonBackgroundPixels=$nonBackground maxIntrinsicWidth=${paragraph.maxIntrinsicWidth}",
        )
        assertTrue("光栅化无任何文本像素（回传链路或字体管理器异常）", nonBackground > 0)

        // PNG 落盘供人工查看：/sdcard/Android/data/com.mistbell.tavern.android/files/skiko_smoke.png
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val png = File(dir, "skiko_smoke.png")
        FileOutputStream(png).use { out ->
            androidBitmap.compress(AndroidBitmap.CompressFormat.PNG, 100, out)
        }
        Log.i(TAG, "SKIKO-RENDER png=${png.absolutePath} bytes=${png.length()}")
        assertTrue("PNG 未写成功", png.exists() && png.length() > 0)
        surface.close()
        skikoBitmap.close()
        androidBitmap.recycle()
    }

    /** b) 系统字体/CJK 可用性探测：拉丁必须可用（硬断言），CJK 只记录结果（软断言）。 */
    @Test
    fun systemFontProbeLatinHardCjkSoft() {
        val fontMgr = FontMgr.default
        val families = fontMgr.familiesCount
        val names = (0 until min(families, 30)).map { fontMgr.getFamilyName(it) }
        Log.i(TAG, "SKIKO-FONTPROBE families=$families first=$names")

        // 拉丁字形必须能解析（skiko 接入的最低底线）
        val latinTypeface =
            fontMgr.matchFamiliesStyleCharacter(
                arrayOf("sans-serif"),
                FontStyle.NORMAL,
                arrayOf<String>(),
                'T'.code,
            )
        assertTrue("默认 FontMgr 找不到拉丁字体（families=$families）", latinTypeface != null)
        assertTrue("拉丁 glyph 为 0（tofu）", latinTypeface!!.getUTF32Glyph('T'.code) != 0.toShort())

        // CJK（spike 关键未知项）：只记录，不硬断言；结论看 SKIKO-CJKPROBE 日志
        val cjkTypeface =
            fontMgr.matchFamiliesStyleCharacter(
                arrayOf("sans-serif"),
                FontStyle.NORMAL,
                arrayOf<String>(),
                '好'.code,
            )
        val cjkGlyph = cjkTypeface?.getUTF32Glyph('好'.code) ?: 0.toShort()
        Log.i(TAG, "SKIKO-CJKPROBE typefaceFound=${cjkTypeface != null} glyphOk=${cjkGlyph != 0.toShort()}")
    }

    /** b-2) CJK 软断言渲染：字形缺失时 Assume 跳过（不算失败），拿到字形则必须真的画出像素。 */
    @Test
    fun cjkRenderSoftProbe() {
        val fontMgr = FontMgr.default
        val cjkTypeface =
            fontMgr.matchFamiliesStyleCharacter(
                arrayOf("sans-serif"),
                FontStyle.NORMAL,
                arrayOf<String>(),
                '好'.code,
            )
        val cjkGlyph = cjkTypeface?.getUTF32Glyph('好'.code) ?: 0.toShort()
        assumeTrue(
            "skiko 默认 FontMgr 拿不到 CJK 字形（候选方案：TypefaceFontProvider 喂 android.graphics.Typeface / 捆绑字体）",
            cjkGlyph != 0.toShort(),
        )

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val canvas = surface.canvas
        canvas.clear(WHITE_BG)
        val fontCollection = FontCollection()
        fontCollection.setDefaultFontManager(fontMgr)
        val builder = ParagraphBuilder(ParagraphStyle(), fontCollection)
        val style = TextStyle()
        style.color = 0xFF000000.toInt()
        style.fontSize = 40f
        builder.pushStyle(style)
        builder.addText("好感度")
        builder.popStyle()
        val paragraph = builder.build()
        paragraph.layout(WIDTH.toFloat())
        paragraph.paint(canvas, 20f, 120f)

        val skikoBitmap = SkikoBitmap()
        skikoBitmap.allocN32Pixels(WIDTH, HEIGHT, false)
        assertTrue(surface.readPixels(skikoBitmap, 0, 0))
        val pixels = skikoBitmap.readPixels() ?: error("bitmap.readPixels 返回 null")
        // premul 白底每像素 4 字节为 [FF,FF,FF,FF]；文本像素至少一个 RGB 字节非 FF（alpha 字节恒 FF 不参与）
        var nonBackground = 0
        var i = 0
        while (i < pixels.size) {
            if ((pixels[i].toInt() and 0xFF) != 0xFF || (pixels[i + 1].toInt() and 0xFF) != 0xFF ||
                (pixels[i + 2].toInt() and 0xFF) != 0xFF
            ) {
                nonBackground++
            }
            i += 4
        }
        Log.i(TAG, "SKIKO-CJKRENDER nonBackgroundPixels=$nonBackground")
        assertTrue("CJK 文本渲染无像素（豆腐块）", nonBackground > 0)
        surface.close()
        skikoBitmap.close()
    }

    /** c-1) Yoga flex 布局：父 fixed 400x200，两个 flexGrow=1 的子节点各分 200 宽。 */
    @Test
    fun yogaFlexLayout() {
        val root = YogaNodeFactory.create()
        root.setWidth(400f)
        root.setHeight(200f)
        root.setFlexDirection(YogaFlexDirection.ROW)
        val left = YogaNodeFactory.create()
        left.setFlexGrow(1f)
        val right = YogaNodeFactory.create()
        right.setFlexGrow(1f)
        root.addChildAt(left, 0)
        root.addChildAt(right, 1)

        root.calculateLayout(YogaConstants.UNDEFINED, YogaConstants.UNDEFINED)

        assertEquals(400f, root.layoutWidth, 0.01f)
        assertEquals(200f, root.layoutHeight, 0.01f)
        assertEquals(200f, left.layoutWidth, 0.01f)
        assertEquals(200f, left.layoutHeight, 0.01f)
        assertEquals(0f, left.layoutX, 0.01f)
        assertEquals(200f, right.layoutWidth, 0.01f)
        assertEquals(200f, right.layoutX, 0.01f)
    }

    /** c-2) Yoga setMeasureFunction：模拟文本测量，子节点取测量函数返回的尺寸。 */
    @Test
    fun yogaMeasureFunction() {
        val container = YogaNodeFactory.create()
        container.setWidth(200f)
        container.setHeight(50f)
        // 默认 align-items: stretch 会把叶子节点拉伸到容器交叉轴宽度（Yoga 遵循 CSS
        // flexbox 语义，实测 layoutWidth=200）；measure 值只在非拉伸轴生效。
        // 引擎文本节点须显式 FLEX_START（等价 align-self: flex-start）才能拿测量宽。
        container.setAlignItems(YogaAlign.FLEX_START)
        val text = YogaNodeFactory.create()
        text.setMeasureFunction(
            YogaMeasureFunction { _, _, _, _, _ -> YogaMeasureOutput.make(80f, 24f) },
        )
        container.addChildAt(text, 0)

        container.calculateLayout(YogaConstants.UNDEFINED, YogaConstants.UNDEFINED)

        assertEquals(200f, container.layoutWidth, 0.01f)
        assertEquals(80f, text.layoutWidth, 0.01f)
        assertEquals(24f, text.layoutHeight, 0.01f)
    }

    /** d) 像素回传性能粗测：20 次 600x400 光栅 + readPixels + copyPixelsFromBuffer，给 M3 帧预算提供数据。 */
    @Test
    fun pixelRoundTripBenchmark() {
        val rounds = 20
        var totalNs = 0L
        repeat(rounds) {
            val start = System.nanoTime()
            val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
            val canvas = surface.canvas
            canvas.clear(WHITE_BG)
            val paint = Paint()
            canvas.drawRect(Rect.makeXYWH(50f, 50f, 200f, 100f), paint)

            val skikoBitmap = SkikoBitmap()
            skikoBitmap.allocN32Pixels(WIDTH, HEIGHT, false)
            assertTrue(surface.readPixels(skikoBitmap, 0, 0))
            val bytes = skikoBitmap.readPixels() ?: error("bitmap.readPixels 返回 null")
            val androidBitmap = AndroidBitmap.createBitmap(WIDTH, HEIGHT, AndroidBitmap.Config.ARGB_8888)
            androidBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

            surface.close()
            skikoBitmap.close()
            androidBitmap.recycle()
            paint.close()
            totalNs += System.nanoTime() - start
        }
        val avgMs = totalNs.toDouble() / rounds / 1_000_000.0
        Log.i(TAG, "SKIKO-BENCH rounds=$rounds size=${WIDTH}x$HEIGHT avgMs=$avgMs")
    }
}
