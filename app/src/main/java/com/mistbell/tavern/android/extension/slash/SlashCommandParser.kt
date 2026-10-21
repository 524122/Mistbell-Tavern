package com.mistbell.tavern.android.extension.slash

/** 解析结果：命令名（小写）与原始参数串 */
data class ParsedSlashCommand(
    val name: String,
    val args: String,
)

/**
 * 斜杠命令解析器（对标酒馆 SlashCommandParser 的最小子集）：`/name args`。
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测。参数保持原始串（含引号）交给命令自行解析——
 * 现阶段内置命令都用整段文本，不做命名参数/引号成组（需要时在命令层自行处理）。
 */
object SlashCommandParser {
    /**
     * 文本是否为斜杠命令：`/` 开头且非 `//`（双斜杠按普通文本处理，防误触发）。
     */
    fun isSlashCommand(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.length > 1 && trimmed.startsWith("/") && !trimmed.startsWith("//")
    }

    /** 解析 `/name args`；非命令文本或仅有 `/` 返回 null。 */
    fun parse(text: String): ParsedSlashCommand? {
        if (!isSlashCommand(text)) return null
        val body = text.trimStart().removePrefix("/")
        val name = body.takeWhile { !it.isWhitespace() }
        return if (name.isBlank()) {
            null
        } else {
            ParsedSlashCommand(
                name = name.lowercase(),
                args = body.removePrefix(name).trim(),
            )
        }
    }
}
