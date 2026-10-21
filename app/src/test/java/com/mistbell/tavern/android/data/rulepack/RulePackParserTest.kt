package com.mistbell.tavern.android.data.rulepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** RulePackParser 的 JVM 单测：zip 结构校验、内容解析、致命/非致命错误分级。 */
class RulePackParserTest {
    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val validManifest = """{"id":"demo","name":"演示包","author":"测试"}"""

    @Test
    fun `完整包解析成功`() {
        val bytes =
            zipOf(
                mapOf(
                    "manifest.json" to validManifest,
                    "prompts.json" to """[{"name":"p1","content":"内容","position":"SYSTEM_END"}]""",
                    "regex.json" to """[{"name":"r1","pattern":"笨蛋","replacement":"**笨蛋**"}]""",
                    "macros.json" to """[{"name":"m1","value":"值"}]""",
                ),
            )
        val bundle = RulePackParser.parseZip(bytes)
        assertEquals("demo", bundle.manifest.id)
        assertEquals(1, bundle.content.prompts.size)
        assertEquals(1, bundle.content.regex.size)
        assertEquals(1, bundle.content.macros.size)
        assertTrue(bundle.warnings.isEmpty())
    }

    @Test
    fun `仅 manifest 的最小包合法`() {
        val bundle = RulePackParser.parseZip(zipOf(mapOf("manifest.json" to validManifest)))
        assertEquals("demo", bundle.manifest.id)
        assertTrue(bundle.content.prompts.isEmpty())
        assertTrue(bundle.content.regex.isEmpty())
        assertTrue(bundle.content.macros.isEmpty())
    }

    @Test
    fun `缺少 manifest 拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(zipOf(mapOf("prompts.json" to "[]")))
        }
    }

    @Test
    fun `id 不合法拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(zipOf(mapOf("manifest.json" to """{"id":"../evil","name":"x"}""")))
        }
    }

    @Test
    fun `路径穿越条目拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(zipOf(mapOf("manifest.json" to validManifest, "../evil.txt" to "x")))
        }
    }

    @Test
    fun `坏正则不拒整包只告警跳过`() {
        val bytes =
            zipOf(
                mapOf(
                    "manifest.json" to validManifest,
                    "regex.json" to """[{"name":"bad","pattern":"[未闭合"},{"name":"good","pattern":"ok"}]""",
                ),
            )
        val bundle = RulePackParser.parseZip(bytes)
        assertEquals(1, bundle.content.regex.size)
        assertEquals("good", bundle.content.regex.first().name)
        assertTrue(bundle.warnings.any { it.contains("bad") })
    }

    @Test
    fun `内容文件解析失败按空表处理并告警`() {
        val bytes =
            zipOf(
                mapOf(
                    "manifest.json" to validManifest,
                    "prompts.json" to "这不是 JSON",
                ),
            )
        val bundle = RulePackParser.parseZip(bytes)
        assertTrue(bundle.content.prompts.isEmpty())
        assertTrue(bundle.warnings.any { it.contains("prompts.json") })
    }

    @Test
    fun `空名宏条目跳过并告警`() {
        val bytes =
            zipOf(
                mapOf(
                    "manifest.json" to validManifest,
                    "macros.json" to """[{"name":"","value":"x"},{"name":"ok","value":"y"}]""",
                ),
            )
        val bundle = RulePackParser.parseZip(bytes)
        assertEquals(1, bundle.content.macros.size)
        assertTrue(bundle.warnings.isNotEmpty())
    }
}
