package com.mistbell.tavern.android.render.litehtml

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build

/**
 * litehtml 绘制回调的 Android 实现。字号单位是 CSS px；调用方先把 Canvas
 * 按 density 缩放，因此这里不再乘密度。
 *
 * 各绘制回调被 JNI（android_container.cpp）按名字+描述符 GetStaticMethodID 绑定，
 * 参数平铺即 C++ 侧绘制契约，签名不可改、函数不能合并；私有辅助沿用同一"矩形+圆角"形态。
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal object LiteHtmlPaint {
    private const val BOLD_WEIGHT = 600

    /** CSS font-weight 规范允许的上限（1–1000），网页可能给出越界值，交给 Typeface 前先夹紧。 */
    private const val MAX_CSS_FONT_WEIGHT = 1000

    @JvmStatic
    fun fontMetrics(
        sizePx: Float,
        weight: Int,
        italic: Boolean,
    ): FloatArray {
        val paint = paint(sizePx, weight, italic)
        val metrics = paint.fontMetrics
        val bounds = android.graphics.Rect()
        paint.getTextBounds("x", 0, 1, bounds)
        return floatArrayOf(
            -metrics.ascent,
            metrics.descent,
            bounds.height().toFloat(),
            paint.measureText("0"),
        )
    }

    @JvmStatic
    fun textWidth(
        text: String,
        sizePx: Float,
        weight: Int,
        italic: Boolean,
    ): Float = paint(sizePx, weight, italic).measureText(text)

    @JvmStatic
    fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        sizePx: Float,
        weight: Int,
        italic: Boolean,
        color: Int,
    ) {
        val paint = paint(sizePx, weight, italic)
        paint.color = color
        canvas.drawText(text, x, baseline, paint)
    }

    @JvmStatic
    fun fill(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        color: Int,
    ) {
        val paint = fillPaint(color)
        val rect = RectF(left, top, right, bottom)
        if (radius > 0f) {
            canvas.drawRoundRect(rect, radius, radius, paint)
        } else {
            canvas.drawRect(rect, paint)
        }
    }

    @JvmStatic
    fun fillOval(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
    ) {
        canvas.drawOval(RectF(left, top, right, bottom), fillPaint(color))
    }

    @JvmStatic
    fun fillLinear(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        colors: IntArray,
        stops: FloatArray,
    ) {
        if (colors.isEmpty()) return
        val paint = fillPaint(colors.last())
        paint.shader = LinearGradient(x0, y0, x1, y1, colors, stops, android.graphics.Shader.TileMode.CLAMP)
        drawFilled(canvas, left, top, right, bottom, radius, paint)
    }

    @JvmStatic
    fun fillRadial(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        cx: Float,
        cy: Float,
        gradientRadius: Float,
        colors: IntArray,
        stops: FloatArray,
    ) {
        if (colors.isEmpty() || gradientRadius <= 0f) return
        val paint = fillPaint(colors.last())
        paint.shader = RadialGradient(cx, cy, gradientRadius, colors, stops, android.graphics.Shader.TileMode.CLAMP)
        drawFilled(canvas, left, top, right, bottom, radius, paint)
    }

    @JvmStatic
    fun fillSweep(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        cx: Float,
        cy: Float,
        colors: IntArray,
        stops: FloatArray,
    ) {
        if (colors.isEmpty()) return
        val paint = fillPaint(colors.last())
        paint.shader = SweepGradient(cx, cy, colors, stops)
        drawFilled(canvas, left, top, right, bottom, radius, paint)
    }

    @JvmStatic
    fun stroke(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        strokeWidth: Float,
        color: Int,
    ) {
        if (strokeWidth <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = color
        val inset = strokeWidth / 2f
        val rect = RectF(left + inset, top + inset, right - inset, bottom - inset)
        if (radius > 0f) {
            canvas.drawRoundRect(rect, radius, radius, paint)
        } else {
            canvas.drawRect(rect, paint)
        }
    }

    /** @return Canvas.save 的计数，供 [restore] 成对使用 */
    @JvmStatic
    fun clip(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Int {
        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        return save
    }

    @JvmStatic
    fun restore(
        canvas: Canvas,
        saveCount: Int,
    ) {
        canvas.restoreToCount(saveCount)
    }

    @JvmStatic
    fun transformText(
        text: String,
        mode: Int,
    ): String =
        when (mode) {
            TRANSFORM_CAPITALIZE ->
                text.replace(WORD) { match ->
                    match.value.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                    }
                }
            TRANSFORM_UPPERCASE -> text.uppercase()
            TRANSFORM_LOWERCASE -> text.lowercase()
            else -> text
        }

    private fun drawFilled(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        paint: Paint,
    ) {
        val save = canvas.save()
        val rect = RectF(left, top, right, bottom)
        if (radius > 0f) {
            val path = android.graphics.Path()
            path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            canvas.clipPath(path)
        } else {
            canvas.clipRect(rect)
        }
        canvas.drawRect(rect, paint)
        canvas.restoreToCount(save)
    }

    private fun fillPaint(color: Int): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = color
        return paint
    }

    private fun paint(
        sizePx: Float,
        weight: Int,
        italic: Boolean,
    ): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = sizePx
        paint.typeface = typeface(weight, italic)
        return paint
    }

    private fun typeface(
        weight: Int,
        italic: Boolean,
    ): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.SANS_SERIF, weight.coerceIn(1, MAX_CSS_FONT_WEIGHT), italic)
        } else {
            val style =
                when {
                    italic && weight >= BOLD_WEIGHT -> Typeface.BOLD_ITALIC
                    weight >= BOLD_WEIGHT -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            Typeface.create(Typeface.SANS_SERIF, style)
        }

    private val WORD = Regex("\\b\\p{L}")

    private const val TRANSFORM_CAPITALIZE = 1
    private const val TRANSFORM_UPPERCASE = 2
    private const val TRANSFORM_LOWERCASE = 3
}
