package com.mistbell.tavern.android.render.model

/*
 * 主题渲染引擎的内容模型（M3）。
 *
 * 核心建模决策：**文本块是一等内容**——布局树的叶子既可以是 TemplateNode 子树（HTML 卡段，
 * 经 InlineCollector 收集行内元素），也可以是 TextBlock（Markdown 叙述段/行内合并产物）。
 * 全部不可变、纯 Kotlin、无 Android/Compose 依赖。
 */

/**
 * 富文本片段：一段样式一致的文本。[text] 为原样内容（含换行符）；样式字段全部可空 = 继承
 * 所在 [TextBlock] 的块级默认。相邻同样式片段由 InlineCollector 合并。
 */
data class TextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    /** 前景色（0xAARRGGBB）；null = 继承块级 color */
    val colorArgb: Long? = null,
    /** 等宽字体（行内 code / Markdown 行内代码） */
    val mono: Boolean = false,
    /** 字号（px，级联折算后的有效值）；null = 继承块级 fontSize */
    val fontSizePx: Float? = null,
    /** 链接目标（a[href] / Markdown 链接）；null = 非链接。M5 命中测试据此跳转 */
    val href: String? = null,
) {
    /** 样式指纹（不含 text）：InlineCollector 合并判等与测量缓存 key 的组成部分 */
    val styleFingerprint: String
        get() =
            listOf(bold, italic, underline, strikethrough, colorArgb, mono, fontSizePx, href)
                .joinToString(separator = "|")
}

/**
 * 文本块：一等内容叶子。[spans] 顺序拼接为整段文本；块级字段（align/字号/行高/前景色）
 * 是未指定样式字段的 span 的继承默认。
 */
data class TextBlock(
    val spans: List<TextSpan>,
    val align: TextAlign = TextAlign.LEFT,
    val fontSizePx: Float? = null,
    val lineHeightPx: Float? = null,
    val colorArgb: Long? = null,
    /** 代码块浅背景（Markdown 围栏代码块）；null = 无背景。由 SlotFiller 折算进包裹容器样式 */
    val codeBackgroundArgb: Long? = null,
) {
    /** 整块文本（span 顺序拼接；测量缓存 key 与 M5 命中区间换算均以此为基准） */
    val text: String by lazy { spans.joinToString(separator = "") { it.text } }

    /** 全块样式指纹（测量缓存 key 的组成部分） */
    val styleFingerprint: String by lazy {
        listOf(
            align.name,
            fontSizePx,
            lineHeightPx,
            colorArgb,
            codeBackgroundArgb,
        ).joinToString(separator = "|") + "#" +
            spans.joinToString(separator = ";") { it.styleFingerprint }
    }
}

/** 渲染时图片源：img[src]（http(s)/本地路径由 ImageLoader 实现方自行解释） */
data class ImageSpec(
    val src: String,
    val alt: String? = null,
)

/** SlotFiller 的段输入（有序）：Markdown 串 / HTML 串 / 现成文本块 */
sealed class ContentSegment {
    /** Markdown 叙述段：经 MarkdownToNodes 转换为若干 TextBlock */
    data class Markdown(val source: String) : ContentSegment()

    /** HTML 卡段：经 TemplateParser + StyleResolver 走与模板同一管线 */
    data class Html(val html: String) : ContentSegment()

    /** 已转换好的文本块（跳过 Markdown 解析） */
    data class Text(val block: TextBlock) : ContentSegment()
}

/**
 * 实例化树节点（SlotFiller 产物，InlineCollector/LayoutSpecBuilder 消费）：
 * - [Element]：模板/HTML 结构节点，样式级联与绑定解析已完成；
 * - [InlineText]：尚未合并的裸文本（TextNode），InlineCollector 的行内收集对象；
 * - [TextLeaf]：一等内容块（Markdown 段/行内合并产物）。
 */
sealed class RenderNode {
    /** 结构节点；[styled] 的 node 恒为 ElementNode（TextNode/SlotNode 不产出本变体） */
    data class Element(
        val styled: StyledNode,
        val children: List<RenderNode>,
    ) : RenderNode()

    /** 裸文本节点（来自 TextNode），携带级联继承链供行内样式合并 */
    data class InlineText(
        val styled: StyledNode,
        val text: String,
    ) : RenderNode()

    /** 一等内容块 */
    data class TextLeaf(val block: TextBlock) : RenderNode()
}

/** 图片加载器：按 src 返回编码字节（PNG/JPEG 等浏览器可解码格式）；失败返回 null */
fun interface ImageLoader {
    fun load(src: String): ByteArray?
}

/**
 * M5 命中测试的文本矩形：一个 TextBlock 在画布（dp 坐标）上的绘制区域 + 全部 span。
 * span 在块内的字符区间由 TextSpan 顺序长度累加换算；[TextSpan.href] 非空即为可点击区间。
 * [width] 为最长行宽（longestLine）；[contentWidth] 为内容框宽（行按 [align] 落在内容框内，
 * 命中测试据此折算行的水平起点）；[height] 为节点框高（含块内多行）。
 */
data class TextRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val spans: List<TextSpan>,
    /** 内容框宽（dp）；行水平起点 = left + (contentWidth - width) × 对齐折算 */
    val contentWidth: Float = 0f,
    /** 行对齐方式（命中区域折算用） */
    val align: TextAlign = TextAlign.LEFT,
)
