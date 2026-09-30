package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.api.model.CharacterData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CardParser 纯函数单元测试（F2 生态互通）。
 * 覆盖：v2 嵌套、v1 老键名兜底、v3 新增字段提取、alternate_greetings/tags/extensions 提取、
 * enabled→disable 反相、insertion_order 优先、entries 数组与 uid-map 两形态，
 * 以及酒馆实测语义（字符串 position、extensions.*、atDepth、0-100 概率）。
 */
class CardParserTest {
    /** 从导入结果还原 CharacterData（与运行时 toDomain 同一解码路径） */
    private fun dataOf(result: CharacterImportResult): CharacterData =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            CharacterData.serializer(),
            result.character.dataJson,
        )

    @Test
    fun `v2嵌套data字段解析`() {
        val json =
            """
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
        val json =
            """
            { "name": "根名", "description": "根描述", "first_mes": "根问候" }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("根名", result.character.name)
        assertEquals("根描述", result.character.description)
        assertEquals("根问候", result.character.firstMes)
    }

    @Test
    fun `v1老键名兜底映射`() {
        val json =
            """
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
        val json =
            """
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
        val json =
            """
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
        val json =
            """
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
        assertEquals(true, entries[1].disable) // enabled=false → disable=true
    }

    @Test
    fun `insertion_order优先于order`() {
        val json =
            """
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
        val json =
            """
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
        val json =
            """
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
        val json =
            """
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

    @Test
    fun `裸base64头像补dataURI前缀`() {
        // 生态部分卡片 avatar 字段是无前缀 base64；ImageUtils 解码要求 data:image/ 前缀
        val payload = "aGVsbG8gd29ybGQ=".repeat(8)
        val json =
            """
            { "data": { "name": "A", "avatar": "$payload" } }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("data:image/png;base64,$payload", result.character.avatarData)
    }

    @Test
    fun `占位符头像不作头像数据`() {
        // Chub 等平台的 avatar 字段是 "none" 等占位符——包装成 data URI 会丢 PNG 卡本体头像
        val json =
            """
            { "data": { "name": "A", "avatar": "none" } }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("", result.character.avatarData)
    }

    @Test
    fun `带前缀头像原样保留`() {
        val json =
            """
            { "data": { "name": "A", "avatarData": "data:image/jpeg;base64,aGVsbG8=" } }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("data:image/jpeg;base64,aGVsbG8=", result.character.avatarData)
    }

    @Test
    fun `无头像字段时avatarData为空`() {
        val json = """{ "data": { "name": "A" } }""".trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("", result.character.avatarData)
    }

    @Test
    fun `世界书条目position按酒馆枚举映射`() {
        // 酒馆 world_info_position：0=角色前 1=角色后 2/3=作者注释顶/底 4=atDepth 5/6=示例消息顶/底
        val json =
            """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": [
                    { "uid": 0, "content": "c0", "position": 0 },
                    { "uid": 1, "content": "c1", "position": 1 },
                    { "uid": 2, "content": "c2", "position": 2 },
                    { "uid": 3, "content": "c3", "position": 3 },
                    { "uid": 5, "content": "c5", "position": 5 },
                    { "uid": 6, "content": "c6", "position": 6 }
                  ]
                }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val byId = result.worldBookEntries.associateBy { it.id }
        assertEquals("before_prompt", byId["0"]?.insertPosition)
        assertEquals("after_prompt", byId["1"]?.insertPosition)
        assertEquals("an_before", byId["2"]?.insertPosition)
        assertEquals("an_after", byId["3"]?.insertPosition)
        assertEquals("em_before", byId["5"]?.insertPosition)
        assertEquals("em_after", byId["6"]?.insertPosition)
        assertTrue(result.warnings.none { it.contains("插入位置") })
    }

    @Test
    fun `字符串position按规范映射`() {
        // 酒馆导出的卡内嵌书条目 position 是字符串（实测 default_Seraphina 等）
        val json =
            """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": [
                    { "id": 0, "content": "c0", "position": "before_char", "keys": ["a"] },
                    { "id": 1, "content": "c1", "position": "after_char", "keys": ["b"] }
                  ]
                }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val byId = result.worldBookEntries.associateBy { it.id }
        assertEquals("before_prompt", byId["0"]?.insertPosition)
        assertEquals("after_prompt", byId["1"]?.insertPosition)
    }

    @Test
    fun `atDepth位置取depth且残留depth被忽略`() {
        // 酒馆 UI 恒写 depth 默认值：仅 position=4 生效；position=0 + depth=4 是锚点条目（实测 Eldoria.json）
        val json =
            """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": [
                    { "uid": 0, "content": "c0", "position": 0, "depth": 4 },
                    { "uid": 1, "content": "c1", "position": 4, "depth": 6, "role": 2 },
                    { "uid": 2, "content": "c2", "position": 4 },
                    { "uid": 3, "content": "c3", "position": 4, "depth": 99 }
                  ]
                }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val byId = result.worldBookEntries.associateBy { it.id }
        // position≠4 时 depth 是 UI 残留值，不产生 @D
        assertEquals(0, byId["0"]?.depth)
        assertEquals("before_prompt", byId["0"]?.insertPosition)
        // atDepth 条目进 @D 模式并带角色
        assertEquals(6, byId["1"]?.depth)
        assertEquals("assistant", byId["1"]?.depthRole)
        // atDepth 无 depth（或 0）钳为 1
        assertEquals(1, byId["2"]?.depth)
        assertEquals(10, byId["3"]?.depth)
        assertTrue(result.warnings.any { it.contains("已钳制") })
    }

    @Test
    fun `extensions位置优先于顶层`() {
        // 酒馆导出的卡内嵌书：数字位置在 extensions.position，顶层 position 是规范字符串
        val json =
            """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": [
                    { "id": 0, "content": "c0", "position": "before_char", "keys": ["a"],
                      "extensions": { "position": 5, "probability": 50, "depth": 4 } },
                    { "id": 1, "content": "c1", "position": "before_char", "keys": ["b"],
                      "extensions": { "position": 4, "depth": 2, "role": 1, "probability": 30 } }
                  ]
                }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val byId = result.worldBookEntries.associateBy { it.id }
        assertEquals("em_before", byId["0"]?.insertPosition)
        assertEquals(0.5, byId["0"]?.probability ?: -1.0, 0.0001)
        assertEquals("before_prompt", byId["1"]?.insertPosition)
        assertEquals(2, byId["1"]?.depth)
        assertEquals("user", byId["1"]?.depthRole)
        assertEquals(0.3, byId["1"]?.probability ?: -1.0, 0.0001)
    }

    @Test
    fun `世界书条目未知position兜底并提示`() {
        val json =
            """
            {
              "data": {
                "name": "A",
                "character_book": { "entries": [ { "uid": 0, "content": "c", "position": 9 } ] }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("before_prompt", result.worldBookEntries[0].insertPosition)
        assertTrue(result.warnings.any { it.contains("未知插入位置") })
    }

    @Test
    fun `世界书条目probability按酒馆百分数换算`() {
        // 酒馆 probability 是 0-100 百分数（实测 100 配 useProbability=true）
        val json =
            """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": [
                    { "uid": 0, "content": "c", "probability": 100 },
                    { "uid": 1, "content": "c", "probability": 50 },
                    { "uid": 2, "content": "c", "probability": 0 },
                    { "uid": 3, "content": "c", "probability": 50, "useProbability": false },
                    { "uid": 4, "content": "c" },
                    { "uid": 5, "content": "c", "probability": 0.5 }
                  ]
                }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val byId = result.worldBookEntries.associateBy { it.id }
        assertEquals(1.0, byId["0"]?.probability ?: -1.0, 0.0001)
        assertEquals(0.5, byId["1"]?.probability ?: -1.0, 0.0001)
        assertEquals(0.0, byId["2"]?.probability ?: -1.0, 0.0001)
        // useProbability=false 不掷骰（恒触发）
        assertEquals(1.0, byId["3"]?.probability ?: -1.0, 0.0001)
        // 缺省必触发
        assertEquals(1.0, byId["4"]?.probability ?: -1.0, 0.0001)
        // ≤1 的值按小数保留（兼容应用内旧值）
        assertEquals(0.5, byId["5"]?.probability ?: -1.0, 0.0001)
        assertTrue(result.warnings.none { it.contains("概率") })
    }

    @Test
    fun `酒馆真实卡内嵌书结构解析`() {
        // 按实测酒馆导出结构（default_Seraphina.png chara chunk）构造
        val json =
            """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Seraphina",
                "character_book": {
                  "name": "Seraphina World",
                  "entries": [
                    {
                      "id": 0,
                      "keys": ["eldoria", "forest"],
                      "secondary_keys": [],
                      "comment": "eldoria",
                      "content": "Eldoria is here.",
                      "constant": false,
                      "selective": true,
                      "insertion_order": 100,
                      "enabled": true,
                      "position": "before_char",
                      "use_regex": true,
                      "extensions": {
                        "position": 0,
                        "exclude_recursion": false,
                        "display_index": 0,
                        "probability": 100,
                        "useProbability": true,
                        "depth": 4,
                        "role": 0
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        assertEquals("Seraphina", result.character.name)
        assertEquals(1, result.worldBookEntries.size)
        val entry = result.worldBookEntries.single()
        assertEquals("0", entry.id)
        assertEquals(listOf("eldoria", "forest"), Json.decodeFromString<List<String>>(entry.keysJson))
        assertEquals(false, entry.disable)
        assertEquals(100, entry.order)
        assertEquals("before_prompt", entry.insertPosition)
        assertEquals(0, entry.depth)
        assertEquals(1.0, entry.probability, 0.0001)
    }

    @Test
    fun `世界书条目role映射0到2`() {
        val json =
            """
            {
              "data": {
                "name": "A",
                "character_book": {
                  "entries": [
                    { "uid": 0, "content": "c", "role": 0 },
                    { "uid": 1, "content": "c", "role": 1 },
                    { "uid": 2, "content": "c", "role": 2 },
                    { "uid": 3, "content": "c", "role": 9 }
                  ]
                }
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val byId = result.worldBookEntries.associateBy { it.id }
        assertEquals("system", byId["0"]?.depthRole)
        assertEquals("user", byId["1"]?.depthRole)
        assertEquals("assistant", byId["2"]?.depthRole)
        assertEquals("system", byId["3"]?.depthRole)
        assertTrue(result.warnings.any { it.contains("未知深度角色") })
    }

    @Test
    fun `v3卡新增字段提取进CharacterData`() {
        val json =
            """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "A",
                "nickname": "小雅",
                "creator_notes_multilingual": { "en": "note", "zh": "注释" },
                "source": ["risu"],
                "group_only_greetings": ["群开场"],
                "assets": [ { "type": "icon", "uri": "x.png" } ]
              }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val data = dataOf(result)
        assertEquals("小雅", data.nickname)
        // JsonElement 原文断言：多语言作者注释逐键比对
        val notes = data.creatorNotesMultilingual!!.jsonObject
        assertEquals("note", notes["en"]!!.jsonPrimitive.content)
        assertEquals("注释", notes["zh"]!!.jsonPrimitive.content)
        assertEquals(listOf("risu"), data.source!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("群开场"), data.groupOnlyGreetings)
        val assets = data.assets!!.jsonArray.single().jsonObject
        assertEquals("icon", assets["type"]!!.jsonPrimitive.content)
        assertEquals("x.png", assets["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v2卡缺失v3字段时CharacterData取默认值`() {
        val json =
            """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": { "name": "A" }
            }
            """.trimIndent()
        val result = CardParser.parse(json) ?: return assertTrue(false)
        val data = dataOf(result)
        assertEquals("", data.nickname)
        assertNull(data.creatorNotesMultilingual)
        assertNull(data.source)
        assertEquals(emptyList<String>(), data.groupOnlyGreetings)
        assertNull(data.assets)
    }
}
