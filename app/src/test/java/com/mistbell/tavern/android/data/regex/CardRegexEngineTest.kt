package com.mistbell.tavern.android.data.regex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CardRegexEngine 的 JVM 单测：解析、placement 过滤、/flags、组引用、disabled。 */
class CardRegexEngineTest {
    private fun script(
        find: String,
        replace: String,
        disabled: Boolean = false,
        placement: List<Int> = listOf(2),
    ) = CardRegexEngine.CardRegexScript("t", find, replace, disabled, placement)

    @Test
    fun `裸模式与组引用替换`() {
        assertEquals("你是 笨蛋", CardRegexEngine.applyToAiDisplay("你是 笨蛋", listOf(script("你是 (.+)", "你是 $1"))))
        // 组引用进包装：$1 捕获组内容
        assertEquals("你是 [笨蛋]", CardRegexEngine.applyToAiDisplay("你是 笨蛋", listOf(script("你是 (.+)", "你是 [$1]"))))
        // 越界组引用（无分组的 $1）：展开为空串，不炸——"**$1**" → "****"
        assertEquals("你是****", CardRegexEngine.applyToAiDisplay("你是笨蛋", listOf(script("笨蛋", "**$1**"))))
    }

    @Test
    fun `替换串里的 jQuery 美元符号不误炸`() {
        val replacement = "```html\n<div>\$('#btn').on('click', \$(this));</div>\n```"
        val result = CardRegexEngine.applyToAiDisplay("【GameStart】", listOf(script("【GameStart】", replacement)))
        assertEquals(replacement, result)
    }

    @Test
    fun `斜杠包裹与大小写 flag`() {
        assertEquals("hi hello", CardRegexEngine.applyToAiDisplay("HELLO hello", listOf(script("/hello/i", "hi"))))
        assertEquals("hi hi", CardRegexEngine.applyToAiDisplay("HELLO hello", listOf(script("/hello/gi", "hi"))))
    }

    @Test
    fun `disabled 与 placement 不含 2 的脚本不生效`() {
        val scripts =
            listOf(
                script("a", "b", disabled = true),
                script("a", "b", placement = listOf(1)),
            )
        assertEquals("a", CardRegexEngine.applyToAiDisplay("a", scripts))
    }

    @Test
    fun `按数组顺序链式应用`() {
        val scripts = listOf(script("a", "b"), script("b", "c"))
        assertEquals("c", CardRegexEngine.applyToAiDisplay("a", scripts))
    }

    @Test
    fun `坏正则单条跳过不影响其他`() {
        val scripts = listOf(script("[未闭合", "x"), script("a", "b"))
        assertEquals("b", CardRegexEngine.applyToAiDisplay("a", scripts))
    }

    @Test
    fun `用户输入按普通显示和提示词规则分流`() {
        val scripts =
            listOf(
                script("input", "stored", placement = listOf(1)),
                script("display", "<b>display</b>", placement = listOf(1)).copy(markdownOnly = true),
                script("prompt", "hidden", placement = listOf(1)).copy(promptOnly = true),
            )
        assertEquals("stored", CardRegexEngine.applyToUserInput("input", scripts))
        assertEquals("<b>display</b>", CardRegexEngine.applyToDisplay("display", "user", scripts, depth = 0))
        assertEquals("hidden", CardRegexEngine.applyToPrompt("prompt", "user", scripts, depth = 0))
    }

    @Test
    fun `markdownOnly 与 promptOnly 不串场景`() {
        val scripts =
            listOf(
                script("x", "common"),
                script("common", "display").copy(markdownOnly = true),
                script("common", "prompt").copy(promptOnly = true),
            )
        assertEquals("display", CardRegexEngine.applyToAiDisplay("x", scripts, depth = 0))
        assertEquals("prompt", CardRegexEngine.applyToPrompt("x", "assistant", scripts, depth = 0))
    }

    @Test
    fun `深度范围按当前零最近历史一计算`() {
        val oldOnly = script("x", "old").copy(minDepth = 2)
        val recentOnly = script("x", "recent").copy(maxDepth = 1)
        assertEquals("x", CardRegexEngine.applyToPrompt("x", "assistant", listOf(oldOnly), depth = 1))
        assertEquals("old", CardRegexEngine.applyToPrompt("x", "assistant", listOf(oldOnly), depth = 2))
        assertEquals("recent", CardRegexEngine.applyToAiDisplay("x", listOf(recentOnly), depth = 1))
        assertEquals("x", CardRegexEngine.applyToAiDisplay("x", listOf(recentOnly), depth = 2))
    }

    @Test
    fun `编辑时只运行 runOnEdit 规则`() {
        val scripts =
            listOf(
                script("a", "b", placement = listOf(1)),
                script("a", "c", placement = listOf(1)).copy(runOnEdit = true),
            )
        assertEquals("b", CardRegexEngine.applyToUserInput("a", scripts))
        assertEquals("c", CardRegexEngine.applyToUserInput("a", scripts, isEdit = true))
    }

    @Test
    fun `match 宏与捕获组内容裁剪`() {
        val rule = script("(secret)-(\\d+)", "$1:{{match}}").copy(trimStrings = listOf("secret"))
        assertEquals(":-42", CardRegexEngine.applyToAiDisplay("secret-42", listOf(rule)))
    }

    @Test
    fun `parse 从 extensions 提取完整执行字段`() {
        val extensions =
            buildJsonObject {
                put(
                    "regex_scripts",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("scriptName", JsonPrimitive("开场白"))
                                put("findRegex", JsonPrimitive("【GameStart】"))
                                put("replaceString", JsonPrimitive("<html>x</html>"))
                                put("disabled", JsonPrimitive(false))
                                put("placement", buildJsonArray { add(JsonPrimitive(2)) })
                                put("trimStrings", buildJsonArray { add(JsonPrimitive("trim")) })
                                put("markdownOnly", JsonPrimitive(true))
                                put("promptOnly", JsonPrimitive(false))
                                put("runOnEdit", JsonPrimitive(true))
                                put("minDepth", JsonPrimitive(2))
                                put("maxDepth", JsonPrimitive(7))
                            },
                        )
                    },
                )
            }
        val script = CardRegexEngine.parse(extensions).single()
        assertEquals("开场白", script.name)
        assertEquals(listOf("trim"), script.trimStrings)
        assertTrue(script.markdownOnly)
        assertTrue(script.runOnEdit)
        assertEquals(2, script.minDepth)
        assertEquals(7, script.maxDepth)
        assertTrue(CardRegexEngine.parse(null).isEmpty())
        assertTrue(CardRegexEngine.parse(JsonObject(emptyMap())).isEmpty())
    }
}
