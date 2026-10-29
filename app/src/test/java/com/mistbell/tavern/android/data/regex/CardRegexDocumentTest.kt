package com.mistbell.tavern.android.data.regex

import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun baseExtensions() =
    buildJsonObject {
        put("world", JsonPrimitive("keep"))
        put("tavern_helper", buildJsonObject { put("keep", JsonPrimitive(true)) })
        put(
            "regex_scripts",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("scriptName", JsonPrimitive("旧规则"))
                        put("findRegex", JsonPrimitive("hello"))
                        put("replaceString", JsonPrimitive("**$1**"))
                        put("disabled", JsonPrimitive(false))
                        put(
                            "placement",
                            buildJsonArray {
                                add(JsonPrimitive(1))
                                add(JsonPrimitive(2))
                            },
                        )
                        put("trimStrings", buildJsonArray { add(JsonPrimitive("x")) })
                        put("markdownOnly", JsonPrimitive(true))
                        put("promptOnly", JsonPrimitive(false))
                        put("runOnEdit", JsonPrimitive(true))
                        put("minDepth", JsonPrimitive(0))
                        put("maxDepth", JsonPrimitive(4))
                        put("futureOption", JsonPrimitive("keep"))
                    },
                )
                add(JsonPrimitive("raw"))
            },
        )
    }

class CardRegexDocumentTest {
    @Test
    fun `编辑保存保留扩展字段和原始条目`() {
        val original = baseExtensions()
        val document = CardRegexDocument.fromExtensions(original)
        val rule = document.rules.single()
        assertEquals("旧规则", rule.name)
        assertEquals(listOf(1, 2), rule.placement)
        assertEquals("x", rule.trimStringsText)
        assertEquals("0", rule.minDepthText)
        assertEquals("4", rule.maxDepthText)
        assertTrue(rule.runOnEdit)
        assertTrue(rule.markdownOnly)
        assertFalse(rule.promptOnly)

        val edited =
            rule.copy(
                name = "新规则",
                findRegex = "new",
                replaceString = "changed",
                userInput = false,
                aiOutput = true,
                markdownOnly = false,
                promptOnly = true,
                runOnEdit = false,
                minDepthText = "2",
                maxDepthText = "",
            )
        val added = RegexRuleDraft(id = 99, name = "", findRegex = "x", replaceString = "y")
        val updated = document.copy(rules = listOf(edited, added)).updatedExtensions(original)
        assertEquals("keep", updated["world"]?.jsonPrimitive?.content)
        assertEquals(buildJsonObject { put("keep", JsonPrimitive(true)) }, updated["tavern_helper"])
        val array = updated["regex_scripts"] as JsonArray
        assertEquals(3, array.size)
        assertEquals("raw", array[1].jsonPrimitive.content)
        val saved = array[0].jsonObject
        assertEquals("新规则", saved["scriptName"]?.jsonPrimitive?.content)
        assertEquals("new", saved["findRegex"]?.jsonPrimitive?.content)
        assertEquals("changed", saved["replaceString"]?.jsonPrimitive?.content)
        assertEquals("keep", saved["futureOption"]?.jsonPrimitive?.content)
        assertFalse("maxDepth" in saved)
        assertEquals("未命名", array[2].jsonObject["scriptName"]?.jsonPrimitive?.content)

        val parsed = CardRegexEngine.parse(updated)
        assertEquals(2, parsed.size)
        assertEquals(listOf(2), parsed[0].placement)
        assertEquals(2, parsed[0].minDepth)
        assertNull(parsed[0].maxDepth)
        assertTrue(parsed[0].promptOnly)
        assertFalse(parsed[0].markdownOnly)
        assertFalse(parsed[0].runOnEdit)
        assertEquals(original, baseExtensions())

        val entity =
            CharacterEntity.fromDomain(
                Character(
                    id = "temporary",
                    name = "测试",
                    data = CharacterData(systemPrompt = "keep", extensions = updated),
                ),
            )
        assertEquals(updated, entity.toDomain().data?.extensions)
    }

    @Test
    fun `缺失findRegex的对象仍可编辑且非对象项保留`() {
        val extensions =
            buildJsonObject {
                put(
                    "regex_scripts",
                    buildJsonArray {
                        add(buildJsonObject { put("scriptName", JsonPrimitive("待修复")) })
                        add(JsonPrimitive("keep-raw"))
                    },
                )
            }
        val document = CardRegexDocument.fromExtensions(extensions)
        assertEquals("", document.rules.single().findRegex)
        assertNotNull(document.rules.single().validationError())
        val updated =
            document.copy(rules = document.rules.map { it.copy(findRegex = "fixed") })
                .updatedExtensions(extensions)["regex_scripts"] as JsonArray
        assertEquals("fixed", updated[0].jsonObject["findRegex"]?.jsonPrimitive?.content)
        assertEquals("keep-raw", updated[1].jsonPrimitive.content)
    }

