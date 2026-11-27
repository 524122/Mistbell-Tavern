package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「需要高级模式」判定（P3 导入报告）单测。
 * 覆盖：四个判定字段各自命中与不命中、世界书条目里的 HTML、默认值（干净卡 = 无）、
 * extensions 形态容错、导入结果 / 实体 / 领域模型三种输入的一致性、批量聚合。
 */
class AdvancedCapabilityDetectorTest {
    private fun extensions(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun entity(
        firstMes: String = "你好",
        mesExample: String = "",
        description: String = "",
        dataJson: String = "{}",
    ) = CharacterEntity(
        id = "c1",
        name = "测试卡",
        role = "assistant",
        description = description,
        personality = "",
        scenario = "",
        firstMes = firstMes,
        mesExample = mesExample,
        color = "#007AFF",
        avatarData = "",
        worldBookId = "",
        dataJson = dataJson,
    )

    private fun entry(content: String) =
        WorldBookEntryEntity(
            id = "e1",
            bookId = "b1",
            comment = "",
            keysJson = "[]",
            content = content,
            constant = false,
            disable = false,
            order = 0,
        )

    private fun domain(entity: CharacterEntity): Character =
        Character(
            id = entity.id,
            name = entity.name,
            role = entity.role,
            description = entity.description,
            personality = entity.personality,
            scenario = entity.scenario,
            firstMes = entity.firstMes,
            mesExample = entity.mesExample,
            color = entity.color,
            avatarData = entity.avatarData,
            worldBookId = entity.worldBookId,
            data =
                entity.dataOrNullForTest(),
        )

    /** 与 CharacterEntity.toDomain 同一解码口径的测试侧实现 */
    private fun CharacterEntity.dataOrNullForTest(): CharacterData? =
        try {
            if (dataJson.isNotBlank()) {
                Json { ignoreUnknownKeys = true }.decodeFromString(CharacterData.serializer(), dataJson)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    // ---- 四个字段：命中与不命中 ----

    @Test
    fun `卡内正则按条数命中`() {
        val profile =
            AdvancedCapabilityDetector.detect(
                extensions("""{"regex_scripts":[{"scriptName":"a"},{"scriptName":"b"},{"scriptName":"c"}]}"""),
                emptyList(),
            )
        assertEquals(3, profile.regexCount)
        assertTrue(profile.hasAny)
        assertFalse(profile.hasHtmlUi)
        assertFalse(profile.hasMvuUpdates)
    }

    @Test
    fun `卡内脚本按段数命中`() {
        val profile =
            AdvancedCapabilityDetector.detect(
                extensions("""{"tavern_helper":{"scripts":{"s1":{"content":"x"},"s2":{"content":"y"}}}}"""),
                emptyList(),
            )
        assertEquals(2, profile.scriptCount)
        assertTrue(profile.hasAny)
    }

    @Test
    fun `tavern_helper缺scripts或形态不符时计零`() {
        assertEquals(
            0,
            AdvancedCapabilityDetector.detect(extensions("""{"tavern_helper":{}}"""), emptyList()).scriptCount,
        )
        assertEquals(
            0,
            AdvancedCapabilityDetector.detect(
                extensions("""{"tavern_helper":{"scripts":"no"}}"""),
                emptyList(),
            ).scriptCount,
        )
        assertEquals(
            0,
            AdvancedCapabilityDetector.detect(extensions("""{"regex_scripts":"no"}"""), emptyList()).regexCount,
        )
        assertEquals(
            0,
            AdvancedCapabilityDetector.detect(extensions("""{}"""), emptyList()).regexCount,
        )
    }

    @Test
    fun `HTML界面四种口径各自命中`() {
        val corpus =
            listOf(
                "```html\n<html><body>界面</body>\n```",
                "<!DOCTYPE html><html><body>x</body></html>",
                "<style>.heart{color:red}</style>",
                "<table><tr><td>表</td></tr></table>",
            )
        corpus.forEach { text ->
            assertTrue("应命中：$text", AdvancedCapabilityDetector.detect(null, listOf(text)).hasHtmlUi)
        }
    }

    @Test
    fun `纯文本与代码围栏不误报HTML`() {
        assertFalse(AdvancedCapabilityDetector.detect(null, listOf("普通开场白，没有标签")).hasHtmlUi)
        assertFalse(AdvancedCapabilityDetector.detect(null, listOf("```js\nvar a = 1\n```")).hasHtmlUi)
    }

    @Test
    fun `MVU更新标记命中且忽略大小写`() {
        val mvuBlock = "<UpdateVariable>;;'</UpdateVariable>"
        assertTrue(AdvancedCapabilityDetector.detect(null, listOf(mvuBlock)).hasMvuUpdates)
        assertTrue(AdvancedCapabilityDetector.detect(null, listOf("<updatevariable>")).hasMvuUpdates)
        assertFalse(AdvancedCapabilityDetector.detect(null, listOf("更新一下变量")).hasMvuUpdates)
    }

    @Test
    fun `世界书条目里的HTML命中而纯文本条目不误报`() {
        val hit = AdvancedCapabilityDetector.detect(entity(), listOf(entry("<style>.a{}</style>状态栏")))
        assertTrue(hit.hasHtmlUi)
        val miss = AdvancedCapabilityDetector.detect(entity(), listOf(entry("纯文本条目")))
        assertFalse(miss.hasHtmlUi)
    }

    @Test
    fun `干净卡四字段默认全无`() {
        val profile = AdvancedCapabilityDetector.detect(entity(), emptyList())
        assertEquals(AdvancedCapabilityProfile.NONE, profile)
        assertFalse(AdvancedCapabilityProfile.NONE.hasAny)
        assertFalse(AdvancedCapabilityDetector.detect(null, emptyList()).hasAny)
    }

    @Test
    fun `语料含备用开场白与角色描述`() {
        val dataJson =
            """{"alternate_greetings":["<table><tr><td>备用开场</td></tr></table>"]}"""
        assertTrue(AdvancedCapabilityDetector.detect(entity(dataJson = dataJson), emptyList()).hasHtmlUi)
        assertTrue(
            AdvancedCapabilityDetector.detect(entity(description = "<style>.d{}</style>"), emptyList()).hasHtmlUi,
        )
    }

    // ---- 导入结果 / 实体 / 领域模型三种输入一致 ----

    @Test
    fun `CardParser填充能力概况且与实体判定同一输入一致`() {
        val cardJson =
            """
            {"spec":"chara_card_v2","data":{
              "name":"探测卡","description":"描述",
              "first_mes":"<style>.a{}</style>开场",
              "extensions":{"regex_scripts":[{},{}],"tavern_helper":{"scripts":[{"content":"s"}]}}
            },
            "character_book":{"name":"书","entries":{"1":{"content":"<table><tr><td>1</td></tr></table>"}}}}
            """.trimIndent()
        val result = CardParser.parse(cardJson) ?: return assertTrue("卡解析不应失败", false)
        val fromResult = result.advancedCapabilities
        val fromParts = AdvancedCapabilityDetector.detect(result.character, result.worldBookEntries)
        assertEquals(fromParts, fromResult)
        assertEquals(2, fromResult.regexCount)
        assertEquals(1, fromResult.scriptCount)
        assertTrue(fromResult.hasHtmlUi)
    }

    @Test
    fun `CardParser干净卡能力概况为无`() {
        val cardJson = """{"spec":"chara_card_v2","data":{"name":"干净卡","first_mes":"你好"}}"""
        val result = CardParser.parse(cardJson) ?: return assertTrue("卡解析不应失败", false)
        assertEquals(AdvancedCapabilityProfile.NONE, result.advancedCapabilities)
        assertFalse(result.advancedCapabilities.hasAny)
    }

    @Test
    fun `同一张卡实体与领域模型判定一致`() {
        val dataJson =
            """{"extensions":{"regex_scripts":[{}]},"alternate_greetings":["备用"]}"""
        val card = entity(mesExample = "<UpdateVariable>;x</UpdateVariable>", dataJson = dataJson)
        assertEquals(
            AdvancedCapabilityDetector.detect(card, emptyList()),
            AdvancedCapabilityDetector.detect(domain(card)),
        )
        assertTrue(AdvancedCapabilityDetector.detect(domain(card)).hasMvuUpdates)
        assertEquals(1, AdvancedCapabilityDetector.detect(domain(card)).regexCount)
    }

    // ---- 批量聚合 ----

    @Test
    fun `批量聚合数量累加且角色名去重保序`() {
        val totals =
            AdvancedCapabilityTotals.aggregate(
                listOf(
                    "甲" to AdvancedCapabilityProfile(regexCount = 2, hasHtmlUi = true),
                    "乙" to AdvancedCapabilityProfile(regexCount = 1, scriptCount = 3, hasMvuUpdates = true),
                    "丙" to AdvancedCapabilityProfile(hasHtmlUi = true),
                    "甲" to AdvancedCapabilityProfile(regexCount = 4),
                ),
            )
        assertEquals(7, totals.regexCount)
        assertEquals(3, totals.scriptCount)
        assertEquals(2, totals.htmlUiCount)
        assertEquals(1, totals.mvuCount)
        assertEquals(listOf("甲", "乙"), totals.regexCharacters)
        assertEquals(listOf("乙"), totals.scriptCharacters)
        assertEquals(listOf("甲", "丙"), totals.htmlUiCharacters)
        assertEquals(listOf("乙"), totals.mvuCharacters)
        assertTrue(totals.hasAny)
    }

    @Test
    fun `批量聚合全干净卡与空批都是无`() {
        assertEquals(
            AdvancedCapabilityTotals.NONE,
            AdvancedCapabilityTotals.aggregate(listOf("甲" to AdvancedCapabilityProfile.NONE)),
        )
        assertEquals(AdvancedCapabilityTotals.NONE, AdvancedCapabilityTotals.aggregate(emptyList()))
        assertFalse(AdvancedCapabilityTotals.NONE.hasAny)
    }

    // ---- 报告字段的缺省 ----

    @Test
    fun `SyncReport新字段默认为无`() {
        val report =
            SyncReport(
                createdCharacters = 1,
                updatedCharacters = 0,
                createdBooks = 0,
                updatedBooks = 0,
                skippedSprites = 0,
                skippedFiles = emptyList(),
                failedFiles = emptyList(),
                duplicateNames = emptyList(),
                durationMs = 5,
            )
        assertEquals(AdvancedCapabilityTotals.NONE, report.advancedCapabilities)
        assertFalse(report.advancedCapabilities.hasAny)
    }

    @Test
    fun `下沉后的HtmlCardText与楼层检测器同一口径`() {
        val samples =
            listOf(
                "```html\n<x>\n```",
                "<!DOCTYPE html><html></html>",
                "<style>.a{}</style>",
                "<table><tr><td>1</td></tr></table>",
                "<span class=\"c\">x</span>",
                "纯文本",
            )
        val detector = com.mistbell.tavern.android.ui.chat.htmlui.HtmlCardDetector
        samples.forEach { sample ->
            assertEquals(HtmlCardText.containsHtml(sample), detector.containsHtml(sample))
        }
    }
}
