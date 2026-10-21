package com.mistbell.tavern.android.extension.slash

import com.mistbell.tavern.android.extension.ExtensionRegistry
import com.mistbell.tavern.android.extension.SlashCommandContext
import kotlinx.coroutines.CancellationException

/**
 * 斜杠命令分发器：解析 → 查注册表 → 执行，返回展示给用户的文本。
 *
 * 不抛业务异常：命令不存在/执行失败都转为提示文本（协程取消原样上抛）。
 * 无 Android 依赖，JVM 单测可直测。
 */
object SlashCommandDispatcher {
    @Suppress("TooGenericExceptionCaught") // 命令边界必须兜住任意扩展异常
    suspend fun dispatch(
        text: String,
        ctx: SlashCommandContext,
    ): String {
        val parsed = SlashCommandParser.parse(text)
        val command = parsed?.let { ExtensionRegistry.findSlashCommand(it.name) }
        return when {
            parsed == null -> "不是有效的命令（输入 /help 查看全部命令）"
            command == null -> "未知命令 /${parsed.name}（输入 /help 查看全部命令）"
            else ->
                try {
                    command.execute(parsed.args, ctx).ifBlank { "命令 /${parsed.name} 已执行" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "命令 /${parsed.name} 执行失败：${e.message}"
                }
        }
    }
}
