package com.mistbell.tavern.android.data.regex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 卡级正则脚本引擎：按数组顺序执行，坏正则或坏替换只跳过当前条目。
 *
 * 支持用户输入、AI 展示及提示词三类路径；显示/提示词规则由 markdownOnly 与
 * promptOnly 分流，并支持楼层深度、编辑开关、捕获组裁剪。原始 AI 消息不被改写。
 */
@Suppress("TooManyFunctions") // 解析、场景过滤与替换展开是同一纯函数引擎的内聚职责
object CardRegexEngine {
    data class CardRegexScript(
        val name: String,
        val findRegex: String,
        val replaceString: String,
        val disabled: Boolean,
        val placement: List<Int>,
        val trimStrings: List<String> = emptyList(),
        val markdownOnly: Boolean = false,
        val promptOnly: Boolean = false,
        val runOnEdit: Boolean = false,
        val minDepth: Int? = null,
        val maxDepth: Int? = null,
    )

    private enum class ApplyMode {
        NORMAL,
        DISPLAY,
        PROMPT,
    }

    /** 从角色卡 extensions.regex_scripts 解析；结构不合法的条目跳过。 */
    fun parse(extensions: JsonObject?): List<CardRegexScript> {
        val array = extensions?.get("regex_scripts") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val find = obj.string("findRegex") ?: return@mapNotNull null
            CardRegexScript(
                name = obj.string("scriptName") ?: "未命名",
                findRegex = find,
                replaceString = obj.string("replaceString").orEmpty(),
                disabled = obj.boolean("disabled") ?: false,
                placement = obj.intList("placement"),
                trimStrings = obj.stringList("trimStrings"),
                markdownOnly = obj.boolean("markdownOnly") ?: false,
                promptOnly = obj.boolean("promptOnly") ?: false,
                runOnEdit = obj.boolean("runOnEdit") ?: false,
                minDepth = obj.int("minDepth"),
                maxDepth = obj.int("maxDepth"),
            )
        }
    }

    private data class ApplyContext(
        val placement: Int,
        val mode: ApplyMode,
        val depth: Int? = null,
        val isEdit: Boolean = false,
    )

    fun applyToAiDisplay(
        content: String,
        scripts: List<CardRegexScript>,
        depth: Int? = null,
        isEdit: Boolean = false,
    ): String = apply(content, scripts, ApplyContext(PLACEMENT_AI_OUTPUT, ApplyMode.DISPLAY, depth, isEdit))

    /** 按消息角色选择 placement；系统消息不进入卡级对话正则。 */
    fun applyToDisplay(
        content: String,
        role: String,
        scripts: List<CardRegexScript>,
        depth: Int? = null,
        isEdit: Boolean = false,
    ): String =
        when (role) {
            "user" ->
                apply(
                    content,
                    scripts,
                    ApplyContext(PLACEMENT_USER_INPUT, ApplyMode.DISPLAY, depth, isEdit),
                )
            "assistant" -> applyToAiDisplay(content, scripts, depth, isEdit)
            else -> content
        }

    /** 发送或编辑用户消息时执行普通规则；只用于显示或提示词的规则不在这里执行。 */
    fun applyToUserInput(
        content: String,
        scripts: List<CardRegexScript>,
        isEdit: Boolean = false,
    ): String = apply(content, scripts, ApplyContext(PLACEMENT_USER_INPUT, ApplyMode.NORMAL, isEdit = isEdit))

    /** 当前消息 depth=0，最近一条历史 depth=1，依次向前递增。 */
    fun applyToPrompt(
        content: String,
        role: String,
        scripts: List<CardRegexScript>,
        depth: Int,
        isEdit: Boolean = false,
    ): String =
        when (role) {
            "user" ->
                apply(
                    content,
                    scripts,
                    ApplyContext(PLACEMENT_USER_INPUT, ApplyMode.PROMPT, depth, isEdit),
                )
            "assistant" ->
                apply(
                    content,
                    scripts,
                    ApplyContext(PLACEMENT_AI_OUTPUT, ApplyMode.PROMPT, depth, isEdit),
                )
            else -> content
        }

    private fun apply(
        content: String,
        scripts: List<CardRegexScript>,
        context: ApplyContext,
    ): String {
        var result = content
        scripts.forEach { script ->
            if (!script.appliesTo(context)) return@forEach
            val compiled = compilePattern(script.findRegex) ?: return@forEach
            runCatching {
                val replacement = script.replaceString.replace(MATCH_MACRO_REGEX) { "$0" }
                if (compiled.replaceAll) {
                    result =
                        compiled.regex.replace(result) { match ->
                            substituteGroups(replacement, match, script.trimStrings)
                        }
                } else {
                    compiled.regex.find(result)?.let { match ->
                        val expanded = substituteGroups(replacement, match, script.trimStrings)
                        result = result.replaceRange(match.range, expanded)
                    }
                }
            }
        }
        return result
    }

    private fun CardRegexScript.appliesTo(context: ApplyContext): Boolean =
        isUsable(context.placement, context.isEdit) && isInDepth(context.depth) && supports(context.mode)

    private fun CardRegexScript.isUsable(
        placement: Int,
        isEdit: Boolean,
    ): Boolean = !disabled && findRegex.isNotEmpty() && placement in this.placement && (!isEdit || runOnEdit)

    private fun CardRegexScript.isInDepth(depth: Int?): Boolean {
        if (depth == null) return true
        val afterMin = minDepth?.let { it < -1 || depth >= it } ?: true
        val beforeMax = maxDepth?.let { it < 0 || depth <= it } ?: true
        return afterMin && beforeMax
    }

    private fun CardRegexScript.supports(mode: ApplyMode): Boolean =
        when (mode) {
            ApplyMode.DISPLAY -> markdownOnly || (!markdownOnly && !promptOnly)
            ApplyMode.PROMPT -> promptOnly || (!markdownOnly && !promptOnly)
            ApplyMode.NORMAL -> !markdownOnly && !promptOnly
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

    private fun JsonObject.intList(key: String): List<Int> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
            ?: emptyList()

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /**
     * 替换串展开：`$$`→字面 `$`；`$&`→原始整段命中；`$0`…`$99` 与 `$<name>`
     * →应用 trimStrings 后的捕获组；其他 `$` 保持原样，避免误伤 HTML/JS。
     */
    internal fun substituteGroups(
        replacement: String,
        match: MatchResult,
        trimStrings: List<String> = emptyList(),
    ): String {
        val out = StringBuilder(replacement.length)
        var i = 0
        while (i < replacement.length) {
            val ch = replacement[i]
            if (ch != '$' || i + 1 >= replacement.length) {
                out.append(ch)
                i++
                continue
            }
            when (replacement[i + 1]) {
                '$' -> out.append('$').also { i += 2 }
                '&' -> out.append(match.value).also { i += 2 }
                '<' -> i = expandNamedGroup(replacement, i, match, trimStrings, out)
                in '0'..'9' -> i = expandNumberedGroup(replacement, i, match, trimStrings, out)
                else -> out.append(ch).also { i++ }
            }
        }
        return out.toString()
    }

    private fun expandNamedGroup(
        replacement: String,
        dollarIndex: Int,
        match: MatchResult,
        trimStrings: List<String>,
        out: StringBuilder,
    ): Int {
        val close = replacement.indexOf('>', dollarIndex + 2)
        if (close < 0) {
            out.append('$')
            return dollarIndex + 1
        }
        val name = replacement.substring(dollarIndex + 2, close)
        val value = match.groups[name]?.value.orEmpty()
        out.append(trimCapturedValue(value, trimStrings))
        return close + 1
    }

    private fun expandNumberedGroup(
        replacement: String,
        dollarIndex: Int,
        match: MatchResult,
        trimStrings: List<String>,
        out: StringBuilder,
    ): Int {
        val digits = replacement.substring(dollarIndex + 1).takeWhile { it.isDigit() }.take(2)
        val groupIndex = digits.toIntOrNull()
        val value =
            if (groupIndex != null && groupIndex < match.groups.size) {
                match.groups[groupIndex]?.value.orEmpty()
            } else {
                ""
            }
        out.append(trimCapturedValue(value, trimStrings))
        return dollarIndex + 1 + digits.length
    }

    private fun trimCapturedValue(
        value: String,
        trimStrings: List<String>,
    ): String = trimStrings.fold(value) { text, trim -> text.replace(trim, "") }

    private data class CompiledPattern(
        val regex: Regex,
        val replaceAll: Boolean,
    )

    /** /pattern/flags 或裸模式；g 控制全部/首次替换，i/m/s 映射为 Kotlin 选项。 */
    private fun compilePattern(findRegex: String): CompiledPattern? {
        val trimmed = findRegex.trim()
        val lastSlash = trimmed.lastIndexOf('/')
        val literal = trimmed.length > 2 && trimmed.startsWith("/") && lastSlash > 0
        val flagText = if (literal) trimmed.substring(lastSlash + 1) else ""
        if (literal && flagText.any { it !in "gimsuy" }) return null
        val pattern = if (literal) trimmed.substring(1, lastSlash) else findRegex
        val options =
            flagText.mapNotNull {
                when (it) {
                    'i' -> RegexOption.IGNORE_CASE
                    'm' -> RegexOption.MULTILINE
                    's' -> RegexOption.DOT_MATCHES_ALL
                    else -> null
                }
            }.toSet()
        return runCatching { CompiledPattern(Regex(pattern, options), 'g' in flagText) }.getOrNull()
    }

    internal fun compileRegex(findRegex: String): Regex? = compilePattern(findRegex)?.regex

    private val MATCH_MACRO_REGEX = Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE)
    private const val PLACEMENT_USER_INPUT = 1
    private const val PLACEMENT_AI_OUTPUT = 2
}
