package com.mistbell.tavern.android.ui.chat.htmlui

import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.extensionpack.NetworkAllowlistProvider
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络白名单数据驱动（P3）：CSP 串 builder、生效域并集、扩展包授权域启停生效。
 * 基础集（两个 worker.dev 产品域）不变；扩展域只认启用包 permissions_json 的 network:<host>。
 */
class NetworkAllowlistTest {
    @After
    fun tearDown() {
        NetworkAllowlistProvider.onPacksChanged(emptyList())
    }

    private fun pack(
        id: String,
        permissions: List<String>,
        enabled: Boolean = true,
    ): ExtensionPackEntity =
        ExtensionPackEntity(
            id = id,
            name = id,
            author = "",
            version = "1",
            permissionsJson = ExtensionPackStore.encodePermissions(permissions),
            enabled = enabled,
            permissionsConfirmed = true,
            contentHash = "h-$id",
            installedAt = "t",
        )

    // ---- CSP builder ----

    @Test
    fun `buildCsp 基础域串与现状一致`() {
        assertEquals(
            "default-src 'none'; " +
                "script-src 'unsafe-inline' https://tavern.local https://cdn.tailwindcss.com; " +
                "style-src 'unsafe-inline' https:; " +
                "img-src https: data:; " +
                "font-src https: data:; " +
                "media-src https:; " +
                "connect-src " +
                "https://cangxuan-workshop.cangxuanjie.workers.dev " +
                "https://cangxuan-forum.cangxuanjie.workers.dev; " +
                "frame-src 'none'; object-src 'none';",
            HtmlUiSandbox.buildCsp(HtmlUiSandbox.effectiveConnectHosts(emptyList())),
        )
    }

    @Test
    fun `生效域为基础集并集 扩展域按字典序追加去重`() {
        val hosts =
            HtmlUiSandbox.effectiveConnectHosts(
                listOf("z.example.com", "api.example.com", "cangxuan-forum.cangxuanjie.workers.dev"),
            )
        assertEquals(
            listOf(
                "cangxuan-workshop.cangxuanjie.workers.dev",
                "cangxuan-forum.cangxuanjie.workers.dev",
                "api.example.com",
                "z.example.com",
            ),
            hosts.toList(),
        )
    }

    @Test
    fun `生效域 host 小写归一与空白过滤`() {
        val hosts = HtmlUiSandbox.effectiveConnectHosts(listOf("API.Example.com", "  ", ""))
        assertEquals("api.example.com", hosts.last())
        assertEquals(3, hosts.size)
    }

    @Test
    fun `injectCsp 产出 meta 且 connect-src 含扩展域`() {
        NetworkAllowlistProvider.onPacksChanged(
            listOf(pack("ext", listOf("network:widget.example.com"))),
        )
        val html = HtmlUiSandbox.injectCsp("<html><head></head><body>hi</body></html>")
        assertTrue(html.contains("Content-Security-Policy"))
        assertTrue(html.contains("https://widget.example.com"))
        assertTrue(html.contains("https://cangxuan-workshop.cangxuanjie.workers.dev"))
    }

    // ---- 拦截判定随扩展域启停生效 ----

    @Test
    fun `扩展域默认拦截 启用后放行 停用后回拦`() {
        val url = "https://widget.example.com/api/data"
        assertFalse("未授权域默认拦截", HtmlUiSandbox.shouldAllowHttps(url, null))

        NetworkAllowlistProvider.onPacksChanged(listOf(pack("ext", listOf("network:widget.example.com"))))
        assertTrue("启用授权包后放行", HtmlUiSandbox.shouldAllowHttps(url, null))

        NetworkAllowlistProvider.onPacksChanged(emptyList())
        assertFalse("停用后回拦", HtmlUiSandbox.shouldAllowHttps(url, null))
    }

    @Test
    fun `禁用包的网络域不生效 大小写不敏感`() {
        NetworkAllowlistProvider.onPacksChanged(
            listOf(
                pack("off", listOf("network:disabled.example.com"), enabled = false),
                pack("on", listOf("network:UPPER.Example.com")),
            ),
        )
        assertFalse(HtmlUiSandbox.shouldAllowHttps("https://disabled.example.com/x", null))
        assertTrue(HtmlUiSandbox.shouldAllowHttps("https://upper.example.com/x", null))
    }

    // ---- provider 纯函数 ----

    @Test
    fun `computeExtensionHosts 只认 enabled 包的 network 声明并去重小写`() {
        val hosts =
            NetworkAllowlistProvider.computeExtensionHosts(
                listOf(
                    pack("a", listOf("network:api.example.com", "network:api.example.com", "prompt:inject")),
                    pack("b", listOf("network:API.example.com")),
                    pack("c", listOf("network:off.example.com"), enabled = false),
                    pack("d", emptyList()),
                ),
            )
        assertEquals(setOf("api.example.com"), hosts)
    }

    @Test
    fun `快照随最近一次包集刷新`() {
        NetworkAllowlistProvider.onPacksChanged(listOf(pack("a", listOf("network:a.example.com"))))
        assertEquals(setOf("a.example.com"), NetworkAllowlistProvider.extensionHosts())
        NetworkAllowlistProvider.onPacksChanged(emptyList())
        assertEquals(emptySet<String>(), NetworkAllowlistProvider.extensionHosts())
    }
}
