package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色卡导出纯函数单测（生态互通闭环）。
 * 覆盖：character_book 随卡导出（CCv2/v3 规范数组形态 + extensions，与酒馆读卡路径一致）、
 * 位置/深度/概率与导入映射互逆、v3 卡导出（spec/spec_version + 非默认 v3 字段）与再导入保真。
 */
class CharacterExporterTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** 从导入结果还原 CharacterData（与运行时 toDomain 同一解码路径，与 CardParserTest 同款） */
    private fun dataOf(result: CharacterImportResult): CharacterData =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            CharacterData.serializer(),
            result.character.dataJson,
        )

    /** 带 3 个非默认 v3 字段的测试角色（nickname / assets / group_only_greetings） */
    private fun v3Character() =
        Character(
            name = "A",
            data =
                CharacterData(
                    nickname = "小雅",
                    assets = Json.parseToJsonElement("""[{"type":"icon","uri":"x.png"}]"""),
                    groupOnlyGreetings = listOf("群开场"),
                ),
        )

    private fun bookEntry(
        id: String,
        insertPosition: String = "before_prompt",
        depth: Int = 0,
        depthRole: String = "system",
        probability: Double = 1.0,
    ) = WorldBookEntryEntity(
        id = id,
        bookId = "wb",
        comment = id,
        keysJson = """["剑"]""",
        content = "c$id",
        constant = false,
        disable = false,
        order = 100,
        insertPosition = insertPosition,
        depth = depth,
        depthRole = depthRole,
        probability = probability,
    )

    @Test
    fun `无世界书时不输出character_book键`() {
        val v2 = json.parseToJsonElement(CharacterExporter.buildV2Json(Character(name = "A"))).jsonObject
        val data = v2["data"]!!.jsonObject
        assertNull("未挂世界书时 data 内不应有 character_book", data["character_book"])
    }

    @Test
    fun `toStPosition与导入映射互逆`() {
        // 酒馆 world_info_position：0=角色前 1=角色后 2/3=作者注释顶/底 4=atDepth 5/6=示例消息顶/底
        assertEquals(0, StInterop.toStPosition("before_prompt", 0))
        assertEquals(1, StInterop.toStPosition("after_prompt", 0))
        assertEquals(2, StInterop.toStPosition("an_before", 0))
        assertEquals(3, StInterop.toStPosition("an_after", 0))
        assertEquals(5, StInterop.toStPosition("em_before", 0))
        assertEquals(6, StInterop.toStPosition("em_after", 0))
        // depth>0 一律导为 atDepth(4) + depth 值
        assertEquals(4, StInterop.toStPosition("after_prompt", 4))
    }

    @Test
    fun `导出条目为规范数组形态并带extensions`() {
        // 酒馆读卡内嵌书走 keys/enabled/字符串 position/extensions.*（world-info.js convertCharacterBook）
        val entries =
            listOf(
                bookEntry("e0", insertPosition = "an_before", probability = 0.5),
                bookEntry("e1", depth = 3, depthRole = "assistant"),
            )
        val v2 =
            json.parseToJsonElement(
                CharacterExporter.buildV2Json(Character(name = "A"), "测试书", entries),
            ).jsonObject
        val book = v2["data"]!!.jsonObject["character_book"]?.jsonObject
        assertNotNull("应输出 character_book", book)
        assertEquals("测试书", book!!["name"]!!.jsonPrimitive.content)

        val exported = book["entries"]!!.jsonArray
        assertEquals(2, exported.size)
        val e0 = exported[0].jsonObject
        // 规范字段名与锚点
        assertEquals(listOf("剑"), e0["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, e0["enabled"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("before_char", e0["position"]!!.jsonPrimitive.content)
        assertEquals(100, e0["insertion_order"]!!.jsonPrimitive.intOrNull)
        // 真实位置/概率在 extensions（酒馆 0-6 枚举 + 0-100 百分数）
        val ext0 = e0["extensions"]!!.jsonObject
        assertEquals(2, ext0["position"]!!.jsonPrimitive.intOrNull)
        assertEquals(50, ext0["probability"]!!.jsonPrimitive.intOrNull)
        assertEquals(true, ext0["useProbability"]!!.jsonPrimitive.booleanOrNull)

        val ext1 = exported[1].jsonObject["extensions"]!!.jsonObject
        assertEquals(4, ext1["position"]!!.jsonPrimitive.intOrNull)
        assertEquals(3, ext1["depth"]!!.jsonPrimitive.intOrNull)
        assertEquals(2, ext1["role"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun `stToStRole与导入映射互逆`() {
        assertEquals(0, StInterop.toStRole("system"))
        assertEquals(1, StInterop.toStRole("user"))
        assertEquals(2, StInterop.toStRole("assistant"))
        assertEquals(0, StInterop.toStRole("unknown"))
    }

    @Test
    fun `导出再导入位置深度概率保真`() {
        val entries =
            listOf(
                bookEntry("e0", insertPosition = "an_after", probability = 0.5),
                bookEntry("e1", depth = 2, depthRole = "assistant", probability = 0.3),
                bookEntry("e2", insertPosition = "em_before"),
            )
        val exported = CharacterExporter.buildV2Json(Character(name = "A"), "书", entries)
        val result = CardParser.parse(exported) ?: return assertTrue(false)
        assertEquals(3, result.worldBookEntries.size)

        val byContent = result.worldBookEntries.associateBy { it.content }
        val round0 = byContent["ce0"]!!
        assertEquals("an_after", round0.insertPosition)
        assertEquals(0, round0.depth)
        assertEquals(0.5, round0.probability, 0.0001)

        val round1 = byContent["ce1"]!!
        assertEquals(2, round1.depth)
        assertEquals("assistant", round1.depthRole)
        assertEquals(0.3, round1.probability, 0.0001)

        val round2 = byContent["ce2"]!!
        assertEquals("em_before", round2.insertPosition)
        // AN/EM 锚点往返无未知位置提示
        assertTrue(result.warnings.none { it.contains("未知插入位置") })
    }

    @Test
    fun `导出的书可被酒馆换算路径读取`() {
        // 模拟酒馆 convertCharacterBook 的读取点：keys/enabled/insertion_order/extensions.position
        val entries =
            listOf(
                bookEntry("e0", insertPosition = "after_prompt", probability = 0.8),
            )
        val exported = CharacterExporter.buildV2Json(Character(name = "A"), "书", entries)
        val book =
            json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject["character_book"]!!.jsonObject
        val entry = book["entries"]!!.jsonArray.single().jsonObject
        // 酒馆读 entry.keys / entry.enabled / entry.insertion_order / entry.extensions.position
        assertNotNull(entry["keys"]!!.jsonArray)
        assertEquals(true, entry["enabled"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(100, entry["insertion_order"]!!.jsonPrimitive.intOrNull)
        assertEquals(1, entry["extensions"]!!.jsonObject["position"]!!.jsonPrimitive.intOrNull)
        assertEquals("after_char", entry["position"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v3导出带spec与v3字段`() {
        val v3 = json.parseToJsonElement(CharacterExporter.buildV3Json(v3Character())).jsonObject
        assertEquals("chara_card_v3", v3["spec"]!!.jsonPrimitive.content)
        assertEquals("3.0", v3["spec_version"]!!.jsonPrimitive.content)
        val data = v3["data"]!!.jsonObject
        // 非默认 v3 字段按原名键输出
        assertEquals("小雅", data["nickname"]!!.jsonPrimitive.content)
        assertEquals(listOf("群开场"), data["group_only_greetings"]!!.jsonArray.map { it.jsonPrimitive.content })
        val assets = data["assets"]!!.jsonArray.single().jsonObject
        assertEquals("icon", assets["type"]!!.jsonPrimitive.content)
        assertEquals("x.png", assets["uri"]!!.jsonPrimitive.content)
        // 未赋值的 v3 字段取默认值，不输出
        assertNull("默认 creator_notes_multilingual 不应输出", data["creator_notes_multilingual"])
        assertNull("默认 source 不应输出", data["source"])
    }

    @Test
    fun `v2导出不强制输出v3默认字段`() {
        val v2 = json.parseToJsonElement(CharacterExporter.buildV2Json(v3Character())).jsonObject
        // spec 保持 v2；nickname 非默认时透传保真（酒馆同款行为）
        assertEquals("chara_card_v2", v2["spec"]!!.jsonPrimitive.content)
        assertEquals("2.0", v2["spec_version"]!!.jsonPrimitive.content)
        assertEquals("小雅", v2["data"]!!.jsonObject["nickname"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v3导出再导入保真`() {
        val exported = CharacterExporter.buildV3Json(v3Character())
        val result = CardParser.parse(exported) ?: return assertTrue(false)
        assertEquals("A", result.character.name)
        val data = dataOf(result)
        assertEquals("小雅", data.nickname)
        assertEquals(listOf("群开场"), data.groupOnlyGreetings)
        val assets = data.assets!!.jsonArray.single().jsonObject
        assertEquals("icon", assets["type"]!!.jsonPrimitive.content)
        assertEquals("x.png", assets["uri"]!!.jsonPrimitive.content)
    }
}
