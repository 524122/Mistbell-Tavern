package com.mistbell.tavern.android.render.parse

import com.mistbell.tavern.android.render.model.Diagnostic
import com.mistbell.tavern.android.render.model.DiagnosticCodes
import com.mistbell.tavern.android.render.model.DiagnosticSeverity.WARN
import com.mistbell.tavern.android.render.model.ThemeVars

/** 特异性三元组（id 数, class 数, 元素数），按字典序比较（设计文档 §5.1） */
data class SelectorSpecificity(
    val ids: Int,
    val classes: Int,
    val elements: Int,
) : Comparable<SelectorSpecificity> {
    override fun compareTo(other: SelectorSpecificity): Int {
        val byId = ids.compareTo(other.ids)
        val byClass = classes.compareTo(other.classes)
        return when {
            byId != 0 -> byId
            byClass != 0 -> byClass
            else -> elements.compareTo(other.elements)
        }
    }
}

/** 复合简单选择器：元素名/.class/#id 的组合（如 h1.title#main），不支持伪类/属性选择器/组合器 */
data class SelectorCompound(
    val tag: String?,
    val classes: List<String>,
    val ids: List<String>,
) {
    val specificity: SelectorSpecificity
        get() = SelectorSpecificity(ids.size, classes.size, if (tag != null) 1 else 0)
}

/** 完整选择器：复合选择器的后代（空格）组合，特异性为各段之和 */
data class CssSelector(val compounds: List<SelectorCompound>) {
    val specificity: SelectorSpecificity
        get() =
            compounds.fold(SelectorSpecificity(0, 0, 0)) { acc, compound ->
                val s = compound.specificity
                SelectorSpecificity(acc.ids + s.ids, acc.classes + s.classes, acc.elements + s.elements)
            }
}

/** 单条声明：属性名（小写）+ 值（var(--mt-*) 已替换后的原始串；类型校验在 StyleResolver 级联时进行） */
data class CssDeclaration(val property: String, val value: String)

/** 一条样式规则；[sourceOrder] 为样式表内单调递增序号，同特异性时后者胜 */
data class CssRule(
    val selectors: List<CssSelector>,
    val declarations: List<CssDeclaration>,
    val sourceOrder: Int,
)

/** 样式表解析产物：畸形构造不抛异常，以 [diagnostics] 审计 */
data class ParsedStylesheet(val rules: List<CssRule>, val diagnostics: List<Diagnostic>)

/** inline style 属性解析产物（声明进级联时排在其余一切样式表规则之后） */
data class InlineStyleDeclarations(val declarations: List<CssDeclaration>, val diagnostics: List<Diagnostic>)

/** 规则块扫描结果：[rule] 为可保留规则（选择器/声明全空则 null），[nextIndex] 为继续扫描位置 */
private data class RuleScan(
    val rule: CssRule?,
    val nextIndex: Int,
)

private val COMMENT_BLOCK = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private const val COMMENT_OPEN = "/*"

/** var(--name[, fallback]) 模式；name 为 CSS 变量名（-- 开头） */
private val VAR_PATTERN = Regex("""var\(\s*(--[A-Za-z0-9_-]+)\s*(?:,\s*([^()]*))?\)""")

/** var 嵌套深度上限（变量值里再引用变量的防循环保护） */
private const val MAX_VAR_DEPTH = 5

/** 诊断 message 里引用原文的截断长度 */
private const val SNIPPET_LIMIT = 40

/** 剥离 /* ... */ 注释；未闭合注释按截断处理并告警 */
private fun stripComments(
    css: String,
    diagnostics: MutableList<Diagnostic>,
): String {
    val without = COMMENT_BLOCK.replace(css, " ")
    val open = without.indexOf(COMMENT_OPEN)
    if (open >= 0) {
        diagnostics += parseFailed("注释未闭合，其后内容已截断丢弃")
        return without.substring(0, open)
    }
    return without
}

/** 诊断码 PARSE_CSS_FAILED 的 WARN 快捷构造 */
private fun parseFailed(message: String): Diagnostic = Diagnostic(WARN, DiagnosticCodes.PARSE_CSS_FAILED, message)

/** 复合选择器整体语法：可选元素名 + 任意 .class/#id 序列；伪类冒号/组合符/属性括号自然拒绝 */
private val COMPOUND_PATTERN = Regex("""^([A-Za-z][A-Za-z0-9-]*)?((?:[.#][A-Za-z0-9_-]+)*)$""")
private val SEGMENT_PATTERN = Regex("""[.#][A-Za-z0-9_-]+""")

/** 复合选择器解析：正则整体匹配 + 分段归类，返回 null 即非法 */
private fun parseCompound(text: String): SelectorCompound? {
    val match = COMPOUND_PATTERN.matchEntire(text) ?: return null
    val tag = match.groupValues[1].lowercase().ifEmpty { null }
    val segments = SEGMENT_PATTERN.findAll(match.groupValues[2]).map { it.value }.toList()
    val classes = segments.filter { it.startsWith(".") }.map { it.substring(1) }
    val ids = segments.filter { it.startsWith("#") }.map { it.substring(1) }
    return if (tag != null || classes.isNotEmpty() || ids.isNotEmpty()) {
        SelectorCompound(tag, classes, ids)
    } else {
        null
    }
}

