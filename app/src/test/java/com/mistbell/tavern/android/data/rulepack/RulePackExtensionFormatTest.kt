package com.mistbell.tavern.android.data.rulepack

import com.mistbell.tavern.android.data.extensionpack.PermissionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 扩展包制品格式解析测试（RulePackParser 扩展面）：
 * type 缺省向后兼容、严格权限目录、JS 制品存在性/大小/路径校验、files 全量读出供落盘。
 */
class RulePackExtensionFormatTest {
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

    private fun extensionManifest(
        extra: String = "",
        permissions: String = """["prompt:inject","reply:process","network:api.example.com"]""",
    ): String =
        """{"id":"demo-ext","name":"演示扩展","author":"Mistbell","version":"1.0.0",""" +
            """"loadingOrder":50,"type":"extension","permissions":$permissions$extra}"""

    @Test
    fun `扩展包完整解析 manifest 与 JS 制品源码`() {
        val bytes =
            zipOf(
                mapOf(
                    "manifest.json" to
                        extensionManifest(
                            extra = ""","webviewPreload":"facade.js","hostHooks":"hook.js"""",
                        ),
                    "facade.js" to "window.TavernHelper = {};",
                    "hook.js" to "function onPrompt() { return null; }",
                ),
            )
        val bundle = RulePackParser.parseZip(bytes)
        assertEquals("extension", bundle.manifest.type)
        assertEquals(3, bundle.manifest.permissions.size)
        assertEquals("window.TavernHelper = {};", bundle.content.webviewPreloadSource)
        assertEquals("function onPrompt() { return null; }", bundle.content.hostHooksSource)
        // files 全量读出（扩展包仓库据此落盘 filesDir/extensions/<id>/）
        assertEquals(setOf("manifest.json", "facade.js", "hook.js"), bundle.files.keys)
    }

    @Test
    fun `type 缺省即规则包（向后兼容）`() {
        val bundle = RulePackParser.parseZip(zipOf(mapOf("manifest.json" to """{"id":"demo","name":"x"}""")))
        assertEquals(RulePackManifest.TYPE_RULEPACK, bundle.manifest.type)
        assertNull(bundle.content.webviewPreloadSource)
        assertNull(bundle.content.hostHooksSource)
    }

    @Test
    fun `type 未知值拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(zipOf(mapOf("manifest.json" to """{"id":"demo","name":"x","type":"theme"}""")))
        }
    }

    @Test
    fun `未知权限拒绝且消息指明该权限`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                RulePackParser.parseZip(
                    zipOf(
                        mapOf(
                            "manifest.json" to extensionManifest(permissions = """["storage:read"]"""),
                        ),
                    ),
                )
            }
        assertTrue(ex.message!!.contains("storage:read"))
    }

    @Test
    fun `network host 非法形态拒绝导入`() {
        listOf(
            """["network:*.example.com"]""",
            """["network:example.com:8080"]""",
            """["network:Example.com"]""",
        ).forEach { perms ->
            assertThrows("perms=$perms 应被拒", IllegalArgumentException::class.java) {
                RulePackParser.parseZip(
                    zipOf(mapOf("manifest.json" to extensionManifest(permissions = perms))),
                )
            }
        }
    }

    @Test
    fun `规则包声明权限同样过权限目录校验（不区分 type）`() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(
                zipOf(mapOf("manifest.json" to """{"id":"demo","name":"x","permissions":["bogus"]}""")),
            )
        }
        // 合法权限不拒
        val bundle =
            RulePackParser.parseZip(
                zipOf(mapOf("manifest.json" to """{"id":"demo","name":"x","permissions":["variables"]}""")),
            )
        assertEquals(listOf("variables"), bundle.manifest.permissions)
    }

    @Test
    fun `webviewPreload 超 64KB 拒绝边界内放行`() {
        val ok = "a".repeat(64 * 1024)
        val bundle =
            RulePackParser.parseZip(
                zipOf(
                    mapOf(
                        "manifest.json" to extensionManifest(extra = ""","webviewPreload":"facade.js""""),
                        "facade.js" to ok,
                    ),
                ),
            )
        assertEquals(ok, bundle.content.webviewPreloadSource)

        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(
                zipOf(
                    mapOf(
                        "manifest.json" to extensionManifest(extra = ""","webviewPreload":"facade.js""""),
                        "facade.js" to "a".repeat(64 * 1024 + 1),
                    ),
                ),
            )
        }
    }

    @Test
    fun `hostHooks 超 256KB 拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(
                zipOf(
                    mapOf(
                        "manifest.json" to extensionManifest(extra = ""","hostHooks":"hook.js""""),
                        "hook.js" to "a".repeat(256 * 1024 + 1),
                    ),
                ),
            )
        }
    }

    @Test
    fun `声明的 JS 制品在 zip 中缺失拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePackParser.parseZip(
                zipOf(mapOf("manifest.json" to extensionManifest(extra = ""","webviewPreload":"facade.js""""))),
            )
        }
    }

    @Test
    fun `JS 制品声明含路径拒绝`() {
        listOf(
            ""","webviewPreload":"../evil.js"""",
            ""","webviewPreload":"sub/dir.js"""",
        ).forEach { extra ->
            assertThrows("extra=$extra 应被拒", IllegalArgumentException::class.java) {
                RulePackParser.parseZip(
                    zipOf(
                        mapOf(
                            "manifest.json" to extensionManifest(extra = extra),
                            "evil.js" to "x",
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `无权限无 JS 的扩展包直接解析通过（解析器不特判确认逻辑）`() {
        val bundle =
            RulePackParser.parseZip(
                zipOf(
                    mapOf(
                        "manifest.json" to
                            """{"id":"plain","name":"无权限扩展","type":"extension","permissions":[]}""",
                    ),
                ),
            )
        assertEquals("plain", bundle.manifest.id)
        assertTrue(PermissionCatalog.resolveAll(bundle.manifest.permissions).isEmpty())
    }
}
