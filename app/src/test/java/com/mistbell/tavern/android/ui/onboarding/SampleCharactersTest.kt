package com.mistbell.tavern.android.ui.onboarding

import com.mistbell.tavern.android.util.CardParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首启引导的内置示例角色资产防线：assets 里分发的示例卡必须能被 CardParser 解析——
 * 示例卡损坏会让新用户的第一步直接失败（激活漏斗首环），必须在 CI 上拦住。
 * 读取路径依赖 Gradle 单测工作目录 = 模块目录（app/）。
 */
class SampleCharactersTest {
    @Test
    fun `内置示例角色资产全部可解析且字段完整`() {
        val dir = File("src/main/assets/sample_characters")
        assertTrue("示例角色资产目录不存在: ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("示例角色资产为空", files.isNotEmpty())

        files.forEach { f ->
            val result = CardParser.parse(f.readText(Charsets.UTF_8))
            assertNotNull("示例角色解析失败: ${f.name}", result)
            assertEquals("示例角色缺名字: ${f.name}", false, result!!.character.name.isBlank())
            assertEquals("示例角色缺开场白: ${f.name}", false, result.character.firstMes.isBlank())
        }
    }
}