/**
 * 一趟替换 [current] 里全部 var 引用：已定义变量取注入值，未定义且声明了回退值时取回退值，
 * 两者皆无 → 记 UNDEFINED_VAR 诊断并返回 null（整个声明丢弃）。
 */
private fun replaceVarMatches(
    matches: List<MatchResult>,
    current: String,
    themeVars: ThemeVars,
    property: String,
    diagnostics: MutableList<Diagnostic>,
): String? {
    val out = StringBuilder()
    var last = 0
    for (match in matches) {
        out.append(current, last, match.range.first)
        val name = match.groupValues[1]
        val fallback = match.groupValues[2].trim()
        val defined = themeVars[name]
        when {
            defined != null -> out.append(defined.trim())
            fallback.isNotEmpty() -> out.append(fallback)
            else -> {
                diagnostics +=
                    Diagnostic(
                        WARN,
                        DiagnosticCodes.UNDEFINED_VAR,
                        "属性「$property」引用未定义变量 $name 且无回退值，该声明丢弃",
                        name,
                    )
                return null
            }
        }
        last = match.range.last + 1
    }
    out.append(current.substring(last))
    return out.toString()
}

/**
 * CSS 子集解析器（手写 tokenizer+parser，设计文档 §5）：`selector[, selector]* { prop: value; }`。
 *
 * 选择器仅支持复合简单选择器（元素/.class/#id）+ 后代空格组合；伪类/@ 规则/属性选择器/组合器
 * 一律 WARN 丢弃整条规则，永不崩。var(--mt-*) 在解析值时替换为 [ThemeVars] 注入值，支持
 * `var(--x, fallback)` 回退；未定义且无回退 → 该声明丢弃 + 诊断。值类型校验（Length/颜色/枚举等）
 * 在 StyleResolver 级联时进行，本层只管结构与变量替换。
 */
object CssParser {
    /** 解析样式表。畸形输入产出诊断而非异常 */
    fun parse(
        css: String,
        themeVars: ThemeVars = ThemeVars.EMPTY,
    ): ParsedStylesheet {
        val diagnostics = mutableListOf<Diagnostic>()
        val source = stripComments(css, diagnostics)
        val rules = mutableListOf<CssRule>()
        var index = 0
        var order = 0
        while (index < source.length) {
            when (source[index]) {
                // 规则间杂散分号/闭括号：容错跳过
                in " \t\r\n" -> index++
                ';' -> index++
                '}' -> {
                    diagnostics += parseFailed("多余的 '}'（无匹配规则块），已跳过")
                    index++
                }
                '@' -> index = skipAtRule(source, index, diagnostics)
                else -> {
                    val scan = scanRule(source, index, themeVars, order, diagnostics)
                    scan.rule?.let { rules += it }
                    order++
                    index = scan.nextIndex
                }
            }
        }
        return ParsedStylesheet(rules.toList(), diagnostics.toList())
    }

    /** 解析 inline style 属性值（声明序列，语义同规则块内部） */
    fun parseInlineStyle(
        style: String,
        themeVars: ThemeVars = ThemeVars.EMPTY,
    ): InlineStyleDeclarations {
        val diagnostics = mutableListOf<Diagnostic>()
        val declarations = parseDeclarations(stripComments(style, diagnostics), themeVars, diagnostics)
        return InlineStyleDeclarations(declarations, diagnostics.toList())
    }

    /** 扫描一条规则：选择器块 + 声明块；EOF 截断按已读取部分容错 */
    private fun scanRule(
        source: String,
        start: Int,
        themeVars: ThemeVars,
        order: Int,
        diagnostics: MutableList<Diagnostic>,
    ): RuleScan {
        val brace = source.indexOf('{', start)
        if (brace < 0) {
            val tail = source.substring(start).trim().take(SNIPPET_LIMIT)
            diagnostics += parseFailed("选择器后缺少 '{'，尾部内容截断丢弃：\"$tail\"")
            return RuleScan(null, source.length)
        }
        val close = source.indexOf('}', brace)
        val truncated = close < 0
        val blockEnd = if (truncated) source.length else close
        if (truncated) {
            diagnostics += parseFailed("规则块未闭合（缺 '}'），按已读取部分容错处理")
        }
        val declarations = parseDeclarations(source.substring(brace + 1, blockEnd), themeVars, diagnostics)
        val selectors = parseSelectorList(source.substring(start, brace), diagnostics)
        val rule =
            if (selectors.isEmpty() || declarations.isEmpty()) {
                null
            } else {
                CssRule(selectors, declarations, order)
            }
        return RuleScan(rule, (blockEnd + 1).coerceAtMost(source.length))
    }

