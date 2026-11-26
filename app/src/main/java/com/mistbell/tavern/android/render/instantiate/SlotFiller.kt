package com.mistbell.tavern.android.render.instantiate

import com.mistbell.tavern.android.render.bind.BindingResolver
import com.mistbell.tavern.android.render.markdown.MarkdownToNodes
import com.mistbell.tavern.android.render.model.BindingContext
import com.mistbell.tavern.android.render.model.ColorValue
import com.mistbell.tavern.android.render.model.ContentSegment
import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.InheritedStyle
import com.mistbell.tavern.android.render.model.Length
import com.mistbell.tavern.android.render.model.NodeBindings
import com.mistbell.tavern.android.render.model.RenderNode
import com.mistbell.tavern.android.render.model.ResolvedStyle
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.model.TextBlock
import com.mistbell.tavern.android.render.model.ThemeVars
import com.mistbell.tavern.android.render.parse.ParsedStylesheet
import com.mistbell.tavern.android.render.parse.StyleResolver
import com.mistbell.tavern.android.render.parse.TemplateParser

/** slot 填充产物：实例化树（[RenderNode]）+ 全程诊断（级联/绑定/填充） */
data class FilledTemplate(
    val root: RenderNode.Element,
    val diagnostics: List<Diagnostic>,
)

/**
 * slot 填充（M3，设计文档 §4.2）：模板 TemplateDocument + 消息分段 → 实例化树。
 *
 * 流程：
 * 1. 模板树走 StyleResolver 级联（默认样式表 + 主题 style.css + var token）；
 * 2. 各段内容走同一管线：HTML 串 → TemplateParser + StyleResolver；Markdown 串 → MarkdownToNodes；
 *    现成 TextBlock 直接使用；
 * 3. BindingResolver 对模板树与段树做绑定解析（宏/状态插值/data-bind）；
 * 4. SlotNode 就地替换为段内容；data-bind:text 元素以变量值整体替换其子文本（渲染期取值）。
 *
 * 容错（设计文档 §4.2/§6.3）：模板未声明某槽位 → 该槽内容按默认顺序（narrative → panels）
 * 追加在模板尾部，不丢内容。data-bind:text 路径未命中 → 插值空串 + WARN。
 * 纯 Kotlin、永不上抛（解析/级联容错语义沿用 M2），可 JVM 单测。
 */
object SlotFiller {
    /** 叙述槽位名（设计文档 §4.2） */
    const val SLOT_NARRATIVE = "narrative"

    /** 面板槽位名（设计文档 §4.2） */
    const val SLOT_PANELS = "panels"

    /** slot 默认顺序（缺槽位追加尾部时的次序） */
    private val DEFAULT_SLOT_ORDER = listOf(SLOT_NARRATIVE, SLOT_PANELS)

    /** 填充请求：模板 + 有序分段集 + 主题样式上下文 + 绑定上下文 + Markdown 调色板 */
    data class FillRequest(
        val template: TemplateDocument,
        val narrative: List<ContentSegment> = emptyList(),
        val panels: List<String> = emptyList(),
        val themeStylesheet: ParsedStylesheet? = null,
        val themeVars: ThemeVars = ThemeVars.EMPTY,
        val context: BindingContext = BindingContext(),
        val markdownPalette: MarkdownToNodes.Palette = MarkdownToNodes.Palette(),
    )

    fun fill(request: FillRequest): FilledTemplate {
        val diagnostics = mutableListOf<Diagnostic>()
        val resolved = StyleResolver.resolve(request.template, request.themeStylesheet, request.themeVars)
        diagnostics += resolved.diagnostics
        val bound = BindingResolver.bind(resolved.tree, request.context)
        diagnostics += bound.diagnostics

        val content =
            mapOf(
                SLOT_NARRATIVE to request.narrative,
                SLOT_PANELS to request.panels.map { ContentSegment.Html(it) },
            )
        val seenSlots = mutableSetOf<String>()
        val children = instantiateChildren(bound.tree, content, request, seenSlots, diagnostics)
        appendUndeclaredSlots(content, seenSlots, request, diagnostics, children)
        return FilledTemplate(RenderNode.Element(bound.tree, children), diagnostics.toList())
    }

    /** 递归实例化：SlotNode 替换为段内容，TextNode 转 InlineText，元素递归 */
    private fun instantiateChildren(
        styled: StyledNode,
        content: Map<String, List<ContentSegment>>,
        request: FillRequest,
        seenSlots: MutableSet<String>,
        diagnostics: MutableList<Diagnostic>,
    ): MutableList<RenderNode> {
        val out = mutableListOf<RenderNode>()
        for (child in styled.children) {
            when (val node = child.node) {
                is TemplateNode.SlotNode -> {
                    seenSlots += node.name
                    out += segmentNodes(content[node.name].orEmpty(), request, diagnostics)
                }
                is TemplateNode.TextNode -> out += RenderNode.InlineText(child, node.text)
                is TemplateNode.ElementNode ->
                    out += elementNode(child, content, request, seenSlots, diagnostics)
            }
        }
        return out
    }

    /** 元素节点：data-bind:text 以变量值整体替换子文本；否则递归子级 */
    private fun elementNode(
        styled: StyledNode,
        content: Map<String, List<ContentSegment>>,
        request: FillRequest,
        seenSlots: MutableSet<String>,
        diagnostics: MutableList<Diagnostic>,
    ): RenderNode.Element {
        val bindings: NodeBindings? = styled.bindings
        val children =
            if (bindings?.textPath != null) {
                listOf(boundTextNode(bindings.textPath, request, diagnostics))
            } else {
                instantiateChildren(styled, content, request, seenSlots, diagnostics)
            }
        return RenderNode.Element(styled, children)
    }

