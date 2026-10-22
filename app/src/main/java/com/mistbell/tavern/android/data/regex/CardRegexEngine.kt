package com.mistbell.tavern.android.data.regex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 卡级正则脚本引擎（酒馆 regex_scripts 的显示时子集）。
 *
 * 语义（对齐酒馆 display-time）：只作用于 AI 消息（placement 含 2）的**展示内容**，
 * 不改存储原文；脚本按数组顺序依次应用；disabled 跳过。
 *
 * findRegex 支持两种形态：/pattern/flags（flags 支持 i/m/s）与裸模式字符串；
 * replaceString 支持 Kotlin 组引用（$1…）。trimStrings / runOnEdit / minDepth 等不在本期。
 */
object CardRegexEngine {
    data class CardRegexScript(
        val name: String,
        val findRegex: String,
        val replaceString: String,
        val disabled: Boolean,
        val placement: List<Int>,
    )

    /** 从角色卡 extensions.regex_scripts 解析；坏条目跳过（坏卡不崩会话）。 */
    fun parse(extensions: JsonObject?): List<CardRegexScript> {
        val array =
            extensions?.get("regex_scripts") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val find = obj["findRegex"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            CardRegexScript(
                name = obj["scriptName"]?.jsonPrimitive?.contentOrNull ?: "未命名",
                findRegex = find,
                replaceString = obj["replaceString"]?.jsonPrimitive?.contentOrNull ?: "",
                disabled = obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                placement =
                    (obj["placement"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                        ?: emptyList(),
            )
        }
    }

    /** 对 AI 消息展示内容依次应用启用脚本（placement 含 2）。坏正则/坏替换单条跳过。 */
    fun applyToAiDisplay(
        content: String,
        scripts: List<CardRegexScript>,
    ): String {
        var result = content
        scripts
            .filter { !it.disabled && PLACEMENT_AI_OUTPUT in it.placement }
            .forEach { script ->
                compileRegex(script.findRegex)?.let { regex ->
                    runCatching {
                        result = regex.replace(result) { match -> substituteGroups(script.replaceString, match) }
                    }
                }
            }
        return result
    }

    /**
     * 酒馆语义的替换串展开（不用 Kotlin 的 $ 组引用——HTML/JS 内容里大量裸 $ 会被误炸）：
     * `$$`→字面 `$`；`$&`→整段命中；`$1`…`$99`→捕获组；`$<name>`→命名组；其余 `$` 原样。
     */
    internal fun substituteGroups(
        replacement: String,
        match: MatchResult,
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
                '<' -> i = expandNamedGroup(replacement, i, match, out)
                in '0'..'9' -> i = expandNumberedGroup(replacement, i, match, out)
                else -> out.append(ch).also { i++ }
            }
        }
        return out.toString()
    }

    /** `$<name>` → 命名组展开；未闭合按字面 `$` 处理。返回消费后的新下标。 */
    private fun expandNamedGroup(
        replacement: String,
        dollarIndex: Int,
        match: MatchResult,
        out: StringBuilder,
    ): Int {
        val close = replacement.indexOf('>', dollarIndex + 2)
        if (close < 0) {
            out.append('$')
            return dollarIndex + 1
        }
        val name = replacement.substring(dollarIndex + 2, close)
        out.append(match.groups[name]?.value.orEmpty())
        return close + 1
    }

    /** `$1`…`$99` → 数字组展开；越界组按空串展开不外炸。返回消费后的新下标。 */
    private fun expandNumberedGroup(
        replacement: String,
        dollarIndex: Int,
        match: MatchResult,
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
        out.append(value)
        return dollarIndex + 1 + digits.length
    }

    /** /pattern/flags（i/m/s）或裸模式；坏正则返回 null（调用方跳过） */
    internal fun compileRegex(findRegex: String): Regex? {
        val (pattern, flags) = splitPatternAndFlags(findRegex)
        return runCatching { Regex(pattern, flags) }.getOrNull()
    }

    private fun splitPatternAndFlags(findRegex: String): Pair<String, Set<RegexOption>> {
        val trimmed = findRegex.trim()
        if (trimmed.length > 2 && trimmed.startsWith("/") && trimmed.lastIndexOf('/') > 0) {
            val lastSlash = trimmed.lastIndexOf('/')
            val flags =
                trimmed.substring(lastSlash + 1).mapNotNull {
                    when (it) {
                        'i' -> RegexOption.IGNORE_CASE
                        'm' -> RegexOption.MULTILINE
                        's' -> RegexOption.DOT_MATCHES_ALL
                        else -> null
                    }
                }.toSet()
            return trimmed.substring(1, lastSlash) to flags
        }
        return findRegex to emptySet()
    }

    private const val PLACEMENT_AI_OUTPUT = 2
}
