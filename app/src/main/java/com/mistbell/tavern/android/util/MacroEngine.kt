package com.mistbell.tavern.android.util

import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * 宏替换引擎（F2.1，生态卡"最后一公里"）。
 *
 * 设计依据 docs/PREDECESSORS.md（Tavo 宏文档 + OMate 宏清单，功能面借鉴）：
 * - Tier1 纯替换：{{char}}/{{user}}/{{persona}}/{{description}}/{{personality}}/{{scenario}}/
 *   {{time}}/{{date}}/{{weekday}}/{{isotime}}/{{isodate}}/{{newline}}/{{space}}/{{noop}}/{{//注释}}
 *   及旧式大写 <CHAR>/<USER>/<BOT>（无花括号，ST 老卡常见）
 * - Tier2 参数宏：{{random::a::b::c}}（随机取一）、{{roll::3d6}}（掷骰求和）
 * - 条件块：{{#if:宏名}}…{{/if}}——仅"非空"判断（0/false 视为非空，与 Tavo 语义一致），
 *   不支持 else/比较/嵌套；找不到的宏名视为空
 * - 未知宏【原样保留】；任何异常整体回退原文（坏卡不崩会话）
 * - 字段注入（{{description}} 等）不做二次渲染，天然防递归
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测；Random 可注入保证测试确定性。
 */
data class MacroContext(
    val char: String = "",
    val user: String = "User",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val persona: String = "",
)

object MacroEngine {
    private val CONDITIONAL = Regex("""\{\{#if:([^{}]+)\}\}([\s\S]*?)\{\{/if\}\}""")
    private val SIMPLE = Regex("""\{\{([^{}]+)\}\}""")
    private val ROLL_SPEC = Regex("""(\d*)d(\d+)""")

    /** 渲染：text 为空原样返回；永不抛异常。 */
    fun render(
        text: String?,
        ctx: MacroContext,
        random: Random = Random.Default,
    ): String {
        if (text.isNullOrEmpty()) return text ?: ""
        return try {
            var out = text
            // 1) 条件块（非嵌套；命中保留 body 继续参与后续渲染，未命中剔除）
            out =
                CONDITIONAL.replace(out) { m ->
                    val name = m.groupValues[1].trim()
                    if (resolveName(name, ctx, random)?.isNotBlank() == true) m.groupValues[2] else ""
                }
            // 2) 花括号宏（未知原样保留）
            out =
                SIMPLE.replace(out) { m ->
                    resolveName(m.groupValues[1].trim(), ctx, random) ?: m.value
                }
            // 3) 旧式大写占位符（无花括号）
            out =
                out
                    .replace("<CHAR>", ctx.char)
                    .replace("<USER>", ctx.user)
                    .replace("<BOT>", ctx.char)
            out
        } catch (_: Exception) {
            text
        }
    }

    /** 解析单个宏名 → 值；未知返回 null（调用方原样保留）。 */
    private fun resolveName(
        token: String,
        ctx: MacroContext,
        random: Random,
    ): String? {
        val lower = token.lowercase()
        return when {
            lower == "char" || lower == "character" || lower == "bot" -> ctx.char
            lower == "user" -> ctx.user
            lower == "description" || lower == "chardescription" -> ctx.description
            lower == "personality" -> ctx.personality
            lower == "scenario" || lower == "charscenario" -> ctx.scenario
            lower == "persona" -> ctx.persona
            lower == "time" -> LocalTime.now().toString().substringBeforeLast(".") // HH:mm:ss
            lower == "isotime" -> LocalTime.now().toString() // 含秒.纳秒
            lower == "date" || lower == "isodate" -> LocalDate.now().toString() // yyyy-MM-dd
            lower == "weekday" -> LocalDate.now().dayOfWeek.toString()
            lower == "newline" -> "\n"
            lower == "space" -> " "
            lower == "noop" -> ""
            lower.startsWith("//") -> "" // {{//注释}}
            lower.startsWith("random::") -> {
                val options = token.substringAfter("random::").split("::")
                if (options.any { it.isBlank() }) null else options[random.nextInt(options.size)]
            }
            lower.startsWith("roll::") -> roll(token.substringAfter("roll::").trim(), random)
            else -> null
        }
    }

    /** NdM 掷骰求和（N 缺省 1，上限防滥用：N≤100、M≤1000）；非法规格返回 null。 */
    private fun roll(
        spec: String,
        random: Random,
    ): String? {
        val m = ROLL_SPEC.matchEntire(spec) ?: return null
        val count = m.groupValues[1].ifBlank { "1" }.toIntOrNull() ?: return null
        val sides = m.groupValues[2].toIntOrNull() ?: return null
        if (count < 1 || sides < 2 || count > 100 || sides > 1000) return null
        var sum = 0
        repeat(count) { sum += 1 + random.nextInt(sides) }
        return sum.toString()
    }
}
