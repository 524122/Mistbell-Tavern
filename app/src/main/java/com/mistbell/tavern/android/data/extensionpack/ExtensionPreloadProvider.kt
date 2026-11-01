package com.mistbell.tavern.android.data.extensionpack

import android.util.Log
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.rulepack.RulePackManifest
import com.mistbell.tavern.android.data.rulepack.RulePackParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扩展包 WebView 预注入装配器（P2 注入链的数据驱动侧，EXTENSION_PLATFORM_DESIGN.md §5.1）。
 *
 * 职责：观察启用中的扩展包（Room Flow），按 loadingOrder 升序（同序按 id 稳定排序）产出
 * 各包 webviewPreload JS 源码的有序列表；HtmlWebViewFactory.create 在文档组装期同步取
 * [snapshot]（预注入发生在 loadDataWithBaseURL 的字符串装配里，不能异步）。
 *
 * 缓存：结果按「启用 preload 包 (id, contentHash) 有序列表」做内存缓存——包启停/覆盖安装
 * （Room Flow 发射）即失效重算，同 key 直接命中（WebView 装配频率高，重读文件不可接受）。
 *
 * 排序/缓存/文件读取决策拆为纯函数（readPreload 注入），JVM 单测覆盖；Room 收集是薄壳。
 */
object ExtensionPreloadProvider {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    /** 缓存条目：键 = 启用 preload 包的有序 (id, contentHash) 列表；值 = 有序 preload JS */
    data class CacheEntry(
        val key: List<Pair<String, String>>,
        val preloads: List<String>,
    )

    @Volatile
    private var cache: CacheEntry = CacheEntry(key = emptyList(), preloads = emptyList())

    private val started = AtomicBoolean(false)

    /**
     * WebView 装配同步取：当前启用扩展包的有序 preload JS 快照。
     *
     * 线程契约（管理页 ANR 防御）：**只读内存、永不触发重算**——本方法被
     * HtmlWebViewFactory.create 在主线程（Compose AndroidView factory）调用，
     * 缓存命中与否都只是 volatile 读；文件 IO 与 manifest 解析只发生在 [start]
     * 的 IO 协程收集侧（onPacksChanged），主线程绝不进入该路径。
     * 缓存未就绪（冷启动首帧）返回空列表——宁缺一帧门面，不可阻塞装配；
     * 包集 Flow 发射后新装配的 WebView 自然补上门面。
     */
    fun snapshot(): List<String> = cache.preloads

    /**
     * 排序（纯函数）：loadingOrder 升序，同序按 id 稳定排序（与注入串顺序契约一致）；
     * 只保留声明了 webviewPreload 的包（hasPreload=false 不参与注入串也不进缓存键）。
     */
    internal fun preloadablePacks(packs: List<ExtensionPackEntity>): List<ExtensionPackEntity> =
        packs
            .filter { it.hasPreload }
            .sortedWith(compareBy({ it.loadingOrder }, { it.id }))

    /**
     * 缓存决策（纯函数）：键未变直接复用旧条目（零文件 IO）；键变化按序重读各包 preload。
     * 单包 hash 变化即整键失效——按包做增量缓存属于过度设计（包数少、重读廉价）。
     */
    internal fun buildCache(
        packs: List<ExtensionPackEntity>,
        previous: CacheEntry,
        readPreload: (ExtensionPackEntity) -> String?,
    ): CacheEntry {
        val ordered = preloadablePacks(packs)
        val key = ordered.map { it.id to it.contentHash }
        if (key == previous.key) return previous
        return CacheEntry(key = key, preloads = ordered.mapNotNull(readPreload))
    }

    /**
     * 从 filesDir 读单包 preload 源码（注入 readPreload 的生产实现）：
     * 包目录内 manifest.json 保存了 webviewPreload 文件名（extractContent 落盘全量条目），
     * 缺文件/解析失败/未声明一律返回 null（跳过该包，绝不让坏包拖垮 WebView 装配）。
     */
    internal fun readPreloadFromDisk(
        filesRoot: File,
        pack: ExtensionPackEntity,
    ): String? =
        runCatching {
            val dir = ExtensionPackStore.packDir(filesRoot, pack.id)
            val manifest =
                json.decodeFromString<RulePackManifest>(
                    File(dir, RulePackParser.MANIFEST_FILE).readText(),
                )
            manifest.webviewPreload
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> File(dir, name).takeIf(File::isFile)?.readText() }
        }.onFailure {
            Log.w(TAG, "扩展包「${pack.id}」preload 读取失败，本包跳过注入：${it.message}")
        }.getOrNull()

    /**
     * 启动常驻收集（幂等）：应用容器在 registerBuiltinExtensions 时调用——种子落库、
     * 管理页启停、覆盖安装都会经 Room Flow 推到这里刷新缓存。
     */
    fun start(
        filesRoot: File,
        daoProvider: () -> ExtensionPackDao,
    ) {
        if (!started.compareAndSet(false, true)) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            daoProvider().observeEnabled().collect { packs ->
                onPacksChanged(packs) { pack -> readPreloadFromDisk(filesRoot, pack) }
            }
        }
    }

    /**
     * Flow 发射入口（可测）：键变化才重算，同键零 IO。
     * 重算（含 filesDir 文件读 + manifest 解析）只允许发生在 IO 侧（[start] 的收集协程
     * 或测试调用）——synchronized 监视器若被主线程进入会与 IO 重算互相阻塞，线程契约见 [snapshot]。
     */
    @Synchronized
    internal fun onPacksChanged(
        packs: List<ExtensionPackEntity>,
        readPreload: (ExtensionPackEntity) -> String?,
    ) {
        cache = buildCache(packs, cache, readPreload)
    }

    private const val TAG = "ExtensionPreload"
}
