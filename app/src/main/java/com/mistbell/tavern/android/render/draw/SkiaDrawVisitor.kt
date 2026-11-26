package com.mistbell.tavern.android.render.draw

import com.mistbell.tavern.android.render.engine.RenderHitBlock
import com.mistbell.tavern.android.render.engine.TextMeasurer
import com.mistbell.tavern.android.render.layout.LayoutLeaf
import com.mistbell.tavern.android.render.layout.YogaBuiltNode
import com.mistbell.tavern.android.render.model.CssUnit
import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.ImageLoader
import com.mistbell.tavern.android.render.model.ImageSpec
import com.mistbell.tavern.android.render.model.Length
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.TextRect
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * Skia 绘制遍历（M3）：按 Yoga 布局结果深度优先光栅化。
 *
 * 每节点：背景色圆角矩形 → border 描边 → 叶子内容（TextBlock → paragraph.paint；
 * ImageSpec → Image.makeFromEncoded 后 fit 居中于节点内容框）；opacity 经 saveLayer 乘入；
 * visibility:hidden 占位不绘制（display:none 已在 LayoutSpecBuilder 剪枝）。
 * 同时收集 TextRect（块画布矩形 + span）与 [RenderHitBlock]（矩形 + 绘制所用 Paragraph，
 * M5 交互会话的原料）与图片失败诊断。
 * 画布坐标系为 dp（宿主在绘制前 scale(density)）。
 */
