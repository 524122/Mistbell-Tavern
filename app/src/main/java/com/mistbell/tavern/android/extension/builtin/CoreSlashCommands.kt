package com.mistbell.tavern.android.extension.builtin

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.extension.ExtensionRegistry
import com.mistbell.tavern.android.extension.SlashCommandContext
import com.mistbell.tavern.android.extension.SlashCommandHandler
import com.mistbell.tavern.android.extension.TavernExtension

/** `/help`：列出注册表中的全部斜杠命令（含别名与描述）。 */
class HelpCommand : TavernExtension, SlashCommandHandler {
    override val id: String = "core-help"
    override val name: String = "help"
    override val loadingOrder: Int = LOADING_ORDER
    override val description: String = "列出全部可用命令"

    override suspend fun execute(
        args: String,
        ctx: SlashCommandContext,
    ): String {
        val commands = ExtensionRegistry.slashCommands
        if (commands.isEmpty()) return "暂无可用命令"
        return commands.joinToString("\n") { command ->
            val aliases = command.aliases.joinToString("") { "（别名 /$it）" }
            "/${command.name}$aliases：${command.description}"
        }
    }

    private companion object {
        const val LOADING_ORDER = 10
    }
}

/**
 * `/note <内容>`：设置当前会话附加指令（author_note，注入在历史之后）；
 * 空参数清除。对齐酒馆作者注释的实时覆盖语义。
 */
class NoteCommand : TavernExtension, SlashCommandHandler {
    override val id: String = "core-note"
    override val name: String = "note"
    override val loadingOrder: Int = LOADING_ORDER
    override val aliases: List<String> = listOf("an")
    override val description: String = "设置会话附加指令（空参数清除）"

    override suspend fun execute(
        args: String,
        ctx: SlashCommandContext,
    ): String {
        val db = TavernApplication.instance.container.database
        val session =
            db.sessionDao().get(ctx.sessionId, ctx.ownerId, ctx.characterId)
                ?: return "未找到当前会话"
        val note = args.trim()
        db.sessionDao().upsert(session.copy(authorNote = note))
        return if (note.isEmpty()) {
            "已清除会话附加指令"
        } else {
            "已设置会话附加指令：${note.take(PREVIEW_LENGTH)}"
        }
    }

    private companion object {
        const val LOADING_ORDER = 20
        const val PREVIEW_LENGTH = 30
    }
}
