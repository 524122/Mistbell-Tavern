package com.mistbell.tavern.android.render.bind

import com.mistbell.tavern.android.render.model.BindingContext
import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.NodeBindings
import com.mistbell.tavern.android.render.model.StyledNode
import com.mistbell.tavern.android.render.model.TemplateNode

/** 绑定解析产物：文本已替换、[NodeBindings] 已挂载的新不可变树 + 诊断 */
data class BoundTemplate(val tree: StyledNode, val diagnostics: List<Diagnostic>)

/**
 * 绑定解析（设计文档 §6）：StyledNode 树 + [BindingContext] → 绑定已解析树。纯函数、无副作用、
 * 无日志框架依赖（诊断走返回值）。失败语义（§6.3）：路径不存在 → 插值空串、可见性按 false，
 * 记 WARN 诊断，永不崩。
 *
 * 步骤：
 * 1. TextNode 宏替换：{{key}} 精确匹配 [BindingContext.macros]，未命中保留原文；
 * 2. TextNode 状态插值：{{state.路径}} 去前缀后在 [BindingContext.variables] 精确匹配，
 *    命中替换，未命中替换为空串 + 诊断；
 * 3. data-bind:text / data-bind:visible 解析为 [NodeBindings]（可见性就地解析成布尔值，
 *    缺路径/变量缺失/假值 → false + 诊断；data-bind:text 保留路径供渲染期取值）。
 */
object BindingResolver {
    // 注意：闭花括号必须转义（\}\}）——Android 的 ICU 正则引擎（java.util.regex 后端）把裸 `}}`
    // 当语法错误，JVM 正则则容忍；M3 冒烟在设备上发现此 M2 遗留问题（JVM 单测无法暴露）
    private val STATE_PATTERN = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")
    private const val STATE_PREFIX = "state."
    private const val BIND_TEXT_ATTR = "data-bind:text"
    private const val BIND_VISIBLE_ATTR = "data-bind:visible"

    /** 假值语义：空串/空白、"false"（大小写不敏感）、"0" 均为假，其余为真 */
    private val FALSE_VALUES = setOf("", "0", "false")

    fun bind(
        tree: StyledNode,
        context: BindingContext,
    ): BoundTemplate {
        val diagnostics = mutableListOf<Diagnostic>()
        return BoundTemplate(bindNode(tree, context, diagnostics), diagnostics.toList())
    }

    private fun bindNode(
        node: StyledNode,
        context: BindingContext,
        diagnostics: MutableList<Diagnostic>,
    ): StyledNode =
        when (val source = node.node) {
            is TemplateNode.TextNode ->
                StyledNode(
                    TemplateNode.TextNode(interpolate(source.text, context, diagnostics)),
                    node.style,
                    node.inherited,
                )
            is TemplateNode.ElementNode -> {
                val bindings = resolveBindings(source.attrs, context, diagnostics)
                val children = node.children.map { bindNode(it, context, diagnostics) }
                StyledNode(source, node.style, node.inherited, bindings, children)
            }
            is TemplateNode.SlotNode -> node
        }

    /** 宏替换 + 状态插值：宏优先（精确键匹配），state. 前缀走变量表，其余保留原文 */
    private fun interpolate(
        text: String,
        context: BindingContext,
        diagnostics: MutableList<Diagnostic>,
    ): String {
        if (!text.contains("{{")) return text
        val out = StringBuilder()
        var last = 0
        for (match in STATE_PATTERN.findAll(text)) {
            out.append(text, last, match.range.first)
            val key = match.groupValues[1].trim()
            when {
                context.macros.containsKey(key) -> out.append(context.macros.getValue(key))
                key.startsWith(STATE_PREFIX) -> {
                    val path = key.removePrefix(STATE_PREFIX).trim()
                    val value = context.variables[path]
                    if (value == null) {
                        diagnostics +=
                            Diagnostic(
                                DiagnosticSeverity.WARN,
                                DiagnosticCodes.MISS_VARIABLE,
                                "状态插值「$key」未命中变量，替换为空串",
                                key,
                            )
                    } else {
                        out.append(value)
                    }
                }
                // 非匹配键（文本本身含 {{ 的场景）：保留原文
                else -> out.append(match.value)
            }
            last = match.range.last + 1
        }
        out.append(text.substring(last))
        return out.toString()
    }

    /** data-bind 两条绑定解析；两个属性都不存在 → null（无绑定） */
    private fun resolveBindings(
        attrs: Map<String, String>,
        context: BindingContext,
        diagnostics: MutableList<Diagnostic>,
    ): NodeBindings? {
        val textAttr = attrs[BIND_TEXT_ATTR]
        val visibleAttr = attrs[BIND_VISIBLE_ATTR]
        if (textAttr == null && visibleAttr == null) return null
        val textPath = variablePath(BIND_TEXT_ATTR, textAttr?.trim(), diagnostics)
        val visiblePath = variablePath(BIND_VISIBLE_ATTR, visibleAttr?.trim(), diagnostics)
        val visible =
            when {
                visiblePath != null -> {
                    val value = context.variables[visiblePath]
                    if (value == null) {
                        diagnostics +=
                            Diagnostic(
                                DiagnosticSeverity.WARN,
                                DiagnosticCodes.MISS_VARIABLE,
                                "data-bind:visible 路径「state.$visiblePath」未命中变量，按不可见处理",
                                BIND_VISIBLE_ATTR,
                            )
                        false
                    } else {
                        isTruthy(value)
                    }
                }
                // 属性存在但缺路径：INVALID_BIND 诊断已在路径提取时记录，按不可见兜底
                visibleAttr != null -> false
                // 属性不存在：默认可见
                else -> true
            }
        return NodeBindings(textPath, visiblePath, visible)
    }

    /**
     * 去掉可选的 state. 前缀得到变量表精确键。[trimmed] 为 null 表示属性不存在（无诊断）；
     * 属性存在但去掉前缀后为空 → INVALID_BIND 诊断。
     */
    private fun variablePath(
        attribute: String,
        trimmed: String?,
        diagnostics: MutableList<Diagnostic>,
    ): String? {
        val path = trimmed?.removePrefix(STATE_PREFIX)?.trim()
        if (!path.isNullOrEmpty()) return path
        if (trimmed != null) {
            diagnostics +=
                Diagnostic(
                    DiagnosticSeverity.WARN,
                    DiagnosticCodes.INVALID_BIND,
                    "绑定属性「$attribute」缺路径，绑定按失败语义处理",
                    attribute,
                )
        }
        return null
    }

    private fun isTruthy(value: String): Boolean = value.trim().lowercase() !in FALSE_VALUES
}
