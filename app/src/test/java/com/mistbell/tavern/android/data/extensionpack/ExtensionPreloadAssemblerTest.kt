package com.mistbell.tavern.android.data.extensionpack

import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 扩展包 WebView 预注入装配器（P2 注入链数据驱动侧）：排序契约（loadingOrder 升序、
 * 同序按 id 稳定）、缓存键失效语义、filesDir 读取（manifest.json → webviewPreload 文件）。
 */
class ExtensionPreloadAssemblerTest {
    private fun pack(
        id: String,
        loadingOrder: Int = 100,
        hash: String = "hash-$id",
        hasPreload: Boolean = true,
        enabled: Boolean = true,
    ): ExtensionPackEntity =
        ExtensionPackEntity(
            id = id,
            name = id,
            author = "",
            version = "1",
            loadingOrder = loadingOrder,
            hasPreload = hasPreload,
            enabled = enabled,
            contentHash = hash,
            installedAt = "t",
        )

    // ---- 排序契约 ----

    @Test
    fun `排序为 loadingOrder 升序 同序按 id 稳定`() {
        val ordered =
            ExtensionPreloadProvider.preloadablePacks(
                listOf(
                    pack("b", loadingOrder = 50),
                    pack("z", loadingOrder = 100),
                    pack("a", loadingOrder = 50),
                    pack("c", loadingOrder = 200),
                ),
            )
        assertEquals(listOf("a", "b", "z", "c"), ordered.map { it.id })
    }

    @Test
    fun `未声明 preload 的包与禁用语义由上游过滤 此处只认 hasPreload 标记`() {
        val ordered =
            ExtensionPreloadProvider.preloadablePacks(
                listOf(pack("with-preload"), pack("no-preload", hasPreload = false)),
            )
        assertEquals(listOf("with-preload"), ordered.map { it.id })
    }

    // ---- 缓存失效语义 ----

    @Test
    fun `键未变命中缓存不重读 键变化重算`() {
        var reads = 0
        val read: (ExtensionPackEntity) -> String? = {
            reads += 1
            "js-of-${it.id}@${it.contentHash}"
        }
        val initial =
            ExtensionPreloadProvider.buildCache(
                listOf(pack("a"), pack("b")),
                previous = emptyCache(),
                readPreload = read,
            )
        assertEquals(2, reads)
        assertEquals(listOf("js-of-a@hash-a", "js-of-b@hash-b"), initial.preloads)

        // 同键（同 id 同 hash 有序集）：零 IO 直接复用
        val hit =
            ExtensionPreloadProvider.buildCache(listOf(pack("a"), pack("b")), previous = initial, readPreload = read)
        assertEquals(2, reads)
        assertEquals(initial, hit)

        // 启停（包集变化）：键变化 → 重算
        val afterToggle = ExtensionPreloadProvider.buildCache(listOf(pack("a")), previous = initial, readPreload = read)
        assertEquals(3, reads)
        assertEquals(listOf("js-of-a@hash-a"), afterToggle.preloads)

        // 覆盖安装（hash 变化）：键变化 → 重算并取到新内容
        val afterUpdate =
            ExtensionPreloadProvider.buildCache(
                listOf(pack("a", hash = "hash-a2"), pack("b")),
                previous = initial,
                readPreload = read,
            )
        assertEquals(5, reads)
        assertEquals(listOf("js-of-a@hash-a2", "js-of-b@hash-b"), afterUpdate.preloads)
    }

    @Test
    fun `缓存键随包序变化而变化（同集合不同序不算命中）`() {
        val keyAb = cacheKey(pack("a", loadingOrder = 1), pack("b", loadingOrder = 2))
        val keyBa = cacheKey(pack("a", loadingOrder = 2), pack("b", loadingOrder = 1))
        assertTrue("同集合不同 loadingOrder 的键必须不同", keyAb != keyBa)
    }

    private fun cacheKey(vararg packs: ExtensionPackEntity): List<Pair<String, String>> =
        ExtensionPreloadProvider.buildCache(packs.toList(), emptyCache()) { "" }.key

    private fun emptyCache(): ExtensionPreloadProvider.CacheEntry =
        ExtensionPreloadProvider.CacheEntry(key = emptyList(), preloads = emptyList())

    // ---- onPacksChanged 快照 ----

    @Test
    fun `快照按最近一次包集刷新`() {
        ExtensionPreloadProvider.onPacksChanged(listOf(pack("a"))) { "A" }
        assertEquals(listOf("A"), ExtensionPreloadProvider.snapshot())
        ExtensionPreloadProvider.onPacksChanged(listOf(pack("b"))) { "B" }
        assertEquals(listOf("B"), ExtensionPreloadProvider.snapshot())
        ExtensionPreloadProvider.onPacksChanged(emptyList()) { "X" }
        assertEquals(emptyList<String>(), ExtensionPreloadProvider.snapshot())
    }

    // ---- filesDir 读取（生产 readPreload 实现的纯 IO 部分）----

    @Test
    fun `readPreloadFromDisk 按 manifest 声明读取 preload 文件`() {
        val root = File(System.getProperty("java.io.tmpdir"), "preload-test-${System.nanoTime()}")
        try {
            ExtensionPackStore.extractContent(
                root,
                "demo-ext",
                mapOf(
                    "manifest.json" to """{"id":"demo-ext","name":"x","webviewPreload":"facade.js"}""",
                    "facade.js" to "window.TavernHelper = {};",
                ),
            )
            val source = ExtensionPreloadProvider.readPreloadFromDisk(root, pack("demo-ext"))
            assertEquals("window.TavernHelper = {};", source)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `readPreloadFromDisk 目录损坏时返回 null 不抛错`() {
        val root = File(System.getProperty("java.io.tmpdir"), "preload-test-${System.nanoTime()}")
        try {
            // 包目录不存在
            assertEquals(null, ExtensionPreloadProvider.readPreloadFromDisk(root, pack("ghost")))
            // manifest 坏
            ExtensionPackStore.extractContent(root, "bad", mapOf("manifest.json" to "不是 JSON"))
            assertEquals(null, ExtensionPreloadProvider.readPreloadFromDisk(root, pack("bad")))
            // 声明的 preload 文件缺失
            ExtensionPackStore.extractContent(
                root,
                "missing",
                mapOf("manifest.json" to """{"id":"missing","name":"x","webviewPreload":"gone.js"}"""),
            )
            assertEquals(null, ExtensionPreloadProvider.readPreloadFromDisk(root, pack("missing")))
            // 未声明 preload
            ExtensionPackStore.extractContent(
                root,
                "plain",
                mapOf("manifest.json" to """{"id":"plain","name":"x"}"""),
            )
            assertEquals(null, ExtensionPreloadProvider.readPreloadFromDisk(root, pack("plain")))
        } finally {
            root.deleteRecursively()
        }
    }
}