    /** 逗号分组解析；任一选择器非法 → 丢弃整条规则（设计文档 §5.1 的保守语义） */
    private fun parseSelectorList(
        text: String,
        diagnostics: MutableList<Diagnostic>,
    ): List<CssSelector> {
        var anyInvalid = false
        val parsed = mutableListOf<CssSelector>()
        for (part in text.split(',')) {
            val selector = parseSelector(part.trim())
            if (selector == null) {
                anyInvalid = true
                val snippet = part.trim().ifEmpty { "<空>" }.take(SNIPPET_LIMIT)
                diagnostics +=
                    parseFailed(
                        "不支持的选择器「$snippet」（仅支持 元素/.class/#id 与后代空格组合），整条规则丢弃",
                    )
            } else {
                parsed += selector
            }
        }
        return if (anyInvalid) emptyList() else parsed
    }

    /** 单个完整选择器：按空白拆后代段，逐段解析复合选择器；任一段非法 → 整个选择器非法 */
    private fun parseSelector(text: String): CssSelector? =
        text.takeIf { it.isNotEmpty() }
            ?.split(WHITESPACE_RUN)
            ?.filter { it.isNotEmpty() }
            ?.map { parseCompound(it) }
            ?.takeIf { compounds -> compounds.none { it == null } }
            ?.map { requireNotNull(it) }
            ?.let(::CssSelector)

    /** 声明块：按 ';' 分段，段内首个 ':' 分属性与值；缺冒号/缺值丢弃 + 诊断 */
    private fun parseDeclarations(
        body: String,
        themeVars: ThemeVars,
        diagnostics: MutableList<Diagnostic>,
    ): List<CssDeclaration> {
        val result = mutableListOf<CssDeclaration>()
        for (segment in body.split(';')) {
            parseDeclaration(segment.trim(), themeVars, diagnostics)?.let { result += it }
        }
        return result.toList()
    }

    /** 单段声明解析；空段静默返回 null，缺冒号/缺值/未定义 var 丢弃 + 诊断 */
    private fun parseDeclaration(
        segment: String,
        themeVars: ThemeVars,
        diagnostics: MutableList<Diagnostic>,
    ): CssDeclaration? {
        val colon = segment.indexOf(':')
        if (segment.isEmpty() || colon <= 0) {
            if (segment.isNotEmpty()) {
                diagnostics += parseFailed("声明缺冒号，已丢弃：\"${segment.take(SNIPPET_LIMIT)}\"")
            }
            return null
        }
        val property = segment.substring(0, colon).trim().lowercase()
        val rawValue = segment.substring(colon + 1).trim()
        return when {
            rawValue.isEmpty() -> {
                diagnostics += parseFailed("属性「$property」缺值，已丢弃")
                null
            }
            else -> resolveVarRefs(rawValue, themeVars, property, diagnostics)?.let { CssDeclaration(property, it) }
        }
    }

    /**
     * 替换值里的 var(--mt-*) 引用（支持变量值再引用变量，深度受限）：
     * 未定义且无回退 → 丢弃声明（诊断已记）；超过深度仍有引用 → 丢弃声明 + 深度诊断。
     */
    private fun resolveVarRefs(
        value: String,
        themeVars: ThemeVars,
        property: String,
        diagnostics: MutableList<Diagnostic>,
    ): String? {
        var current = value
        var depth = 0
        while (depth < MAX_VAR_DEPTH && VAR_PATTERN.containsMatchIn(current)) {
            val matches = VAR_PATTERN.findAll(current).toList()
            current = replaceVarMatches(matches, current, themeVars, property, diagnostics) ?: return null
            depth++
        }
        return current
            .takeUnless { VAR_PATTERN.containsMatchIn(it) }
            ?: run {
                diagnostics += parseFailed("属性「$property」var 引用嵌套过深，已丢弃")
                null
            }
    }

    /** @ 规则（@media/@keyframes/@import 等）：一律丢弃；语句型跳到 ';'，块型跳到匹配 '}' */
    private fun skipAtRule(
        source: String,
        start: Int,
        diagnostics: MutableList<Diagnostic>,
    ): Int {
        val headEnd = (start + SNIPPET_LIMIT).coerceAtMost(source.length)
        val name = AT_RULE_NAME.find(source.substring(start, headEnd))?.value ?: "@…"
        diagnostics += parseFailed("不支持 @ 规则（$name…），已丢弃")
        val brace = source.indexOf('{', start)
        val semicolon = source.indexOf(';', start)
        if (brace < 0 && semicolon < 0) {
            diagnostics += parseFailed("@ 规则未闭合，尾部内容截断丢弃")
            return source.length
        }
        return when {
            semicolon >= 0 && (brace < 0 || semicolon < brace) -> semicolon + 1
            // 块型 @ 规则：嵌套花括号配对（@media 内含规则块）
            else -> skipAtRuleBlock(source, brace)
        }
    }

    /** 块型 @ 规则跳过：从规则块 '{' 起配对花括号，返回恢复扫描位置 */
    private fun skipAtRuleBlock(
        source: String,
        brace: Int,
    ): Int {
        var i = brace + 1
        var depth = 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return i
    }

    private val WHITESPACE_RUN = Regex("\\s+")
    private val AT_RULE_NAME = Regex("""@[A-Za-z-]+""")
}
