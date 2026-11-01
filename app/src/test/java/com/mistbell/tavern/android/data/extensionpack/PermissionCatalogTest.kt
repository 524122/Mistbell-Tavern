package com.mistbell.tavern.android.data.extensionpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 权限目录测试：固定白名单、network:host 动态形态（P1 从严）、中文元数据完整性。 */
class PermissionCatalogTest {
    @Test
    fun `七个固定权限全部可解析且中文元数据完整`() {
        val fixed =
            listOf(
                "prompt:inject",
                "reply:process",
                "events:subscribe",
                "messages:read",
                "messages:write",
                "generation:control",
                "variables",
            )
        fixed.forEach { raw ->
            val entry = PermissionCatalog.resolve(raw)
            assertNotNull("固定权限 $raw 必须在目录中", entry)
            assertTrue("$raw 中文名不能为空", entry!!.displayName.isNotBlank())
            assertTrue("$raw 说明不能为空", entry.description.isNotBlank())
            assertTrue(PermissionCatalog.isKnown(raw))
        }
    }

    @Test
    fun `目录外权限返回 null`() {
        assertNull(PermissionCatalog.resolve("storage:read"))
        assertNull(PermissionCatalog.resolve("prompt"))
        assertNull(PermissionCatalog.resolve(""))
        assertNull(PermissionCatalog.resolve("NETWORK:host.example"))
    }

    @Test
    fun `network 合法 hostname 解析成功且描述含主机名`() {
        val entry = PermissionCatalog.resolve("network:cangxuan-forum.cangxuanjie.workers.dev")
        assertNotNull(entry)
        assertEquals("network:cangxuan-forum.cangxuanjie.workers.dev", entry!!.raw)
        assertTrue(entry.displayName.contains("cangxuan-forum.cangxuanjie.workers.dev"))
        assertTrue(entry.description.contains("cangxuan-forum.cangxuanjie.workers.dev"))
    }

    @Test
    fun `network 单标签主机名合法`() {
        assertNotNull(PermissionCatalog.resolve("network:localhost"))
    }

    @Test
    fun `network host 非法形态一律拒绝`() {
        // P1 从严：通配符/端口/大写/下划线/空主机/首尾点/首尾连字符/空格逐项非法
        val invalidHosts =
            listOf(
                "network:*.example.com",
                "network:example.com:8080",
                "network:Example.com",
                "network:under_score.example.com",
                "network:",
                "network:.example.com",
                "network:example.com.",
                "network:-example.com",
                "network:example.com-",
                "network:exam ple.com",
            )
        invalidHosts.forEach { raw ->
            assertNull("「$raw」应被视为非法 host", PermissionCatalog.resolve(raw))
            assertTrue(PermissionCatalog.isKnown(raw).not())
        }
    }

    @Test
    fun `resolveAll 未知权限抛出且消息含原始声明值`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                PermissionCatalog.resolveAll(listOf("prompt:inject", "file:read"))
            }
        assertTrue(ex.message!!.contains("file:read"))
    }

    @Test
    fun `resolveAll 合法清单按序返回并可自定义消息前缀`() {
        val entries =
            PermissionCatalog.resolveAll(
                listOf("messages:write", "network:api.example.com"),
                invalidPrefix = "包非法",
            )
        assertEquals(2, entries.size)
        assertEquals("messages:write", entries[0].raw)
        assertEquals("network:api.example.com", entries[1].raw)

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                PermissionCatalog.resolveAll(listOf("nope"), invalidPrefix = "包非法")
            }
        assertTrue(ex.message!!.startsWith("包非法"))
    }
}
