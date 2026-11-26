package com.mistbell.tavern.android.render.litehtml

import android.graphics.Bitmap
import android.util.LruCache

/**
 * litehtml v0.10 的 Android 入口。布局和 CSS 走上游库，绘制走 [LiteHtmlPaint]。
 * 宽度单位是 CSS px（与 dp 1:1），[density] 只用于位图像素尺寸。
 *
 * 聊天气泡用它画 style / table。图片加载、逐边边框和脚本都不在这里。
 */
object LiteHtmlRenderer {
    private const val CACHE_MAX_BYTES = 12 * 1024 * 1024

    private val frames =
        object : LruCache<String, Bitmap>(CACHE_MAX_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount.coerceAtLeast(1)
        }

    private val renderLock = Any()

    init {
        System.loadLibrary("litehtmlbridge")
    }

    fun render(
        html: String,
        widthCssPx: Int,
        density: Float,
    ): Bitmap {
        require(html.isNotEmpty()) { "html 不能为空" }
        require(widthCssPx > 0) { "宽度必须大于 0" }
        require(density > 0f) { "density 必须大于 0" }
        val key = "$widthCssPx|$density|$html"
        frames.get(key)?.let { return it }
        val bitmap = synchronized(renderLock) { nativeRender(html, widthCssPx, density) }
        frames.put(key, bitmap)
        return bitmap
    }

    private external fun nativeRender(
        html: String,
        widthCssPx: Int,
        density: Float,
    ): Bitmap
}
