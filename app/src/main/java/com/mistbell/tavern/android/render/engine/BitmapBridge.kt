package com.mistbell.tavern.android.render.engine

import android.graphics.Bitmap
import org.jetbrains.skia.Surface
import java.nio.ByteBuffer
import org.jetbrains.skia.Bitmap as SkikoBitmap

/**
 * 位图回传桥（M3）：skiko Surface → android.graphics.Bitmap。
 * 路径照抄 M1 spike 已验证链路（Surface.readPixels → skiko Bitmap.readPixels():ByteArray →
 * android Bitmap.copyPixelsFromBuffer），但 N32 回传字节序需交换 R/B（见 [wrapToBitmap]）。
 * 引擎唯一的 android.graphics.Bitmap 生产出口之一（另一处 PanelRenderer）。
 */
object BitmapBridge {
    /**
     * 回传整幅 surface 像素为 ARGB_8888 Bitmap；readPixels 失败返回 null（调用方按 fallback 处理）。
     * [widthPx]/[heightPx] 为像素尺寸（与 surface 一致）。skiko Bitmap 由本函数关闭。
     */
    fun capture(
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        val skikoBitmap = SkikoBitmap()
        try {
            skikoBitmap.allocN32Pixels(widthPx, heightPx, false)
            val pixels =
                if (surface.readPixels(skikoBitmap, 0, 0)) {
                    skikoBitmap.readPixels()
                } else {
                    null
                }
            return pixels?.let { bytes -> wrapToBitmap(bytes, widthPx, heightPx) }
        } finally {
            skikoBitmap.close()
        }
    }

    /**
     * 字节缓冲 → android.graphics.Bitmap（双方均 4 字节/像素）。
     * skiko 的 N32 回传字节序是 **BGRA**，android ARGB_8888 缓冲字节序是 **RGBA**——
     * 须逐像素交换 R/B，否则全部颜色红蓝对调（M1 spike 只验过黑白灰未暴露；
     * M3 冒烟用彩色徽章发现，见 mvuPanelFullPipelineRender）。
     */
    private fun wrapToBitmap(
        pixels: ByteArray,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        var i = 0
        val limit = pixels.size - PIXEL_STRIDE
        while (i <= limit) {
            val red = pixels[i]
            pixels[i] = pixels[i + 2]
            pixels[i + 2] = red
            i += PIXEL_STRIDE
        }
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        out.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        return out
    }

    private const val PIXEL_STRIDE = 4
}
