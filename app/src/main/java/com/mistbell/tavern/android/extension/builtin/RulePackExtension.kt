package com.mistbell.tavern.android.extension.builtin

import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.repository.RulePackSource
import com.mistbell.tavern.android.data.repository.awaitPacks
import com.mistbell.tavern.android.data.repository.currentPacks
import com.mistbell.tavern.android.extension.MacroProvider
import com.mistbell.tavern.android.extension.PromptHookContext
import com.mistbell.tavern.android.extension.PromptInterceptor
import com.mistbell.tavern.android.extension.ReplyHookContext
import com.mistbell.tavern.android.extension.ReplyPostProcessor
import com.mistbell.tavern.android.extension.TavernExtension
import com.mistbell.tavern.android.util.MacroContext
import com.mistbell.tavern.android.util.MacroEngine

/**
 * 规则包扩展：把当前启用的规则包（声明式数据）接到第 1 层三个钩子上。
 *
 * 单一内置扩展实例运行时读取启用包缓存，启用/禁用即时生效、无需重启——
 * 不做"每包一个扩展实例"的动态注册（禁用要先注销实例，链路反而复杂）。
 *
 * 冷启动语义：挂起钩子（prompt/reply）用 [awaitPacks] 确定性等待首次加载完成；
 * 同步钩子（宏）用 [currentPacks]，未加载完成时按空表处理。
 *
 * @param source 启用包缓存（AppContainer 的 EnabledRulePacksCache；测试注入假实现）
 */
class RulePackExtension(
    private val source: RulePackSource,
) : TavernExtension, PromptInterceptor, ReplyPostProcessor, MacroProvider {
    override val id: String = "rule-packs"
    override val name: String = "规则包"
    override val loadingOrder: Int = LOADING_ORDER

    // ---- PromptInterceptor：包 prompts → 复用自定义提示词的落点语义 ----

    override suspend fun intercept(
        segments: MutableList<PromptBuilder.Segment>,
        context: PromptHookContext,
    ) {
        source.awaitPacks().forEach { pack ->
            val prompts =
                pack.content.prompts.map { prompt ->
                    CustomPromptEntity(
                        id = "${pack.id}:${prompt.name}",
                        name = "${pack.name}·${prompt.name}",
                        content = prompt.content,
                        type = "RULE_PACK",
                        position = prompt.position,
                        isEnabled = prompt.enabled,
                        priority = prompt.priority,
                    )
                }
            CustomPromptExtension.applyCustomPrompts(segments, prompts, context.mctx, SOURCE_PREFIX)
        }
    }

    // ---- ReplyPostProcessor：包 regex 规则链式作用于回复正文 ----

    override suspend fun process(
        reply: String,
        context: ReplyHookContext,
    ): String {
        var result = reply
        source.awaitPacks().forEach { pack ->
            pack.content.regex.filter { it.enabled }.forEach { rule ->
                result = Regex(rule.pattern).replace(result, rule.replacement)
            }
        }
        return result
    }

    // ---- MacroProvider：包 macros 查表；值支持一层嵌套渲染（内置宏/其他包宏），防自递归 ----

    /**
     * 重入守卫：宏值里再写 {{自身}} 时嵌套渲染直接返回原文，防无限递归栈溢出。
     * MacroProvider.resolve 是同步函数，ThreadLocal 与 MacroEngine.render 的单线程语义匹配。
     */
    private val resolving = ThreadLocal.withInitial { false }

    override fun resolve(
        name: String,
        ctx: MacroContext,
    ): String? {
        val macro =
            source.currentPacks().firstNotNullOfOrNull { pack ->
                pack.content.macros.firstOrNull { it.enabled && it.name.equals(name, ignoreCase = true) }
            }
        return macro?.let { renderMacroValue(it, ctx) }
    }

    private fun renderMacroValue(
        macro: com.mistbell.tavern.android.data.rulepack.RulePackMacro,
        ctx: MacroContext,
    ): String {
        if (resolving.get()) return macro.value
        resolving.set(true)
        return try {
            MacroEngine.render(macro.value, ctx)
        } finally {
            resolving.set(false)
        }
    }

    companion object {
        /** 排在自定义提示词（100）之后：用户自定义先于规则包注入 */
        private const val LOADING_ORDER = 200
        private const val SOURCE_PREFIX = "规则包·"
    }
}
