package com.mistbell.tavern.android.data.regex

import com.mistbell.tavern.android.data.local.entity.GlobalRegexRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局正则存取与编排测试：实体↔脚本↔草稿三态映射、GLOBAL→SCOPED 执行顺序、
 * 卡级停用名单解析。
 */
class GlobalRegexStoreTest {
    @Test
    fun `实体转脚本保留 placement 与深度语义`() {
        val entity =
            GlobalRegexRuleEntity(
                id = "a",
                name = "剥思维链",
                findRegex = "<think>[\\s\\S]*?</think>",
                replaceString = "",
                placementJson = "[1,2]",
                trimStringsJson = "[\"玩家说：\"]",
                markdownOnly = true,
                minDepth = -1,
                maxDepth = 4,
                sortOrder = 0,
            )
        val script = GlobalRegexStore.toScript(entity)
        assertEquals("剥思维链", script.name)
        assertEquals(listOf(1, 2), script.placement)
        assertEquals(listOf("玩家说："), script.trimStrings)
        assertTrue(script.markdownOnly)
        assertEquals(-1, script.minDepth)
        assertEquals(4, script.maxDepth)
    }

    @Test
    fun `草稿保存再加载往返不丢字段`() {
        val draft =
            RegexRuleDraft(
                id = 0,
                name = "状态行转面板",
                findRegex = "^【(.+?)】(.+)$",
                replaceString = "<panel title=\"$1\">$2</panel>",
                userInput = true,
                aiOutput = true,
                promptOnly = true,
                runOnEdit = true,
                minDepthText = "0",
                maxDepthText = "8",
                trimStringsText = "旁白\n系统",
            )
        val entities = GlobalRegexStore.fromDrafts(listOf(draft), now = 123L)
        assertEquals(1, entities.size)
        val entity = entities.single()
        assertEquals(0, entity.sortOrder)
        assertEquals("[1,2]", entity.placementJson)
        assertEquals(123L, entity.createdAt)

        val restored = GlobalRegexStore.toDocument(entities).rules.single()
        assertEquals(draft.name, restored.name)
        assertEquals(draft.findRegex, restored.findRegex)
        assertEquals(draft.replaceString, restored.replaceString)
        assertTrue(restored.userInput)
        assertTrue(restored.aiOutput)
        assertTrue(restored.promptOnly)
        assertTrue(restored.runOnEdit)
        assertEquals("0", restored.minDepthText)
        assertEquals("8", restored.maxDepthText)
        assertEquals("旁白\n系统", restored.trimStringsText)
    }

    @Test
    fun `组合顺序全局先于卡级`() {
        val global = listOf(script("g1"))
        val card = listOf(script("c1"))
        val combined = GlobalRegexStore.combine(global, card)
        assertEquals(listOf("g1", "c1"), combined.map { it.name })
    }

    @Test
    fun `组合后按列表顺序执行全局产物可被卡级继续改写`() {
        // 全局先剥掉包裹，卡级再把剩余文本改名——体现 global 先执行的管线语义
        val scripts =
            GlobalRegexStore.combine(
                listOf(script("unwrap", findRegex = "\\[wrap\\](.+?)\\[/wrap\\]", replaceString = "$1")),
                listOf(script("rename", findRegex = "旧名", replaceString = "新名")),
            )
        val result =
            CardRegexEngine.applyToAiDisplay(
                "[wrap]旧名[/wrap]",
                scripts,
            )
        assertEquals("新名", result)
    }

    @Test
    fun `坏 placement JSON 容错为空不炸整表`() {
        val entity =
            GlobalRegexRuleEntity(
                id = "bad",
                name = "坏规则",
                findRegex = "x",
                replaceString = "",
                placementJson = "not-json",
                trimStringsJson = "{bad",
            )
        val script = GlobalRegexStore.toScript(entity)
        assertTrue(script.placement.isEmpty())
        assertTrue(script.trimStrings.isEmpty())
        // placement 为空的规则不命中任何场景：内容原样保留
        assertEquals("original", CardRegexEngine.applyToAiDisplay("original", listOf(script)))
    }

    @Test
    fun `停用名单解析与格式化往返`() {
        assertEquals(emptySet<String>(), GlobalRegexStore.parseDisabledIds(null))
        assertEquals(emptySet<String>(), GlobalRegexStore.parseDisabledIds(""))
        assertEquals(setOf("a", "b"), GlobalRegexStore.parseDisabledIds("a,b"))
        assertEquals(setOf("a", "b"), GlobalRegexStore.parseDisabledIds(" a , b ,"))
        // Set 无序：只断言往返等价，不断言落盘顺序
        assertEquals(
            setOf("b", "a"),
            GlobalRegexStore.parseDisabledIds(GlobalRegexStore.formatDisabledIds(setOf("b", "a"))),
        )
        assertEquals(
            setOf("x"),
            GlobalRegexStore.parseDisabledIds(GlobalRegexStore.formatDisabledIds(setOf("x"))),
        )
    }

    private fun script(
        name: String,
        findRegex: String = "x",
        replaceString: String = "",
    ): CardRegexEngine.CardRegexScript =
        CardRegexEngine.CardRegexScript(
            name = name,
            findRegex = findRegex,
            replaceString = replaceString,
            disabled = false,
            placement = listOf(2),
        )
}