class SkiaDrawVisitor(
    private val measurer: TextMeasurer,
    private val imageLoader: ImageLoader?,
) {
    /** M5 命中测试矩形（画布 dp 坐标，累积绝对位置） */
    val textRects: List<TextRect> get() = textRectList.toList()

    /** M5 交互会话块（TextRect + 绘制所用 Paragraph；Paragraph 归 TextMeasurer 缓存所有） */
    val hitBlocks: List<RenderHitBlock> get() = hitBlockList.toList()

    /** 图片加载/解码失败诊断 */
    val diagnostics: List<Diagnostic> get() = diagnosticList.toList()

    private val textRectList = mutableListOf<TextRect>()
    private val hitBlockList = mutableListOf<RenderHitBlock>()
    private val diagnosticList = mutableListOf<Diagnostic>()

    /** 从根开始绘制；[root] 的 Yoga 节点须已 calculateLayout */
    fun draw(
        canvas: Canvas,
        root: YogaBuiltNode,
    ) {
        drawNode(canvas, root, 0f, 0f, FULL_ALPHA)
    }

    /** [absX]/[absY] 为节点框画布绝对坐标（dp）；[inheritedAlpha] 为 0..255 累积不透明度 */
    private fun drawNode(
        canvas: Canvas,
        node: YogaBuiltNode,
        absX: Float,
        absY: Float,
        inheritedAlpha: Int,
    ) {
        val yoga = node.yoga
        val x = absX + yoga.layoutX
        val y = absY + yoga.layoutY
        val width = yoga.layoutWidth
        val height = yoga.layoutHeight
        val props = node.spec.props
        val alpha = (inheritedAlpha * props.opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, FULL_ALPHA)
        val visible = width > 0f && height > 0f && !props.visibilityHidden && alpha > 0
        if (visible) {
            // 子树自身透明度 < 1：saveLayer 包裹（层合成时乘入），层内绘制保持全强度
            val layered = alpha < FULL_ALPHA
            if (layered) {
                val layerPaint = Paint()
                layerPaint.alpha = alpha
                canvas.saveLayer(Rect.makeXYWH(x, y, width, height), layerPaint)
            }
            try {
                drawBox(canvas, node, x, y)
                drawLeafContent(canvas, node, x, y)
                for (child in node.children) {
                    drawNode(canvas, child, x, y, FULL_ALPHA)
                }
            } finally {
                if (layered) canvas.restore()
            }
        }
    }

    /** 背景圆角矩形 + border 描边（颜色缺省不描边；半径钳制在半宽/半高内） */
    private fun drawBox(
        canvas: Canvas,
        node: YogaBuiltNode,
        x: Float,
        y: Float,
    ) {
        val props = node.spec.props
        val width = node.yoga.layoutWidth
        val height = node.yoga.layoutHeight
        val radius = props.borderRadius.coerceAtMost(width / 2f).coerceAtMost(height / 2f)
        if (props.backgroundColor != null) {
            val paint = Paint()
            paint.color = props.backgroundColor.argb.toInt()
            canvas.drawRRect(RRect.makeXYWH(x, y, width, height, radius), paint)
            paint.close()
        }
        if (props.borderWidth > 0f && props.borderColor != null) {
            val paint = Paint()
            paint.color = props.borderColor.argb.toInt()
            paint.mode = PaintMode.STROKE
            paint.strokeWidth = props.borderWidth
            val inset = props.borderWidth / 2f
            canvas.drawRRect(
                RRect.makeLTRB(x + inset, y + inset, x + width - inset, y + height - inset, radius),
                paint,
            )
            paint.close()
        }
    }

    /** 叶子内容分派：TextBlock / ImageSpec；Container 无自身内容。坐标已扣内边距 */
    private fun drawLeafContent(
        canvas: Canvas,
        node: YogaBuiltNode,
        x: Float,
        y: Float,
    ) {
        val props = node.spec.props
        val width = node.yoga.layoutWidth
        val height = node.yoga.layoutHeight
        val padLeft = paddingPx(props.paddingLeft)
        val padTop = paddingPx(props.paddingTop)
        val content =
            Box(
                x = x + padLeft,
                y = y + padTop,
                width = width - padLeft - paddingPx(props.paddingRight),
                height = height - padTop - paddingPx(props.paddingBottom),
            )
        when (val leaf = node.spec.leaf) {
            is LayoutLeaf.Text -> drawText(canvas, node, leaf.block, content)
            is LayoutLeaf.Image -> drawImage(canvas, leaf.image, content)
            is LayoutLeaf.Container -> Unit
        }
    }

    /** 文本叶子：复用测量缓存里的同一 Paragraph 实例绘制，并记录 TextRect + 会话块（M5） */
    private fun drawText(
        canvas: Canvas,
        node: YogaBuiltNode,
        block: TextBlock,
        box: Box,
    ) {
        if (box.width <= 0f) return
        val paragraph = measurer.paragraph(block, box.width)
        paragraph.paint(canvas, box.x, box.y)
        val rect =
            TextRect(
                left = box.x,
                top = box.y,
                width = paragraph.longestLine,
                height = node.yoga.layoutHeight,
                spans = block.spans,
                contentWidth = box.width,
                align = block.align,
            )
        textRectList += rect
        hitBlockList += RenderHitBlock(rect = rect, paragraph = paragraph)
    }

    /** 图片：加载 → 解码 → fit 居中于内容框；失败记诊断并跳过（渲染继续） */
    private fun drawImage(
        canvas: Canvas,
        imageSpec: ImageSpec,
        box: Box,
    ) {
        val loader = imageLoader
        val image =
            if (box.width <= 0f || box.height <= 0f || loader == null) {
                null
            } else {
                decodeImage(loader, imageSpec)
            }
        if (image != null) {
            drawFitted(canvas, image, box)
            image.close()
        }
    }

    /** 加载 + 解码；任一步失败记诊断返回 null */
    private fun decodeImage(
        loader: ImageLoader,
        imageSpec: ImageSpec,
    ): Image? =
        try {
            val bytes = loader.load(imageSpec.src)
            if (bytes == null) {
                recordImageFailure(imageSpec, "图片加载失败")
                null
            } else {
                Image.makeFromEncoded(bytes)
            }
        } catch (ignored: Throwable) {
            recordImageFailure(imageSpec, "图片解码失败")
            null
        }

    /** 等比缩放 fit 居中绘制 */
    private fun drawFitted(
        canvas: Canvas,
        image: Image,
        box: Box,
    ) {
        val scale = minOf(box.width / image.width, box.height / image.height)
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val paint = Paint()
        canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeXYWH(
                box.x + (box.width - drawWidth) / 2f,
                box.y + (box.height - drawHeight) / 2f,
                drawWidth,
                drawHeight,
            ),
            SamplingMode.LINEAR,
            paint,
            false,
        )
        paint.close()
    }

    /** 内容框（节点框扣除内边距后的绘制区，画布 dp 坐标） */
    private data class Box(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    private fun recordImageFailure(
        imageSpec: ImageSpec,
        message: String,
    ) {
        diagnosticList +=
            Diagnostic(
                DiagnosticSeverity.WARN,
                DiagnosticCodes.IMAGE_FAILED,
                message,
                imageSpec.src,
            )
    }

    /** 内边距折 px（PERCENT/undefined 场景 v0 视为 0，级联产物通常已是 PX） */
    private fun paddingPx(length: Length?): Float = if (length?.unit == CssUnit.PX) length.value else 0f

    private companion object {
        const val FULL_ALPHA = 255
    }
}
