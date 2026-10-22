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
        val result = CardRegexEngine.applyToAiDisplay("HELLO hello", listOf(script("/hello/i", "hi")))
        assertEquals("hi hi", result)
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
    fun `parse 从 extensions 提取`() {
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
                            },
                        )
                    },
                )
            }
        val scripts = CardRegexEngine.parse(extensions)
        assertEquals(1, scripts.size)
        assertEquals("开场白", scripts.first().name)
        assertTrue(CardRegexEngine.parse(null).isEmpty())
        assertTrue(CardRegexEngine.parse(JsonObject(emptyMap())).isEmpty())
    }
}