    @Test
    fun `删除全部规则保留空数组且顺序影响预览`() {
        val first = RegexRuleDraft(id = 0, name = "first", findRegex = "a", replaceString = "b")
        val second = RegexRuleDraft(id = 1, name = "second", findRegex = "b", replaceString = "c")
        val document = CardRegexDocument(rules = listOf(first, second))
        assertEquals("c", evaluateRegexPreview(document, RegexPreviewRequest(input = "a")).text)
        val reversed = document.copy(rules = listOf(second, first))
        assertEquals("b", evaluateRegexPreview(reversed, RegexPreviewRequest(input = "a")).text)

        val empty = document.copy(rules = emptyList()).updatedExtensions(null)
        assertTrue((empty["regex_scripts"] as JsonArray).isEmpty())
        assertTrue(CardRegexEngine.parse(empty).isEmpty())
    }

    @Test
    fun `无效规则可定位且停用规则不阻止保存`() {
        val disabled =
            RegexRuleDraft(
                id = 0,
                name = "停用",
                findRegex = "[",
                disabled = true,
                userInput = false,
                aiOutput = false,
            )
        assertNull(disabled.validationError())
        assertNotNull(disabled.copy(disabled = false).validationError())
        assertNotNull(disabled.copy(findRegex = "").validationError())
        assertNotNull(disabled.copy(minDepthText = "x").validationError())
        assertNotNull(disabled.copy(maxDepthText = "x").validationError())
        assertNotNull(disabled.copy(minDepthText = "4", maxDepthText = "2").validationError())

        val document = CardRegexDocument(rules = listOf(disabled.copy(id = 0, disabled = false), disabled.copy(id = 1)))
        assertEquals(1, evaluateRegexPreview(document, RegexPreviewRequest(input = "a")).skippedInvalidRules)
        assertEquals(1, document.validationErrors().size)
    }

    @Test
    fun `新增和复制插入到当前选中规则之后`() {
        val first = RegexRuleDraft(id = 0, name = "first", findRegex = "a")
        val second = RegexRuleDraft(id = 1, name = "second", findRegex = "b")
        val document = CardRegexDocument(rules = listOf(first, second))

        val inserted = RegexRuleDraft(id = 2, name = "inserted", findRegex = "c")
        assertEquals(listOf(0, 2, 1), document.insert(inserted, first.id).rules.map { it.id })
        assertEquals(listOf(0, 1, 2), document.insert(inserted, null).rules.map { it.id })
        assertEquals(listOf(0, 1, 2), document.insert(inserted, 99).rules.map { it.id })
    }

    @Test
    fun `展示发送和提示词预览走真实引擎`() {
        val rule =
            RegexRuleDraft(
                id = 0,
                name = "测试",
                findRegex = "hello",
                replaceString = "**world**",
                userInput = true,
                aiOutput = true,
                markdownOnly = false,
                promptOnly = false,
                runOnEdit = false,
                maxDepthText = "1",
            )
        val document = CardRegexDocument(rules = listOf(rule))
        assertEquals("**world**", evaluateRegexPreview(document, RegexPreviewRequest(input = "hello")).text)
        assertEquals(
            "**world**",
            evaluateRegexPreview(document, RegexPreviewRequest(input = "hello", role = "user")).text,
        )
        assertEquals(
            "**world**",
            evaluateRegexPreview(
                document,
                RegexPreviewRequest(input = "hello", mode = RegexPreviewMode.USER_INPUT),
            ).text,
        )
        assertEquals(
            "**world**",
            evaluateRegexPreview(
                document,
                RegexPreviewRequest(input = "hello", mode = RegexPreviewMode.PROMPT, role = "user"),
            ).text,
        )
        assertEquals(
            "hello",
            evaluateRegexPreview(
                document,
                RegexPreviewRequest(input = "hello", mode = RegexPreviewMode.PROMPT, depthText = "2"),
            ).text,
        )
        assertEquals(
            "hello",
            evaluateRegexPreview(
                document,
                RegexPreviewRequest(input = "hello", mode = RegexPreviewMode.USER_INPUT, isEdit = true),
            ).text,
        )
        assertEquals(
            "预览深度须为整数",
            evaluateRegexPreview(document, RegexPreviewRequest(input = "hello", depthText = "x")).text,
        )
        assertEquals(
            "预览深度不能小于 0",
            evaluateRegexPreview(document, RegexPreviewRequest(input = "hello", depthText = "-1")).text,
        )
    }
}