    /** data-bind:text 取值：命中 → 变量值文本；未命中 → 空串 + WARN（设计文档 §6.3） */
    private fun boundTextNode(
        path: String,
        request: FillRequest,
        diagnostics: MutableList<Diagnostic>,
    ): RenderNode.InlineText {
        val value = request.context.variables[path]
        if (value == null) {
            diagnostics +=
                Diagnostic(
                    DiagnosticSeverity.WARN,
                    DiagnosticCodes.MISS_VARIABLE,
                    "data-bind:text 路径「state.$path」未命中变量，按空串渲染",
                    path,
                )
        }
        val synthetic =
            StyledNode(
                TemplateNode.TextNode(value.orEmpty()),
                ResolvedStyle(),
                InheritedStyle.ROOT,
            )
        return RenderNode.InlineText(synthetic, value.orEmpty())
    }

    /** 一组段 → 渲染节点列表（Markdown 展开、HTML 走解析管线、TextBlock 包裹成块） */
    private fun segmentNodes(
        segments: List<ContentSegment>,
        request: FillRequest,
        diagnostics: MutableList<Diagnostic>,
    ): List<RenderNode> =
        segments.flatMap { segment ->
            when (segment) {
                is ContentSegment.Markdown -> segmentBlocks(segment.source, request)
                is ContentSegment.Html -> htmlSegment(segment.html, request, diagnostics)
                is ContentSegment.Text -> listOf(wrapBlock(segment.block))
            }
        }

    /** Markdown 串 → 每块一个包裹节点 */
    private fun segmentBlocks(
        markdown: String,
        request: FillRequest,
    ): List<RenderNode> = MarkdownToNodes.convert(markdown, request.markdownPalette).map(::wrapBlock)

    /** HTML 串：TemplateParser + StyleResolver + BindingResolver 同管线 → 实例化子树（保留段根元素样式） */
    private fun htmlSegment(
        html: String,
        request: FillRequest,
        diagnostics: MutableList<Diagnostic>,
    ): List<RenderNode> {
        val document = TemplateParser.parse(html)
        diagnostics += document.diagnostics
        val resolved = StyleResolver.resolve(document, request.themeStylesheet, request.themeVars)
        diagnostics += resolved.diagnostics
        val bound = BindingResolver.bind(resolved.tree, request.context)
        diagnostics += bound.diagnostics
        // 段根元素自身（承载 class 样式如卡片容器）必须保留，故走 elementNode 而非只取 children
        return listOf(elementNode(bound.tree, emptyMap(), request, mutableSetOf(), diagnostics))
    }

    /**
     * 模板未声明的槽位内容追加到模板尾部（默认顺序 narrative → panels，设计文档 §4.2 容错）。
     */
    private fun appendUndeclaredSlots(
        content: Map<String, List<ContentSegment>>,
        seenSlots: Set<String>,
        request: FillRequest,
        diagnostics: MutableList<Diagnostic>,
        out: MutableList<RenderNode>,
    ) {
        for (name in DEFAULT_SLOT_ORDER) {
            val segments = content[name].orEmpty()
            if (name in seenSlots || segments.isEmpty()) continue
            diagnostics +=
                Diagnostic(
                    DiagnosticSeverity.INFO,
                    DiagnosticCodes.INVALID_SLOT,
                    "模板未声明 slot「$name」，内容按默认顺序追加在模板尾部",
                    name,
                )
            out += segmentNodes(segments, request, diagnostics)
        }
    }

    /**
     * 一等内容块包裹为块级容器：补块间距（对齐原 MarkdownRenderer 的 spacedBy(4dp)）；
     * 代码块（带浅背景）另加容器背景/内边距/圆角，使布局尺寸含内边距、背景不与相邻块贴合。
     */
    private fun wrapBlock(block: TextBlock): RenderNode.Element {
        val isCode = block.codeBackgroundArgb != null
        val style =
            if (isCode) {
                ResolvedStyle(
                    marginBottom = Length.px(BLOCK_GAP_PX),
                    backgroundColor = ColorValue(requireNotNull(block.codeBackgroundArgb)),
                    paddingTop = Length.px(CODE_PADDING_PX),
                    paddingRight = Length.px(CODE_PADDING_PX),
                    paddingBottom = Length.px(CODE_PADDING_PX),
                    paddingLeft = Length.px(CODE_PADDING_PX),
                    borderRadius = Length.px(CODE_RADIUS_PX),
                )
            } else {
                ResolvedStyle(marginBottom = Length.px(BLOCK_GAP_PX))
            }
        val wrapper =
            StyledNode(
                TemplateNode.ElementNode("div", emptyMap(), emptyList()),
                style,
                InheritedStyle.ROOT,
            )
        return RenderNode.Element(wrapper, listOf(RenderNode.TextLeaf(block)))
    }

    /** 段间距（px，与 ui/chat MarkdownRenderer 的 spacedBy(4dp) 对齐） */
    private const val BLOCK_GAP_PX = 4f

    /** 代码块容器内边距（px，对齐 MarkdownRenderer 的 padding(12dp)） */
    private const val CODE_PADDING_PX = 12f

    /** 代码块容器圆角（px，对齐 MarkdownRenderer 的 RoundedCornerShape(8dp)） */
    private const val CODE_RADIUS_PX = 8f
}
