package com.mistbell.tavern.android.ui.chat.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MVU 变量扁平化桥单测（THEME_FRAMEWORK_DESIGN.md §6.2 数据源过渡桥） */
class VariablesBridgeTest {
    @Test
    fun `nested objects flatten to dot paths`() {
        val json =
            """
            {"主角状态": {"修为": {"当前境界": "筑基六层", "进度": 55}, "HP": 80}}
            """.trimIndent()
        val vars = flattenMvuVariables(json)
        assertEquals("筑基六层", vars["主角状态.修为.当前境界"])
        assertEquals("55", vars["主角状态.修为.进度"])
        assertEquals("80", vars["主角状态.HP"])
    }

    @Test
    fun `numbers and booleans convert to strings`() {
        val vars =
            flattenMvuVariables(
                """{"胜利": true, "失败": false, "计数": 0, "小数": 1.5}""",
            )
        assertEquals("true", vars["胜利"])
        assertEquals("false", vars["失败"])
        assertEquals("0", vars["计数"])
        assertEquals("1.5", vars["小数"])
    }

    @Test
    fun `arrays flatten with index paths`() {
        val vars =
            flattenMvuVariables(
                """{"队伍": [{"名字": "阿璃"}, {"名字": "阿珩"}]}""",
            )
        assertEquals("阿璃", vars["队伍.0.名字"])
        assertEquals("阿珩", vars["队伍.1.名字"])
    }

    @Test
    fun `null values are skipped`() {
        val vars = flattenMvuVariables("""{"留下": "值", "丢弃": null}""")
        assertEquals("值", vars["留下"])
        assertTrue("null 值位不应注入（同 token 桥语义）", vars.containsKey("丢弃").not())
    }

    @Test
    fun `string values keep raw content`() {
        val vars = flattenMvuVariables("""{"备注": "含 空格 与 # 号"}""")
        assertEquals("含 空格 与 # 号", vars["备注"])
    }

    @Test
    fun `invalid json yields empty map`() {
        assertEquals(emptyMap<String, String>(), flattenMvuVariables("{坏 json"))
        assertEquals(emptyMap<String, String>(), flattenMvuVariables(""))
        assertEquals(emptyMap<String, String>(), flattenMvuVariables(null))
    }

    @Test
    fun `non object root yields empty map`() {
        assertEquals(emptyMap<String, String>(), flattenMvuVariables("[1, 2, 3]"))
        assertEquals(emptyMap<String, String>(), flattenMvuVariables("\"纯字符串\""))
    }

    @Test
    fun `snapshot carries version for cache invalidation`() {
        val snapshot = VariablesSnapshot(version = 7L, variables = flattenMvuVariables("""{"a": "1"}"""))
        assertEquals(7L, snapshot.version)
        assertEquals("1", snapshot.variables["a"])
        assertEquals(0L, VariablesSnapshot.EMPTY.version)
    }
}
