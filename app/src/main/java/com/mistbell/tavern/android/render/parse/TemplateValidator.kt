package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.RenderDecision
import com.mistbell.tavern.android.render.model.TemplateDocument
import com.mistbell.tavern.android.render.model.TemplateNode

/**
 * 渲染判定（设计文档 §7.1 卡段级兜底）：TemplateDocument → [RenderDecision]。
 *
 * NeedsFallback 触发条件：出现过危险元素丢弃记录（table/svg/form/video 等）、slot 语法非法、
 * 模板树为空。判定依据是 TemplateParser 产出的诊断（[DiagnosticCodes.DROP_ELEMENT] 且 target
 * 属于危险元素集合）——解析与判定共用同一解析器，主题模板（template.html）与消息卡段
 * （用户消息里的 HTML 卡）都可以走这条路；卡段回落 HtmlBubble（WebView 沙箱），整条消息
 * 失败回落 legacy 由宿主路由。
 */
object TemplateValidator {
    /** 自带内容的 void 元素（无子节点也视为有效内容） */
    private val SELF_CONTENT_TAGS = setOf("br", "hr", "img")

    fun decide(document: TemplateDocument): RenderDecision {
        val reasons = mutableListOf<String>()
        for (diagnostic in document.diagnostics) {
            if (isDangerousDrop(diagnostic) || diagnostic.code == DiagnosticCodes.INVALID_SLOT) {
                reasons += diagnostic.message
            }
        }
        if (!hasRenderableContent(document.tree)) {
            reasons += "模板树为空（无有效内容），无法渲染"
        }
        return if (reasons.isEmpty()) RenderDecision.Renderable else RenderDecision.NeedsFallback(reasons.toList())
    }

    /** 便利入口：卡段 HTML 串直接判定（解析 + 判定） */
    fun decideHtml(
        html: String,
        templateVersion: Int = TemplateParser.DEFAULT_TEMPLATE_VERSION,
    ): RenderDecision = decide(TemplateParser.parse(html, templateVersion))

    /** 危险元素丢弃记录判定：[DiagnosticCodes.DROP_ELEMENT] 且丢弃对象属于危险元素集合 */
    private fun isDangerousDrop(diagnostic: Diagnostic): Boolean =
        diagnostic.code == DiagnosticCodes.DROP_ELEMENT &&
            diagnostic.target != null &&
            diagnostic.target in TemplateParser.DANGEROUS_ELEMENTS

    /** 有效内容判定：非空白文本 / slot / 自带内容元素 / 任意后代有效即视为非空 */
    private fun hasRenderableContent(node: TemplateNode): Boolean =
        when (node) {
            is TemplateNode.TextNode -> node.text.isNotBlank()
            is TemplateNode.SlotNode -> true
            is TemplateNode.ElementNode ->
                node.tag in SELF_CONTENT_TAGS || node.children.any { hasRenderableContent(it) }
        }
}
