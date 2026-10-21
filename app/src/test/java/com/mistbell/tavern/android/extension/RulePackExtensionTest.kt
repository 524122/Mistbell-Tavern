package com.mistbell.tavern.android.extension

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.repository.EnabledRulePack
import com.mistbell.tavern.android.data.repository.RulePackSource
import com.mistbell.tavern.android.data.rulepack.RulePackContent
import com.mistbell.tavern.android.data.rulepack.RulePackMacro
import com.mistbell.tavern.android.data.rulepack.RulePackPrompt
import com.mistbell.tavern.android.data.rulepack.RulePackRegexRule
import com.mistbell.tavern.android.extension.builtin.RulePackExtension
import com.mistbell.tavern.android.util.MacroContext
import com.mistbell.tavern.android.util.MacroEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RulePackExtension 三钩子的 JVM 单测（缓存提供器用 fake lambda，不经 Room）。
 */
class RulePackExtensionTest {
    private val mctx = MacroContext(char = "蒋珞", user = "阿伟")

    @After
    fun tearDown() {
        ExtensionRegistry.resetForTesting()
        MacroEngine.resetMacroProvidersForTesting()
    }

    private fun pack(
        id: String,
        content: RulePackContent,
        loadingOrder: Int = 100,
    ) = EnabledRulePack(id = id, name = "包$id", loadingOrder = loadingOrder, content = content)

    private fun extensionOf(packs: List<EnabledRulePack>) =
        RulePackExtension(
            object : RulePackSource {
                override val packs: StateFlow<List<EnabledRulePack>?> = MutableStateFlow(packs)
            },
        )

    private fun segment(
        role: String,
        content: String,
    ) = PromptBuilder.Segment(ChatMessage(role = role, content = content), "测试")

    private fun baseSegments() =
        mutableListOf(
            segment("system", "角色卡"),
            segment("user", "当前消息"),
        )

    // ---- PromptInterceptor ----

    @Test
    fun `包提示词按落点注入且标注规则包来源`() =
        runBlocking {
            val content =
                RulePackContent(
                    prompts = listOf(RulePackPrompt(name = "p1", content = "注入内容", position = "SYSTEM_END")),
                )
            val segments = baseSegments()
            extensionOf(listOf(pack("a", content))).intercept(segments, promptHookContext())
            assertEquals(3, segments.size)
            assertEquals("注入内容", segments[1].message.content)
            assertEquals("规则包·包a·p1", segments[1].source)
            assertEquals("当前消息", segments[2].message.content)
        }

    @Test
    fun `包内禁用的提示词条目不生效`() =
        runBlocking {
            val content =
                RulePackContent(
                    prompts = listOf(RulePackPrompt(name = "p1", content = "注入内容", enabled = false)),
                )
            val segments = baseSegments()
            extensionOf(listOf(pack("a", content))).intercept(segments, promptHookContext())
            assertEquals(2, segments.size)
        }

    @Test
    fun `多包按 loadingOrder 升序应用`() =
        runBlocking {
            val late = pack("b", RulePackContent(prompts = listOf(RulePackPrompt("p", "晚包"))), loadingOrder = 200)
            val early = pack("a", RulePackContent(prompts = listOf(RulePackPrompt("p", "早包"))), loadingOrder = 50)
            val segments = baseSegments()
            // 提供器乱序返回，扩展内部按缓存序应用（缓存层已按 loadingOrder 排序，测试直接验证顺序效果）
            extensionOf(listOf(early, late)).intercept(segments, promptHookContext())
            val contents = segments.map { it.message.content }
            assertTrue(contents.indexOf("早包") < contents.indexOf("晚包"))
        }

    // ---- ReplyPostProcessor ----

    @Test
    fun `输出正则链式替换`() =
        runBlocking {
            val content =
                RulePackContent(
                    regex =
                        listOf(
                            RulePackRegexRule(name = "r1", pattern = "笨蛋", replacement = "**笨蛋**"),
                            RulePackRegexRule(name = "r2", pattern = "滚", replacement = "走"),
                        ),
                )
            val result = extensionOf(listOf(pack("a", content))).process("你这个笨蛋，滚！", replyHookContext())
            assertEquals("你这个**笨蛋**，走！", result)
        }

    @Test
    fun `无规则时回复逐字节不变`() =
        runBlocking {
            val reply = "原样回复"
            assertEquals(reply, extensionOf(emptyList()).process(reply, replyHookContext()))
            assertEquals(
                reply,
                extensionOf(listOf(pack("a", RulePackContent()))).process(reply, replyHookContext()),
            )
        }

    // ---- MacroProvider ----

    @Test
    fun `宏查表解析且支持一层嵌套内置宏`() {
        val content =
            RulePackContent(
                macros = listOf(RulePackMacro(name = "mood", value = "{{char}}很开心")),
            )
        val result = extensionOf(listOf(pack("a", content))).resolve("mood", mctx)
        assertEquals("蒋珞很开心", result)
    }

    @Test
    fun `宏名大小写不敏感且未命中返回 null`() {
        val content = RulePackContent(macros = listOf(RulePackMacro(name = "mood", value = "好")))
        val ext = extensionOf(listOf(pack("a", content)))
        assertEquals("好", ext.resolve("MOOD", mctx))
        assertNull(ext.resolve("unknown", mctx))
    }

    @Test
    fun `宏自引用不递归爆栈`() {
        val content = RulePackContent(macros = listOf(RulePackMacro(name = "x", value = "{{x}}")))
        // 走完整链路（注册表 + 宏桥）真实触发重入：render("{{x}}") → provider → render("{{x}}") → …
        // 不抛 StackOverflowError 即为通过；结果形态不约束（守卫返回原文）
        ExtensionRegistry.register(extensionOf(listOf(pack("a", content))))
        MacroEngine.registerMacroProvider { n, c ->
            ExtensionRegistry.macroProviders.firstNotNullOfOrNull { it.resolve(n, c) }
        }
        MacroEngine.render("{{x}}", mctx)
    }

    @Test
    fun `冷启动时挂起钩子等待首个加载结果（不按空表放行）`() =
        runBlocking {
            val flow = MutableStateFlow<List<EnabledRulePack>?>(null)
            val ext =
                RulePackExtension(
                    object : RulePackSource {
                        override val packs: StateFlow<List<EnabledRulePack>?> = flow
                    },
                )
            val segments = baseSegments()
            val job =
                launch {
                    ext.intercept(segments, promptHookContext())
                }
            yield()
            // 缓存未加载完成：拦截器挂起等待，段列表保持原样（不放行残缺提示词）
            assertEquals(2, segments.size)
            flow.value =
                listOf(
                    pack("a", RulePackContent(prompts = listOf(RulePackPrompt("p", "迟到的注入", "SYSTEM_END")))),
                )
            job.join()
            assertEquals(3, segments.size)
            assertEquals("迟到的注入", segments[1].message.content)
        }

    private fun promptHookContext(): PromptHookContext =
        PromptHookContext("owner", "char", "session", mctx) {
            error("本测试的拦截器不应触达 db（规则包提示词走纯数据路径）")
        }

    private fun replyHookContext() = ReplyHookContext("owner", "char", "session")
}
