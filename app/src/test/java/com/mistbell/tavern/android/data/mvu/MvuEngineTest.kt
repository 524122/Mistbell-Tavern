package com.mistbell.tavern.android.data.mvu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MvuEngine 的 JVM 单测：三 op、嵌套建层、delta、剥离、坏块、路径防护。 */
class MvuEngineTest {
    private val empty = JsonObject(emptyMap())

    private fun updateBlock(patch: String) =
        "<UpdateVariable>\n<Analysis>x</Analysis>\n<JSONPatch>\n$patch\n</JSONPatch>\n</UpdateVariable>"

    @Test
    fun `无块时文本与数据零改动`() {
        val result = MvuEngine.process("没有块的回复", empty)
        assertEquals("没有块的回复", result.cleanedText)
        assertEquals(0, result.opsApplied)
        assertTrue(result.warnings.isEmpty())
        assertFalse(MvuEngine.hasUpdateBlock("普通文本"))
    }

    @Test
    fun `replace 自动建嵌套层并剥离块`() {
        val text = "前文" + updateBlock("""[{"op":"replace","path":"/主角状态/修为/当前境界","value":"筑基六层"}]""") + "后文"
        val result = MvuEngine.process(text, empty)
        assertEquals("前文后文", result.cleanedText)
        assertEquals(1, result.opsApplied)
        assertEquals(
            "筑基六层",
            result.statData.jsonObject["主角状态"]!!.jsonObject["修为"]!!.jsonObject["当前境界"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `delta 数值加减与 remove 删键`() {
        val base =
            JsonObject(
                mapOf(
                    "主角状态" to JsonObject(mapOf("修为" to JsonObject(mapOf("进度百分比" to JsonPrimitive(90.0))))),
                ),
            )
        val patch =
            """[{"op":"delta","path":"/主角状态/修为/进度百分比","value":15},""" +
                """{"op":"remove","path":"/主角状态/修为/进度百分比"}]"""
        val deltaPatch = """[{"op":"delta","path":"/主角状态/修为/进度百分比","value":15}]"""
        val afterDelta = MvuEngine.process(updateBlock(deltaPatch), base)
        val cultivation = afterDelta.statData.jsonObject["主角状态"]!!.jsonObject["修为"]!!.jsonObject
        assertEquals(105.0, cultivation["进度百分比"]!!.jsonPrimitive.double, 0.001)
        val afterRemove = MvuEngine.process(updateBlock(patch), base)
        assertFalse(
            afterRemove.statData.jsonObject["主角状态"]!!.jsonObject["修为"]!!.jsonObject.containsKey("进度百分比"),
        )
    }

    @Test
    fun `多块顺序应用`() {
        val replaceBlock = updateBlock("""[{"op":"replace","path":"/a","value":1}]""")
        val deltaBlock = updateBlock("""[{"op":"delta","path":"/a","value":2}]""")
        val result = MvuEngine.process(replaceBlock + deltaBlock, empty)
        assertEquals(2, result.opsApplied)
        assertEquals(3.0, result.statData["a"]!!.jsonPrimitive.double, 0.001)
    }

    @Test
    fun `坏 JSON 块跳过且不影响好块`() {
        val text = updateBlock("这不是 JSON") + updateBlock("""[{"op":"replace","path":"/ok","value":"好"}]""")
        val result = MvuEngine.process(text, empty)
        assertEquals(1, result.opsApplied)
        assertTrue(result.warnings.isNotEmpty())
        assertEquals("好", result.statData["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun `路径注入防护`() {
        val patch =
            """[{"op":"replace","path":"/../etc/passwd","value":"x"},""" +
                """{"op":"replace","path":"/a/__proto__/b","value":"x"},""" +
                """{"op":"replace","path":"无斜杠","value":"x"}]"""
        val result = MvuEngine.process(updateBlock(patch), empty)
        assertEquals(0, result.opsApplied)
        assertTrue(result.statData.isEmpty())
    }

    @Test
    fun `缺 JSONPatch 段的残缺块告警且不剥离失败`() {
        val text = updateBlock("")
        val result = MvuEngine.process(text, empty)
        assertEquals(0, result.opsApplied)
        assertTrue(result.warnings.isNotEmpty())
        // 块仍被剥离（语义上它是 UpdateVariable 块，内容残缺而已）
        assertEquals("", result.cleanedText)
    }

    @Test
    fun `delta 对非数值跳过`() {
        val base = JsonObject(mapOf("s" to JsonPrimitive("文本")))
        val result = MvuEngine.process(updateBlock("""[{"op":"delta","path":"/s","value":5}]"""), base)
        assertEquals(0, result.opsApplied)
        assertEquals("文本", result.statData["s"]!!.jsonPrimitive.content)
    }
}
