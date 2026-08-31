package com.mistbell.tavern.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorldBookParser 纯函数单元测试（F2 独立世界书导入，F2 生态互通）。
 * 覆盖：uid-map 与数组两种 entries 形态、key 单串规整、enabled→disable 反相、
 * insertion_order 优先、缺失字段默认值、书名 fallback。
 */
class WorldBookParserTest {

    @Test
    fun `uid-map主流形态解析`() {
        val json = """
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
        val json = """
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
        val json = """
            { "entries": { "1": { "content": "c", "key": "单键" } } }
        """.trimIndent()
        val (_, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        val keys = kotlinx.serialization.json.Json
            .decodeFromString<List<String>>(entries.single().keysJson)
        assertEquals(listOf("单键"), keys)
    }

    @Test
    fun `key数组保持多元素`() {
        val json = """
            { "entries": { "1": { "content": "c", "key": ["k1", "k2", "k3"] } } }
        """.trimIndent()
        val (_, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        val keys = kotlinx.serialization.json.Json
            .decodeFromString<List<String>>(entries.single().keysJson)
        assertEquals(listOf("k1", "k2", "k3"), keys)
    }

    @Test
    fun `缺失字段取默认值`() {
        val json = """
            { "entries": { "1": { "content": "只有内容" } } }
        """.trimIndent()
        val (_, entries) = WorldBookParser.parse(json, "fallback") ?: return assertTrue(false)
        val entry = entries.single()
        assertEquals("", entry.comment)
        assertEquals("[]", entry.keysJson)
        assertEquals("只有内容", entry.content)
        assertEquals(false, entry.constant) // 默认非常驻
        assertEquals(false, entry.disable)  // 默认启用
        assertEquals(0, entry.order)        // 默认顺序
    }

    @Test
    fun `无uid时生成非空id`() {
        val json = """
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
        val withName = WorldBookParser.parse(
            """{ "name": "有名字", "entries": {} }""", "fallback"
        )
        assertEquals("有名字", withName?.first?.name)

        val withoutName = WorldBookParser.parse(
            """{ "entries": { "1": { "content": "c" } } }""", "备用书名"
        )
        assertNotNull(withoutName)
        assertEquals("备用书名", withoutName!!.first.name)
    }

    @Test
    fun `非法JSON返回null`() {
        assertNull(WorldBookParser.parse("{ 不是 json", "f"))
    }
}
