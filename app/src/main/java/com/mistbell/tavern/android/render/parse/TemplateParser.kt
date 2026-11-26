package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.model.TemplateNode
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * 模板解析器（M2）：jsoup 容错解析 HTML → 元素/属性白名单过滤 → [TemplateDocument]。
 *
 * 模板解析（主题作者的 template.html）与卡段渲染（用户消息里的 HTML 卡段）共用本解析器。
 * 白名单外的构造按设计文档 §4.3 静默丢弃语义容错处理：良性元素 unwrap（子节点提升），
 * 危险元素整子树丢弃；丢弃动作全部产出 WARN 诊断（"静默"指无执行面，不是无审计）。
 * 容错解析永不上抛；解析深度有上限保护（超限子树丢弃，防深嵌套递归爆栈）。
 */
object TemplateParser {
    /** templateVersion 缺省值（manifest 解析失败/缺省时宿主按 1 传入，v0 冻结） */
    const val DEFAULT_TEMPLATE_VERSION = 1

    /** 元素白名单（设计文档 §4.1 表格） */
    val WHITELIST_ELEMENTS: Set<String> =
        setOf(
            "div", "span", "p", "br", "b", "strong", "i", "em", "u", "s",
            "a", "img", "hr", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6",
            "code", "pre", "slot",
        )

    /**
     * 危险元素（脚本/样式/嵌入/交互/表格/媒体）：整子树丢弃 + WARN。
     * TemplateValidator 以此集合判定卡段是否回落 WebView 渲染（设计文档 §7.1）。
     */
    val DANGEROUS_ELEMENTS: Set<String> =
        setOf(
            "script", "style", "iframe", "object", "embed", "link", "meta",
            "form", "input", "button", "svg", "table", "thead", "tbody", "tfoot",
            "tr", "td", "th", "video", "audio",
            // 上表之外的同类危险面（画布/替代文本域/框架集），与上表同待遇
            "canvas", "applet", "noscript", "select", "option", "textarea",
            "math", "frameset", "frame",
        )

    /** slot 合法 name 集合（v0：设计文档 §4.2 的 narrative/panels + 预留三槽位） */
    val SLOT_NAMES: Set<String> = setOf("narrative", "panels", "thinking", "sender", "timestamp")

    /** 所有元素通用的属性白名单（设计文档 §4.1：class/style/data-bind:*） */
    private val BASE_ATTRS: Set<String> =
        setOf("class", "style", "data-bind:text", "data-bind:visible")

    private const val SLOT_TAG = "slot"

    /** 递归深度上限：jsoup 自身解析可承受深嵌套，但本引擎递归转换需要护住调用栈 */
    private const val MAX_DEPTH = 100

    /** 解析模板 HTML 为白名单树。容错解析永不上抛；[templateVersion] 非法（<1）按 1 处理 */
    fun parse(
        html: String,
        templateVersion: Int = DEFAULT_TEMPLATE_VERSION,
    ): TemplateDocument {
        val diagnostics = mutableListOf<Diagnostic>()
        // parseBodyFragment 把输入当 body 片段：不引入 html/head 包裹，容错闭合标签
        val body = Jsoup.parseBodyFragment(html).body()
        val children = convertChildren(body.childNodes(), depth = 1, diagnostics)
        val version = if (templateVersion < 1) 1 else templateVersion
        return TemplateDocument(assembleRoot(children), version, diagnostics.toList())
    }

    /** 单根则直取，多根/含散文本则包一层透明 div（不丢内容） */
    private fun assembleRoot(children: List<TemplateNode>): TemplateNode.ElementNode =
        if (children.size == 1 && children.first() is TemplateNode.ElementNode) {
            children.first() as TemplateNode.ElementNode
        } else {
            TemplateNode.ElementNode("div", emptyMap(), children)
        }

    /** 转换一组 jsoup 子节点；unwrap 语义在此扁平化（子节点提升到父级） */
    private fun convertChildren(
        nodes: List<org.jsoup.nodes.Node>,
        depth: Int,
        diagnostics: MutableList<Diagnostic>,
    ): List<TemplateNode> = nodes.flatMap { convertNode(it, depth, diagnostics) }

    private fun convertNode(
        node: org.jsoup.nodes.Node,
        depth: Int,
        diagnostics: MutableList<Diagnostic>,
    ): List<TemplateNode> =
        when (node) {
            is Element -> convertElement(node, depth, diagnostics)
            is TextNode -> listOf(TemplateNode.TextNode(node.wholeText))
            // 注释/CDATA 等非内容节点：静默跳过（无渲染语义）
            else -> emptyList()
        }

