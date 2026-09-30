package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StSyncImporter 纯决策核心单元测试（从酒馆同步）。
 * 覆盖：JSON 分类（世界书先判 / v1、v2 卡识别 / 垃圾拒收）、
 * 已存在角色的同步合并（保 id 与本地个性化、头像空值不回退）、
 * 卡→书链接解析（内嵌优先于 extensions.world）、同名先到先得。
 */
class StSyncImporterTest {
    private fun existingEntity(
        id: String = "local-1",
        name: String = "旧名",
        avatarData: String = "data:image/jpeg;base64,OLD",
    ) = CharacterEntity(
        id = id,
        name = name,
        role = "assistant",
        description = "旧描述",
        personality = "旧性格",
        scenario = "旧场景",
        firstMes = "旧开场",
        mesExample = "旧示例",
        color = "#FF0000",
        avatarData = avatarData,
        worldBookId = "old-book",
        themeId = "theme-9",
        dataJson = "{}",
    )

    private fun parsedResult(
        name: String = "新名",
        avatarData: String = "data:image/jpeg;base64,NEW",
        dataJson: String = "{}",
    ) = CharacterImportResult(
        character =
            CharacterEntity(
                id = "parsed-1",
                name = name,
                role = "assistant",
                description = "新描述",
                personality = "新性格",
                scenario = "新场景",
                firstMes = "新开场",
                mesExample = "新示例",
                color = "#007AFF",
                avatarData = avatarData,
                worldBookId = "parsed-book",
                dataJson = dataJson,
            ),
        worldBook = null,
        worldBookEntries = emptyList(),
    )

    @Test
    fun `entries根键识别为世界书且先于卡解析`() {
        val mapForm = """{"name":"书","entries":{"1":{"content":"c"}}}"""
        assertEquals(StSyncClassifier.Kind.WorldBook, StSyncClassifier.classifyJson(mapForm))
        val arrayForm = """{"entries":[{"uid":"1","content":"c"}]}"""
        assertEquals(StSyncClassifier.Kind.WorldBook, StSyncClassifier.classifyJson(arrayForm))
    }

    @Test
    fun `v2与v1卡识别为角色卡而垃圾JSON拒收`() {
        val v2 = """{"spec":"chara_card_v2","data":{"name":"A","description":"d"}}"""
        assertEquals(StSyncClassifier.Kind.Card, StSyncClassifier.classifyJson(v2))
        val v1 = """{"char_name":"A","char_persona":"p","char_greeting":"hi"}"""
        assertEquals(StSyncClassifier.Kind.Card, StSyncClassifier.classifyJson(v1))
        val bareName = """{"name":"A","description":"d"}"""
        assertEquals(StSyncClassifier.Kind.Card, StSyncClassifier.classifyJson(bareName))
        assertTrue(StSyncClassifier.classifyJson("{oops") is StSyncClassifier.Kind.Unknown)
        assertTrue(StSyncClassifier.classifyJson("""{"foo":1}""") is StSyncClassifier.Kind.Unknown)
        assertTrue(StSyncClassifier.classifyJson("""[1,2]""") is StSyncClassifier.Kind.Unknown)
    }

    @Test
    fun `更新保留身份与本地个性化字段其余按PC覆盖`() {
        val merged = applyCardToExisting(existingEntity(), parsedResult(), worldBookId = "wb-new")
        // 保留：id（聊天记录不断链）、role、color、themeId（本地个性化）
        assertEquals("local-1", merged.id)
        assertEquals("assistant", merged.role)
        assertEquals("#FF0000", merged.color)
        assertEquals("theme-9", merged.themeId)
        // 覆盖：PC 版字段
        assertEquals("新名", merged.name)
        assertEquals("新描述", merged.description)
        assertEquals("新性格", merged.personality)
        assertEquals("新场景", merged.scenario)
        assertEquals("新开场", merged.firstMes)
        assertEquals("新示例", merged.mesExample)
        assertEquals("wb-new", merged.worldBookId)
    }

    @Test
    fun `头像空值不覆盖已有头像`() {
        val blankAvatar = applyCardToExisting(existingEntity(), parsedResult(avatarData = ""), worldBookId = "")
        assertEquals("data:image/jpeg;base64,OLD", blankAvatar.avatarData)
        val freshAvatar = applyCardToExisting(existingEntity(), parsedResult(), worldBookId = "")
        assertEquals("data:image/jpeg;base64,NEW", freshAvatar.avatarData)
    }

    @Test
    fun `内嵌书名优先于extensions世界书链接`() {
        val books = mapOf("内嵌书" to "id-a", "Eldoria" to "id-b")
        assertEquals("id-a", resolveWorldBookId("内嵌书", "Eldoria", books))
        assertEquals("id-b", resolveWorldBookId(null, "Eldoria", books))
        // 查不到的书名一律不链接
        assertEquals("", resolveWorldBookId(null, "不存在的书", books))
        assertEquals("", resolveWorldBookId(null, null, books))
        assertEquals("", resolveWorldBookId(null, "  ", books))
    }

    @Test
    fun `同名先到先得且名字trim等价`() {
        val index = SyncNameIndex(listOf("  甲  " to "id-1"))
        assertEquals("id-1", index.lookup("甲"))
        // 重复登记保留先到 id
        index.register("甲", "id-2")
        assertEquals("id-1", index.lookup("甲"))
        index.register("乙", "id-3")
        assertEquals("id-3", index.lookup(" 乙 "))
        assertNull(index.lookup("丙"))
    }

    @Test
    fun `extensionsWorld从dataJson透传读出`() {
        val card =
            """
            {"spec":"chara_card_v2","data":{"name":"A","description":"d","extensions":{"world":"Eldoria"}}}
            """.trimIndent()
        val result = CardParser.parse(card) ?: return assertTrue("卡解析不应失败", false)
        assertEquals("Eldoria", extensionsWorldName(result))
        val noWorld = """{"spec":"chara_card_v2","data":{"name":"A"}}"""
        assertNull(extensionsWorldName(CardParser.parse(noWorld)!!))
    }
}
