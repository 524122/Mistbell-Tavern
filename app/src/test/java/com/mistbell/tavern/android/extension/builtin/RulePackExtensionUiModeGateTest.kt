package com.mistbell.tavern.android.extension.builtin

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.repository.EnabledRulePack
import com.mistbell.tavern.android.data.repository.UiModeGatedRulePackSource
import com.mistbell.tavern.android.data.rulepack.RulePackContent
import com.mistbell.tavern.android.data.rulepack.RulePackMacro
import com.mistbell.tavern.android.data.rulepack.RulePackPrompt
import com.mistbell.tavern.android.data.rulepack.RulePackRegexRule
import com.mistbell.tavern.android.extension.PromptHookContext
import com.mistbell.tavern.android.extension.ReplyHookContext
import com.mistbell.tavern.android.util.MacroContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 规则包扩展四个入口经真实模式闸（[UiModeGatedRulePackSource]）的界面模式单测
 * （P3 执行边界，docs/UI_MODE.md）：简洁模式下提示词块 / 输入正则 / 输出正则 / 包宏
 * 全部不生效；高级模式恢复。生产接法是 AppContainer 给 RulePackExtension 挂同一个闸源。
 */
class RulePackExtensionUiModeGateTest {
    private val mode = MutableStateFlow(UiMode.ADVANCED)
    private val delegatePacks = MutableStateFlow<List<EnabledRulePack>?>(emptyList())

    private val mctx = MacroContext(char = "蒋珞", user = "阿伟")

    /** 四种能力齐全的包 */
    private fun fullPack() =
        EnabledRulePack(
            id = "all",
            name = "全能包",
            loadingOrder = 100,
            content =
                RulePackContent(
                    prompts = listOf(RulePackPrompt(name = "块", content = "包提示词块", position = "SYSTEM_END")),
                    regex =
                        listOf(
                            RulePackRegexRule(
                                name = "入",
                                pattern = "笨蛋",
                                replacement = "**笨蛋**",
                                applyTo = listOf("input"),
                            ),
                            RulePackRegexRule(
                                name = "出",
                                pattern = "再见",
                                replacement = "再会",
                                applyTo = listOf("output"),
                            ),
                        ),
                    macros = listOf(RulePackMacro(name = "mood", value = "{{char}}很开心")),
                ),
        )

    /** 生产同款接法：扩展读模式闸源 */
    private fun extensionOf() =
        RulePackExtension(
            UiModeGatedRulePackSource(
                delegate =
                    object : com.mistbell.tavern.android.data.repository.RulePackSource {
                        override val packs: kotlinx.coroutines.flow.StateFlow<List<EnabledRulePack>?> = delegatePacks
                    },
                mode = mode,
                scope = CoroutineScope(Dispatchers.Unconfined),
            ),
        )

    private fun segment(
        role: String,
        content: String,
    ) = PromptBuilder.Segment(ChatMessage(role = role, content = content), "测试")

    private fun baseSegments() =
        mutableListOf(
            segment("system", "角色卡"),
            segment("user", "笨蛋说谁呢"),
        )

    private fun promptHookContext(): PromptHookContext =
        PromptHookContext("owner", "char", "session", mctx) {
            error("本测试的拦截器不应触达 db（规则包提示词走纯数据路径）")
        }

    private fun replyHookContext() = ReplyHookContext("owner", "char", "session")

    @Test
    fun `简洁模式四个入口全部不生效`() =
        runBlocking {
            delegatePacks.value = listOf(fullPack())
            mode.value = UiMode.SIMPLE
            val ext = extensionOf()

            // 提示词块 + 输入正则都不生效（段数与内容原样）
            val segments = baseSegments()
            ext.intercept(segments, promptHookContext())
            assertEquals(2, segments.size)
            assertEquals("角色卡", segments[0].message.content)
            assertEquals("笨蛋说谁呢", segments[1].message.content)

            // 输出正则不生效
            assertEquals("再见", ext.process("再见", replyHookContext()))

            // 包宏不解析
            assertNull(ext.resolve("mood", mctx))
        }

    @Test
    fun `切回高级四个入口恢复`() =
        runBlocking {
            delegatePacks.value = listOf(fullPack())
            mode.value = UiMode.SIMPLE
            val ext = extensionOf()
            assertNull(ext.resolve("mood", mctx))

            mode.value = UiMode.ADVANCED
            // 让 runBlocking 事件循环里的 Unconfined combine 任务跑完，闸源值才是翻转后的
            yield()
            yield()

            val segments = baseSegments()
            ext.intercept(segments, promptHookContext())
            assertEquals(3, segments.size)
            assertEquals("包提示词块", segments[1].message.content)
            assertEquals("**笨蛋**说谁呢", segments[2].message.content)
            assertEquals("再会", ext.process("再见", replyHookContext()))
            assertEquals("蒋珞很开心", ext.resolve("mood", mctx))
        }

    @Test
    fun `简洁模式启动后再装载的包也不生效`() =
        runBlocking {
            // 进程从简洁模式启动：先建闸源再装载包，仍不生效
            mode.value = UiMode.SIMPLE
            delegatePacks.value = listOf(fullPack())
            val ext = extensionOf()
            val segments = baseSegments()
            ext.intercept(segments, promptHookContext())
            assertEquals(2, segments.size)
            assertNull(ext.resolve("mood", mctx))
        }
}
