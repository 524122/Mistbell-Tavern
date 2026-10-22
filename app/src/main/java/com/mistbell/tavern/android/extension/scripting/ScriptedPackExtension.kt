package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.extension.MacroProvider
import com.mistbell.tavern.android.extension.PromptHookContext
import com.mistbell.tavern.android.extension.PromptInterceptor
import com.mistbell.tavern.android.extension.ReplyHookContext
import com.mistbell.tavern.android.extension.ReplyPostProcessor
import com.mistbell.tavern.android.extension.TavernExtension
import com.mistbell.tavern.android.extension.builtin.CustomPromptExtension
import com.mistbell.tavern.android.util.MacroContext

/**
 * 脚本包钩子适配层：把 [ScriptRuntimeManager] 的 JS 调用接到第 1 层既有钩子通道。
 *
 * 本体极薄——生命周期/动态命令/事件都在管理器里；这里只做三钩子的参数拼装与结果落地。
 */
class ScriptedPackExtension(
    private val manager: ScriptRuntimeManager,
) : TavernExtension, PromptInterceptor, ReplyPostProcessor, MacroProvider {
    override val id: String = "scripted-packs"
    override val name: String = "脚本包"
    override val loadingOrder: Int = LOADING_ORDER

    override suspend fun intercept(
        segments: MutableList<PromptBuilder.Segment>,
        context: PromptHookContext,
    ) {
        val ctxJson =
            ScriptRuntimeManager.ctxJson(
                sessionId = context.sessionId,
                char = context.mctx.char,
                user = context.mctx.user,
            )
        manager.promptInjections(ctxJson).forEach { (packName, injections) ->
            val prompts =
                injections.map { injection ->
                    CustomPromptEntity(
                        id = "script:$packName:${injection.name}",
                        name = "$packName·${injection.name}",
                        content = injection.content,
                        type = "SCRIPT",
                        position = injection.position,
                        isEnabled = true,
                    )
                }
            CustomPromptExtension.applyCustomPrompts(segments, prompts, context.mctx, SOURCE_PREFIX)
        }
    }

    override suspend fun process(
        reply: String,
        context: ReplyHookContext,
    ): String =
        manager.processReplies(
            reply,
            ScriptRuntimeManager.ctxJson(sessionId = context.sessionId),
        )

    override fun resolve(
        name: String,
        ctx: MacroContext,
    ): String? = manager.callMacro(name, ScriptRuntimeManager.ctxJson("", ctx.char, ctx.user))

    companion object {
        /** 排在规则包（200）之后：声明式数据先于可执行脚本 */
        private const val LOADING_ORDER = 300
        private const val SOURCE_PREFIX = "脚本·"
    }
}