    /** 元素转换：白名单保留 / 危险整树丢弃 / 其余 unwrap。返回 0..n 个节点（unwrap 可展开多个） */
    private fun convertElement(
        element: Element,
        depth: Int,
        diagnostics: MutableList<Diagnostic>,
    ): List<TemplateNode> {
        val tag = element.tagName()
        return when {
            tag == SLOT_TAG -> convertSlot(element, diagnostics)
            tag in DANGEROUS_ELEMENTS -> {
                diagnostics +=
                    Diagnostic(
                        DiagnosticSeverity.WARN,
                        DiagnosticCodes.DROP_ELEMENT,
                        "危险元素 <$tag> 已整子树丢弃${styleHint(element)}",
                        tag,
                    )
                emptyList()
            }
            else -> {
                val children = convertChildrenGuarded(element, tag, depth, diagnostics)
                if (tag in WHITELIST_ELEMENTS) {
                    listOf(TemplateNode.ElementNode(tag, filterAttributes(element, tag, diagnostics), children))
                } else {
                    // 白名单外的良性元素（article/section/figure 等）与未知标签：unwrap——
                    // 属性连同元素壳丢弃，子节点提升到父级
                    diagnostics +=
                        Diagnostic(
                            DiagnosticSeverity.WARN,
                            DiagnosticCodes.DROP_ELEMENT,
                            "白名单外元素 <$tag> 已 unwrap（子节点保留提升到父级）",
                            tag,
                        )
                    children
                }
            }
        }
    }

    /** 递归深度上限保护：超深子树整体丢弃（栈安全） */
    private fun convertChildrenGuarded(
        element: Element,
        tag: String,
        depth: Int,
        diagnostics: MutableList<Diagnostic>,
    ): List<TemplateNode> =
        if (depth >= MAX_DEPTH) {
            diagnostics +=
                Diagnostic(
                    DiagnosticSeverity.WARN,
                    DiagnosticCodes.DROP_ELEMENT,
                    "嵌套超过 $MAX_DEPTH 层，<$tag> 的子树已丢弃",
                    tag,
                )
            emptyList()
        } else {
            convertChildren(element.childNodes(), depth + 1, diagnostics)
        }

    /** slot 解析：name 合法 → SlotNode（槽位不承载回退内容）；缺失/非法 → 整子树丢弃 + 诊断 */
    private fun convertSlot(
        element: Element,
        diagnostics: MutableList<Diagnostic>,
    ): List<TemplateNode> {
        val name = element.attr("name").trim()
        if (name in SLOT_NAMES) {
            val hasFallback =
                element.childNodes().any { it is TextNode && it.wholeText.isNotBlank() } ||
                    element.children().isNotEmpty()
            if (hasFallback) {
                diagnostics +=
                    Diagnostic(
                        DiagnosticSeverity.INFO,
                        DiagnosticCodes.INVALID_SLOT,
                        "slot「$name」的回退内容已丢弃（槽位内容运行时填充）",
                        name,
                    )
            }
            return listOf(TemplateNode.SlotNode(name))
        }
        val shown = name.ifEmpty { "<缺失>" }
        diagnostics +=
            Diagnostic(
                DiagnosticSeverity.WARN,
                DiagnosticCodes.INVALID_SLOT,
                "slot name「$shown」不在合法集合 $SLOT_NAMES，整子树丢弃",
                shown,
            )
        return emptyList()
    }

    /** 属性过滤：白名单内保留，其余丢弃 + 诊断（key 已由 jsoup 统一小写） */
    private fun filterAttributes(
        element: Element,
        tag: String,
        diagnostics: MutableList<Diagnostic>,
    ): Map<String, String> {
        val allowed = allowedAttributes(tag)
        val result = mutableMapOf<String, String>()
        for (attribute in element.attributes()) {
            val key = attribute.key
            if (key in allowed) {
                result[key] = attribute.value
            } else {
                diagnostics +=
                    Diagnostic(
                        DiagnosticSeverity.WARN,
                        DiagnosticCodes.DROP_ATTRIBUTE,
                        "属性「$key」不在 <$tag> 白名单内，已丢弃",
                        key,
                    )
            }
        }
        return result.toMap()
    }

    /** 每元素属性白名单（设计文档 §4.1 表格：hr 仅排版属性；a 加 href；img 加 src/alt） */
    private fun allowedAttributes(tag: String): Set<String> =
        when (tag) {
            "hr" -> setOf("class", "style")
            "a" -> BASE_ATTRS + "href"
            "img" -> BASE_ATTRS + "src" + "alt"
            else -> BASE_ATTRS
        }

    /** style 元素丢弃时的作者提示：内容像 CSS（出现规则块花括号且带属性冒号）则指路 style.css */
    private fun styleHint(element: Element): String =
        if (element.tagName() == "style" && element.data().contains('{') && element.data().contains(':')) {
            "（style 元素不参与渲染，样式请放 style.css）"
        } else {
            ""
        }
}
