package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.api.model.CharacterData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CardParser 纯函数单元测试（F2 生态互通）。
 * 覆盖：v2 嵌套、v1 老键名兜底、alternate_greetings/tags/extensions 提取、
 * enabled→disable 反相、insertion_order 优先、entries 数组与 uid-map 两形态。
 */
class CardParserTest {

    /** 从导入结果还原 CharacterData（与运行时 toDomain 同一解码路径） */
    private fun dataOf(result: CharacterImportResult): CharacterData =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            CharacterData.serializer(), result.character.dataJson
        )

    @Test
    fun `v2嵌套data字段解析`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "爱丽丝",
                "description": "一位向导",
                "personality": "冷静",
                "scenario": "魔法学院",
                "first_mes": "你好",
                "mes_example": "<START>",
                "system_prompt": "你是向导",
                "post_history_instructions": "保持简短",
                "creator_notes": "测试卡",
                "creator": "tester",
                "character_version": "2.1"
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("爱丽丝", result.character.name)
        assertEquals("一位向导", result.character.description)
        assertEquals("冷静", result.character.personality)
        assertEquals("魔法学院", result.character.scenario)
        assertEquals("你好", result.character.firstMes)
        assertEquals("<START>", result.character.mesExample)
        val data = dataOf(result)
        assertEquals("你是向导", data.systemPrompt)
        assertEquals("保持简短", data.postHistoryInstructions)
        assertEquals("测试卡", data.creatorNotes)
        assertEquals("tester", data.creator)
        assertEquals("2.1", data.characterVersion)
    }

    @Test
    fun `v2根对象兜底`() {
        // data 位为空时回退读根对象（部分工具只写根级）
        val json = """
            { "name": "根名", "description": "根描述", "first_mes": "根问候" }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("根名", result.character.name)
        assertEquals("根描述", result.character.description)
        assertEquals("根问候", result.character.firstMes)
    }

    @Test
    fun `v1老键名兜底映射`() {
        val json = """
            {
              "char_name": "老卡",
              "char_persona": "老人设",
              "world_scenario": "老场景",
              "char_greeting": "老问候",
              "example_dialogue": "老示例"
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("老卡", result.character.name)
        assertEquals("老人设", result.character.description)
        assertEquals("老场景", result.character.scenario)
        assertEquals("老问候", result.character.firstMes)
        assertEquals("老示例", result.character.mesExample)
    }

    @Test
    fun `alternate_greetings与tags提取进CharacterData`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "A",
                "alternate_greetings": ["问候一", "问候二"],
                "tags": ["奇幻", "向导"]
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val data = dataOf(result)
        assertEquals(listOf("问候一", "问候二"), data.alternateGreetings)
        assertEquals(listOf("奇幻", "向导"), data.tags)
    }

    @Test
    fun `extensions原样透传`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "A",
                "extensions": {
                  "depth_prompt": { "prompt": "深层", "depth": 4 },
                  "talkativeness": "0.5"
                }
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val ext = dataOf(result).extensions
        assertNotNull(ext)
        assertEquals("深层", ext!!["depth_prompt"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals("0.5", ext["talkativeness"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enabled布尔正确反相为disable`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "A",
                "character_book": {
                  "name": "书",
                  "entries": [
                    { "uid": 1, "content": "开", "enabled": true, "keys": ["k1"] },
                    { "uid": 2, "content": "关", "enabled": false, "keys": ["k2"] }
                  ]
                }
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val entries = result.worldBookEntries.sortedBy { it.id }
        assertEquals(2, entries.size)
        assertEquals(false, entries[0].disable) // enabled=true → disable=false
        assertEquals(true, entries[1].disable)  // enabled=false → disable=true
    }

    @Test
    fun `insertion_order优先于order`() {
        val json = """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": [
                    { "uid": 1, "content": "c", "order": 9, "insertion_order": 3 },
                    { "uid": 2, "content": "c", "order": 5 }
                  ]
                }
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val byId = result.worldBookEntries.associateBy { it.id }
        assertEquals(3, byId["1"]!!.order) // insertion_order 覆盖 order
        assertEquals(5, byId["2"]!!.order) // 缺 insertion_order 时回退 order
    }

    @Test
    fun `entries数组形态解析`() {
        val json = """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "name": "书",
                  "entries": [
                    { "uid": 7, "content": "条目内容", "comment": "备注",
                      "constant": true, "disable": true, "keys": ["a", "b"],
                      "keysecondary": ["s"] }
                  ]
                }
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertNotNull(result.worldBook)
        assertEquals("书", result.worldBook?.name)
        val entry = result.worldBookEntries.single()
        assertEquals("7", entry.id)
        assertEquals("备注", entry.comment)
        assertEquals("条目内容", entry.content)
        assertEquals(true, entry.constant)
        assertEquals(true, entry.disable)
        assertEquals(listOf("a", "b"), Json.decodeFromString<List<String>>(entry.keysJson))
    }

    @Test
    fun `entries按uid的map形态解析`() {
        val json = """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": {
                    "10": { "content": "甲", "enabled": true, "keys": ["x"] },
                    "20": { "content": "乙", "disable": false, "keys": "单键" }
                  }
                }
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals(2, result.worldBookEntries.size)
        val byId = result.worldBookEntries.associateBy { it.id }
        assertEquals("甲", byId["10"]!!.content)
        assertEquals(false, byId["10"]!!.disable)
        // 单字符串 key 规整成单元素数组
        assertEquals(listOf("单键"), Json.decodeFromString<List<String>>(byId["20"]!!.keysJson))
    }

    @Test
    fun `世界书id被挂到角色实体`() {
        val json = """
            {
              "data": {
                "name": "A",
                "character_book": { "entries": { "1": { "content": "c" } } }
              }
            }
        """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val bookId = result.worldBook?.id.orEmpty()
        assertTrue(bookId.isNotBlank())
        assertEquals(bookId, result.character.worldBookId)
        // 条目挂在同一本书下
        assertTrue(result.worldBookEntries.all { it.bookId == bookId })
    }

    @Test
    fun `非法JSON返回null`() {
        assertNull(CardParser.parse("{ 不是 json"))
        assertNull(CardParser.parse(""))
    }
}
