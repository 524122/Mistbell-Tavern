package com.mistbell.tavern.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorldBookParser 纯函数单元测试（F2 独立世界书导入，F2 生态互通）。
 * 覆盖：uid-map 与数组两种 entries 形态、key 单串规整、enabled→disable 反相、
 * insertion_order 优先、缺失字段默认值、书名 fallback，
 * 以及酒馆实测语义（数字 position 枚举、atDepth、0-100 概率）。
 */
class WorldBookParserTest {
    @Test
    fun `uid-map主流形态解析`() {
        val json =
            """
            {
              "name": "学院世界书",
              "entries": {
                "1": { "comment": "条例", "content": "夜间禁足",
                       "keys": ["夜间", "禁足"], "constant": false,
                       "enabled": true, "insertion_order": 2 },
                "2": { "content": "院长室", "keys": ["院长"], "disable": true, "order": 8 }
              }
            }
            """.trimIndent()
        val (book, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        assertEquals("学院世界书", book.name)
        assertEquals(2, entries.size)
        val byId = entries.associateBy { it.id }
        assertEquals("1", byId["1"]?.id) // id 用 uid
        assertEquals("2", byId["2"]?.id)
        // enabled=true → disable=false；显式 disable=true 保持
        assertEquals(false, byId["1"]!!.disable)
        assertEquals(true, byId["2"]!!.disable)
        // insertion_order 优先于 order
        assertEquals(2, byId["1"]!!.order)
        assertEquals(8, byId["2"]!!.order)
        assertTrue(entries.all { it.bookId == book.id })
    }

    @Test
    fun `entries数组形态解析`() {
        val json =
            """
            {
              "entries": [
                { "uid": 5, "comment": "甲", "content": "内容甲", "keys": ["a"] },
                { "uid": 3, "comment": "乙", "content": "内容乙", "keys": ["b"] }
              ]
            }
            """.trimIndent()
        val (book, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        assertEquals(2, entries.size)
        assertEquals(setOf("5", "3"), entries.map { it.id }.toSet())
    }

    @Test
    fun `key单字符串规整成单元素数组`() {
        val json =
            """
            { "entries": { "1": { "content": "c", "key": "单键" } } }
            """.trimIndent()
        val (_, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        val keys =
            kotlinx.serialization.json.Json
                .decodeFromString<List<String>>(entries.single().keysJson)
        assertEquals(listOf("单键"), keys)
    }

    @Test
    fun `key数组保持多元素`() {
        val json =
            """
            { "entries": { "1": { "content": "c", "key": ["k1", "k2", "k3"] } } }
            """.trimIndent()
        val (_, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        val keys =
            kotlinx.serialization.json.Json
                .decodeFromString<List<String>>(entries.single().keysJson)
        assertEquals(listOf("k1", "k2", "k3"), keys)
    }

    @Test
    fun `缺失字段取默认值`() {
        val json =
            """
            { "entries": { "1": { "content": "只有内容" } } }
            """.trimIndent()
        val (_, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        val entry = entries.single()
        assertEquals("", entry.comment)
        assertEquals("[]", entry.keysJson)
        assertEquals("只有内容", entry.content)
        assertEquals(false, entry.constant) // 默认非常驻
        assertEquals(false, entry.disable) // 默认启用
        assertEquals(0, entry.order) // 默认顺序
    }

    @Test
    fun `无uid时生成非空id`() {
        val json =
            """
            { "entries": [ { "content": "甲" }, { "content": "乙" } ] }
            """.trimIndent()
        val (_, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.id.isNotBlank() })
        // 两个缺 uid 的条目 id 不得撞车
        assertTrue(entries[0].id != entries[1].id)
    }

    @Test
    fun `书名取name字段否则用fallback`() {
        val withName =
            WorldBookParser.parse(
                """{ "name": "有名字", "entries": {} }""",
                "fallback",
            )
        assertEquals("有名字", withName?.first?.name)

        val withoutName =
            WorldBookParser.parse(
                """{ "entries": { "1": { "content": "c" } } }""",
                "备用书名",
            )
        assertNotNull(withoutName)
        assertEquals("备用书名", withoutName!!.first.name)
    }

    @Test
    fun `非法JSON返回null`() {
        assertNull(WorldBookParser.parse("{ 不是 json", "f"))
    }

    @Test
    fun `酒馆独立世界书位置概率映射`() {
        // 按实测酒馆独立世界书结构（Eldoria.json / 美奈.json）构造：
        // Eldoria 锚点条目带 UI 残留 depth=4；美奈 atDepth 条目 position=4、depth=0
        val json =
            """
            {
              "entries": {
                "0": { "uid": 0, "key": ["eldoria"], "keysecondary": [],
                       "content": "Eldoria", "constant": false, "selective": true,
                       "order": 100, "position": 0, "disable": false,
                       "probability": 100, "useProbability": true, "depth": 4, "role": null },
                "1": { "uid": 1, "key": ["美奈"], "content": "美奈设定",
                       "constant": true, "order": 100,
                       "position": 4, "depth": 0, "role": 0,
                       "probability": 100, "useProbability": true },
                "2": { "uid": 2, "key": ["游侠"], "content": "作者注释条目",
                       "constant": false, "order": 50,
                       "position": 2, "probability": 50 }
              }
            }
            """.trimIndent()
        val (book, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        assertNotNull(book)
        val byId = entries.associateBy { it.id }
        // 锚点条目：position=0 → 角色定义前，残留 depth=4 不产生 @D，概率 100 → 必触发
        assertEquals("before_prompt", byId["0"]?.insertPosition)
        assertEquals(0, byId["0"]?.depth)
        assertEquals(1.0, byId["0"]?.probability ?: -1.0, 0.0001)
        // atDepth 条目：depth=0 钳为 1
        assertEquals(1, byId["1"]?.depth)
        assertEquals("system", byId["1"]?.depthRole)
        // position=2 → 作者注释顶，概率 50 → 0.5
        assertEquals("an_before", byId["2"]?.insertPosition)
        assertEquals(0.5, byId["2"]?.probability ?: -1.0, 0.0001)
    }
}
