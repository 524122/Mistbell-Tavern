package com.mistbell.tavern.android.render

import com.mistbell.tavern.android.data.repository.THEME_CAPABILITY_LAYOUT
import com.mistbell.tavern.android.data.repository.THEME_SUPPORTED_TEMPLATE_VERSION
import com.mistbell.tavern.android.data.repository.ThemeManifest
import com.mistbell.tavern.android.data.repository.layoutThemeVars
import com.mistbell.tavern.android.data.theme.ThemeSupport
import com.mistbell.tavern.android.render.model.DiagnosticSeverity
import com.mistbell.tavern.android.render.model.RenderDecision
import com.mistbell.tavern.android.render.model.TemplateNode
import com.mistbell.tavern.android.render.parse.CssParser
import com.mistbell.tavern.android.render.parse.TemplateParser
import com.mistbell.tavern.android.render.parse.TemplateValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 示例布局主题包协议测试（M6）：samples/theme-ink-novel/ 三件套（+ theme.json）能被
 * 真实解析链原样消费——manifest 解析、tokens 解析与 --mt-* 注入、TemplateParser 白名单、
 * CssParser 样式级联、TemplateValidator 可渲染判定。纯 JVM（解析器无 Android 依赖，
 * 按仓库相对路径读 fixtures，先例 CardRegexRealCardTest 的 ../build/… 同款）。
 */
class SampleInkNovelPackTest {
    private val packDir = File("../samples/theme-ink-novel")

    @Test
    fun `sample pack files exist`() {
        assertTrue("manifest.json 应存在", File(packDir, "manifest.json").isFile)
        assertTrue("theme.json 应存在", File(packDir, "theme.json").isFile)
        assertTrue("template.html 应存在", File(packDir, "template.html").isFile)
        assertTrue("style.css 应存在", File(packDir, "style.css").isFile)
    }

    @Test
    fun `manifest parses with layout capability and supported template version`() {
        val manifest =
            repositoryManifestJson().decodeFromString<ThemeManifest>(
                File(packDir, "manifest.json").readText(),
            )
        assertEquals("theme-ink-novel", manifest.id)
        assertTrue("id 必须过导入的 id 正则", Regex("^[A-Za-z0-9_-]{1,64}$").matches(manifest.id))
        assertEquals(THEME_CAPABILITY_LAYOUT, manifest.capabilities)
        assertEquals(THEME_SUPPORTED_TEMPLATE_VERSION, manifest.templateVersion)
    }

    @Test
    fun `theme json parses and injects full token bridge`() {
        val tokens =
            ThemeSupport.parseTokens(File(packDir, "theme.json").readText())
        assertNotNull("theme.json 必须能被 ThemeSupport 解析（导入校验同款）", tokens)
        val (light, dark) = layoutThemeVars(tokens!!)
        assertEquals("10 个规范变量浅色侧应全注入", 10, light.size)
        assertEquals("10 个规范变量深色侧应全注入", 10, dark.size)
        assertTrue("深浅色两套 token 应不同（示例包有 dark 覆盖块）", light != dark)
        // 点位抽查：变量名与 ThemeVars.CANONICAL_NAMES 对齐
        assertTrue(light.containsKey("--mt-primary"))
        assertTrue(light.containsKey("--mt-on-surface"))
    }

    @Test
    fun `template parses renderable with both slots`() {
        val document = TemplateParser.parse(File(packDir, "template.html").readText())
        assertEquals(THEME_SUPPORTED_TEMPLATE_VERSION, document.templateVersion)
        assertEquals("模板解析应零 ERROR 诊断", 0, document.diagnostics.count { it.severity == DiagnosticSeverity.ERROR })
        assertEquals("模板应可渲染（无危险构造、树非空）", RenderDecision.Renderable, TemplateValidator.decide(document))
        val slotNames = collectSlotNames(document.tree)
        assertTrue("模板应声明 narrative 槽位", "narrative" in slotNames)
        assertTrue("模板应声明 panels 槽位", "panels" in slotNames)
    }

    @Test
    fun `style sheet parses with serif typography and token bridge`() {
        // var(--mt-*) 在解析期完成替换（CssDeclaration 契约）：注入两枚 token 验证桥路连通
        val injected =
            com.mistbell.tavern.android.render.model.ThemeVars(
                mapOf("--mt-on-surface" to "#2b2b2b", "--mt-primary" to "#4a4238"),
            )
        val stylesheet = CssParser.parse(File(packDir, "style.css").readText(), injected)
        assertEquals(
            "样式表解析不应有 ERROR 级诊断",
            0,
            stylesheet.diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
        )
        assertTrue("样式表应产出规则", stylesheet.rules.isNotEmpty())
        val declarations = stylesheet.rules.flatMap { it.declarations }
        val fontFamily = declarations.firstOrNull { it.property == "font-family" }
        assertEquals("水墨主题的字体族应为白名单内的 serif", "serif", fontFamily?.value)
        val foreground = declarations.firstOrNull { it.property == "color" }
        assertEquals(
            "前景色应取注入的 --mt-on-surface（token 桥连通）",
            "#2b2b2b",
            foreground?.value,
        )
    }

    @Test
    fun `undefined token with fallback resolves to fallback value`() {
        // 不注入任何 token：带回退值的 var() 取回退值、无回退值的声明被丢弃（UNDEFINED_VAR WARN）
        val stylesheet = CssParser.parse(File(packDir, "style.css").readText())
        assertTrue(
            "未注入 token 时允许 WARN 诊断（回退语义），但不应有 ERROR",
            stylesheet.diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
        )
        val declarations = stylesheet.rules.flatMap { it.declarations }
        assertEquals("带回退值的 var() 应取回退值", "#2b2b2b", declarations.firstOrNull { it.property == "color" }?.value)
    }

    /** 递归收集模板树中的 slot 名 */
    private fun collectSlotNames(node: TemplateNode): Set<String> =
        when (node) {
            is TemplateNode.SlotNode -> setOf(node.name)
            is TemplateNode.ElementNode ->
                node.children.flatMap { collectSlotNames(it) }.toSet()
            is TemplateNode.TextNode -> emptySet()
        }

    /** 与 ThemePackRepository 同配置的 manifest 解析器（ignoreUnknownKeys + isLenient） */
    private fun repositoryManifestJson(): Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
}
