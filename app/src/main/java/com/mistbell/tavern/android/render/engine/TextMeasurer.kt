package com.mistbell.tavern.android.render.engine

import com.mistbell.tavern.android.render.model.TextAlign
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.TextSpan
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.DecorationLineStyle
import org.jetbrains.skia.paragraph.DecorationStyle
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle

/**
 * SkParagraph 测量桥（M3）：FontCollection（EngineBootstrap 构建）+ ParagraphBuilder，
 * measure(maxWidth) → (w, h)，供 Yoga measure function 与 SkiaDrawVisitor 两用。
 *
 * 缓存策略（注意 Paragraph 不可重入 layout）：Paragraph 实例按 (块文本+样式指纹, 约束宽)
 * 缓存，创建时 layout 一次后**不再重排**——同 key 复用同一实例测量与绘制，保证测得高度与
 * 绘制布局一致；不同约束宽各自独立实例。缓存为线程安全 LRU（Yoga 测量回调所在线程不定）。
 *
 * 本类触碰 skiko JNI，不可 JVM 单测；逻辑保持薄。
 */
class TextMeasurer(
    private val fontCollection: FontCollection,
    paragraphCacheCapacity: Int = DEFAULT_PARAGRAPH_CACHE_CAPACITY,
) {
    /** 缓存条目：(样式指纹|约束宽) → 已 layout 的 Paragraph；accessOrder 淘汰最久未用 */
    private val paragraphCache =
        object : LinkedHashMap<String, Paragraph>(INITIAL_CACHE_SIZE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Paragraph>): Boolean {
                return size > paragraphCacheCapacity
            }
        }

    /** 测量：返回 (宽, 高)。宽为内容宽（约束内最长行；EXACTLY 场景由调用方取约束宽） */
    fun measure(
        block: TextBlock,
        maxWidth: Float?,
    ): Pair<Float, Float> {
        val width = maxWidth ?: MAX_LAYOUT_WIDTH
        val paragraph = paragraph(block, width)
        val intrinsic = paragraph.maxIntrinsicWidth
        val measuredWidth =
            if (maxWidth == null || intrinsic >= maxWidth) {
                paragraph.longestLine
            } else {
                intrinsic
            }
        return measuredWidth to paragraph.height
    }

    /**
     * 取已 layout 的 Paragraph（缓存命中复用同一实例）。返回后**不得再次 layout**——
     * 绘制方按当前节点内容宽取用，宽度不同即缓存未命中并各自建实例。
     * key = 样式指纹 + **文本 hash** + 约束宽：同样式不同文本的块（如同款列表行）必须各建实例，
     * 否则绘制会复用他块字形（M3 冒烟实测：列表四行全部画成第一行文字）。
     */
    fun paragraph(
        block: TextBlock,
        maxWidth: Float,
    ): Paragraph {
        val key = "${block.styleFingerprint}|${block.text.hashCode()}|${(maxWidth * KEY_SCALE).toInt()}"
        synchronized(paragraphCache) {
            paragraphCache[key]?.let { return it }
        }
        val built = buildParagraph(block, maxWidth)
        synchronized(paragraphCache) {
            paragraphCache[key] = built
        }
        return built
    }

    /** 构建 + 单次 layout（本类唯一的 layout 调用点） */
    private fun buildParagraph(
        block: TextBlock,
        maxWidth: Float,
    ): Paragraph {
        val paragraphStyle = ParagraphStyle()
        paragraphStyle.textStyle = baseTextStyle(block)
        paragraphStyle.alignment =
            when (block.align) {
                TextAlign.CENTER -> Alignment.CENTER
                TextAlign.RIGHT -> Alignment.RIGHT
                TextAlign.LEFT -> Alignment.LEFT
            }
        val builder = ParagraphBuilder(paragraphStyle, fontCollection)
        for (span in block.spans) {
            builder.pushStyle(spanStyle(block, span))
            builder.addText(span.text)
            builder.popStyle()
        }
        val paragraph = builder.build()
        paragraph.layout(maxWidth)
        return paragraph
    }

    /**
     * 块基线样式：字号/前景色；字族默认走 FontCollection 默认字体管理器。
     * 行高说明：skiko-android 0.9.47 的 TextStyle.height 在设备上表现异常——任何非空值
     * （无论按绝对像素还是字号倍数传入）都产出畸大行框（实测 16px 字号 + height=24 或 1.5
     * 均得 ~387px 行框，而 height 缺省时行框 23px ≈ 1.44 倍，与 1.5 目标接近）。
     * v0 因此**不设 height**，走字体默认行距；[TextBlock.lineHeightPx] 字段保留（模型完整，
     * 待 skiko 修复/换路径后启用），代码块 12px 字号的默认行距同样适用。
     */
    private fun baseTextStyle(block: TextBlock): TextStyle {
        val style = TextStyle()
        style.color = (block.colorArgb ?: DEFAULT_COLOR).toInt()
        style.fontSize = block.fontSizePx ?: DEFAULT_FONT_SIZE
        return style
    }

    /** span 样式：在块基线上叠加装饰字段（bold/italic/下划线/删除线/颜色/等宽/字号） */
    private fun spanStyle(
        block: TextBlock,
        span: TextSpan,
    ): TextStyle {
        val style = baseTextStyle(block)
        style.color = (span.colorArgb ?: block.colorArgb ?: DEFAULT_COLOR).toInt()
        style.fontSize = span.fontSizePx ?: block.fontSizePx ?: DEFAULT_FONT_SIZE
        val weight = if (span.bold) BOLD_WEIGHT else NORMAL_WEIGHT
        val slant = if (span.italic) FontSlant.ITALIC else FontSlant.UPRIGHT
        style.fontStyle = FontStyle(weight, NORMAL_WIDTH, slant)
        if (span.underline || span.strikethrough) {
            // DecorationStyle 为 Java 构造（不可具名参数）：underline/overline/lineThrough/gaps/color/线型/粗细
            style.decorationStyle =
                DecorationStyle(
                    span.underline,
                    false,
                    span.strikethrough,
                    true,
                    NO_DECORATION_COLOR,
                    DecorationLineStyle.SOLID,
                    THICKNESS_MULTIPLIER,
                )
        }
        if (span.mono) {
            // skiko 的字族 setter 与数组 getter 名称不对称，属性合成不可用，须直呼方法
            style.setFontFamily(MONO_FAMILY)
        }
        return style
    }

    private companion object {
        /** UNDEFINED 约束下的布局宽上限（避免无穷值进入 Skia；实际行宽由 maxIntrinsic 决定） */
        const val MAX_LAYOUT_WIDTH = 100_000f

        /** 缓存 key 的宽度量化精度（0.5px 步进，避免浮点抖动击穿缓存） */
        const val KEY_SCALE = 2f

        const val DEFAULT_COLOR = 0xFF000000L
        const val DEFAULT_FONT_SIZE = 16f
        const val BOLD_WEIGHT = 700
        const val NORMAL_WEIGHT = 400

        /** FontStyle 的 width 维度常规值（FontWidth NORMAL） */
        const val NORMAL_WIDTH = 5

        const val MONO_FAMILY = "monospace"

        /** DecorationStyle 的颜色位：0 = 继承文字色 */
        const val NO_DECORATION_COLOR = 0
        const val THICKNESS_MULTIPLIER = 1f
        const val DEFAULT_PARAGRAPH_CACHE_CAPACITY = 128
        const val INITIAL_CACHE_SIZE = 32
        const val LOAD_FACTOR = 0.75f
    }
}
