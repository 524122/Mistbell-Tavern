package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.extension.PromptHookContext
import com.mistbell.tavern.android.extension.PromptInterceptor
import com.mistbell.tavern.android.extension.ReplyHookContext
import com.mistbell.tavern.android.extension.ReplyPostProcessor
import com.mistbell.tavern.android.extension.TavernExtension
import com.mistbell.tavern.android.extension.builtin.CustomPromptExtension

/**
 * 扩展包宿主钩子适配层：把 [ExtensionHookRuntimeManager] 的 JS 调用接到第 1 层既有钩子通道
 * （[ScriptedPackExtension] 的同型薄壳——生命周期/事件都在管理器里，这里只做参数拼装与落地）。
 *
 * - prompt:inject：onPrompt 产出经 CustomPromptExtension.applyCustomPrompts 落五档 position
 *   （来源标「扩展·」），包间执行顺序 = 包 loadingOrder（管理器保证）
 * - reply:process：onReply 链式处理，落库前生效
 */
class ExtensionHookExtension(
    private val manager: ExtensionHookRuntimeManager,
) : TavernExtension,
    PromptInterceptor,
    ReplyPostProcessor {
    override val id: String = "extension-hooks"
    override val name: String = "扩展包钩子"
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
        manager.promptInjections(ctxJson).forEach { hooked ->
            val prompts =
                hooked.injections.map { injection ->
                    CustomPromptEntity(
                        id = "exthook:${hooked.packId}:${injection.name}",
                        name = "${hooked.packName}·${injection.name}",
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

    companion object {
        /** 排在规则包（200）与脚本包（300）之后、MVU 回复处理（400）之前：钩子产出的
         * <UpdateVariable> 块仍会被 MVU 层解析 */
        private const val LOADING_ORDER = 350
        private const val SOURCE_PREFIX = "扩展·"
    }
}
