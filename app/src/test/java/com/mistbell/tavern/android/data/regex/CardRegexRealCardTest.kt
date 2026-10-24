package com.mistbell.tavern.android.data.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CardRegexRealCardTest {
    @Test
    fun `真实苍玄界卡开场白替换`() {
        val file = File("../build/card-cangxuan.json")
        if (!file.exists()) return // 本地无卡文件时跳过（CI 兼容）
        val card = Json.parseToJsonElement(file.readText()).jsonObject
        val data = card["data"]!!.jsonObject
        val scripts = CardRegexEngine.parse(data["extensions"]!!.jsonObject)
        println("parsed: ${scripts.size}")
        val result = CardRegexEngine.applyToAiDisplay("【GameStart】", scripts)
        println("replaced length: ${result.length}")
        assertTrue(result.length > 1000)
        assertTrue(result.contains("<!DOCTYPE html>"))
    }

    @Test
    fun `真实苍玄界卡按深度过滤提示词变量块`() {
        val file = File("../build/card-cangxuan.json")
        if (!file.exists()) return // 本地无卡文件时跳过（CI 兼容）
        val card = Json.parseToJsonElement(file.readText()).jsonObject
        val data = card["data"]!!.jsonObject
        val scripts = CardRegexEngine.parse(data["extensions"]!!.jsonObject)
        val input = "<UpdateVariable>\n{\"stat_data\":{}}\n</UpdateVariable>"
        val depthFour = CardRegexEngine.applyToPrompt(input, "assistant", scripts, depth = 4)
        val depthSix = CardRegexEngine.applyToPrompt(input, "assistant", scripts, depth = 6)
        assertTrue(depthFour.contains("<UpdateVariable>"))
        assertFalse(depthSix.contains("<UpdateVariable>"))
    }
}
