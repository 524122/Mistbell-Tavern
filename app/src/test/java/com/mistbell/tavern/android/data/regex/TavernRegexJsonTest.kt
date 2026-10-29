package com.mistbell.tavern.android.data.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** getTavernRegexes 契约序列化：ST 字段形态、id 来源前缀、全局先于卡级的数组序 */
class TavernRegexJsonTest {
    @Test
    fun `字段名与 ST regex 扩展形态对齐`() {
        val json = TavernRegexJson.toTavernJson(global = listOf(globalScript("剥壳")), card = emptyList())
        val obj = Json.parseToJsonElement(json).jsonArray.single().jsonObject
        assertEquals("global-0", obj.getValue("id").jsonPrimitive.content)
        assertEquals("剥壳", obj.getValue("scriptName").jsonPrimitive.content)
        assertEquals("<think>[\\s\\S]*?</think>", obj.getValue("findRegex").jsonPrimitive.content)
        assertEquals("", obj.getValue("replaceString").jsonPrimitive.content)
        assertTrue(obj.containsKey("trimStrings"))
        assertTrue(obj.containsKey("placement"))
        assertTrue(obj.containsKey("disabled"))
        assertTrue(obj.containsKey("markdownOnly"))
        assertTrue(obj.containsKey("promptOnly"))
        assertTrue(obj.containsKey("runOnEdit"))
        assertTrue(obj.containsKey("minDepth"))
        assertTrue(obj.containsKey("maxDepth"))
    }

    @Test
    fun `全局先于卡级且 id 带来源前缀`() {
        val json =
            TavernRegexJson.toTavernJson(
                global = listOf(globalScript("g1"), globalScript("g2")),
                card = listOf(cardScript("c1")),
            )
        val array = Json.parseToJsonElement(json).jsonArray
        assertEquals(3, array.size)
        assertEquals("global-0", array[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("global-1", array[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("card-0", array[2].jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `placement 与深度字段原样透传`() {
        val json =
            TavernRegexJson.toTavernJson(
                global = emptyList(),
                card =
                    listOf(
                        CardRegexEngine.CardRegexScript(
                            name = "深度规则",
                            findRegex = "x",
                            replaceString = "y",
                            disabled = false,
                            placement = listOf(1, 2),
                            minDepth = -1,
                            maxDepth = 4,
                        ),
                    ),
            )
        val obj = Json.parseToJsonElement(json).jsonArray.single().jsonObject
        assertEquals("[1,2]", obj.getValue("placement").toString())
        assertEquals("-1", obj.getValue("minDepth").jsonPrimitive.content)
        assertEquals("4", obj.getValue("maxDepth").jsonPrimitive.content)
    }

    @Test
    fun `空管线返回空数组`() {
        assertEquals("[]", TavernRegexJson.toTavernJson(emptyList(), emptyList()))
    }

    private fun globalScript(name: String): CardRegexEngine.CardRegexScript =
        CardRegexEngine.CardRegexScript(
            name = name,
            findRegex = "<think>[\\s\\S]*?</think>",
            replaceString = "",
            disabled = false,
            placement = listOf(2),
        )

    private fun cardScript(name: String): CardRegexEngine.CardRegexScript =
        CardRegexEngine.CardRegexScript(
            name = name,
            findRegex = "x",
            replaceString = "",
            disabled = false,
            placement = listOf(2),
        )
}
