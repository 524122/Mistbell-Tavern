package com.mistbell.tavern.android.data.extensionpack

import android.util.Log
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网络白名单数据驱动侧（P3，EXTENSION_PLATFORM_DESIGN.md §4）：生效域 =
 * 基础集（编译期产品域，HtmlUiSandbox 持有）∪ 已启用扩展包声明且已授权的 `network:<host>`。
 *
 * 与 ExtensionPreloadProvider 同款「Flow 收集 + 快照只读内存」模式：Room Flow 在 IO 协程
 * 收集（包启停/覆盖安装/删除即失效重算，纯内存解码、无文件 IO），WebView 拦截线程与
 * CSP 装配（主线程）只读 [extensionHosts] 快照——**主线程永不阻塞**。
 *
 * 授权语义：白名单只认 extension_packs.permissions_json 里的 `network:<host>` 声明，
 * 而该清单只在导入/覆盖安装时经 PermissionCatalog 校验 + 用户确认后落库（P1 管理平面）——
 * 「声明且启用」即「已授权」。
 */
object NetworkAllowlistProvider {
    @Volatile
    private var extensionHosts: Set<String> = emptySet()

    private val started = AtomicBoolean(false)

    /**
     * 当前启用扩展包声明的已授权网络域快照（只读内存；CSP 装配与请求拦截共用）。
     * 未启动/无启用包返回空集——基础域不受影响，扩展域宁缺勿滥。
     */
    fun extensionHosts(): Set<String> = extensionHosts

    /**
     * 计算启用包集合的网络域（纯函数，JVM 单测锁定）：只认 enabled 包的
     * network:<host> 声明；host 小写归一（CSP/拦截比较均为小写），去重，
     * 超过硬上限截断并告警（CSP 串长度防御；正常用量个位数）。
     */
    internal fun computeExtensionHosts(packs: List<ExtensionPackEntity>): Set<String> {
        val hosts =
            packs
                .asSequence()
                .filter { it.enabled }
                .flatMap { ExtensionPackStore.decodePermissions(it.permissionsJson).asSequence() }
                .mapNotNull { raw ->
                    raw.takeIf { it.startsWith(PermissionCatalog.NETWORK_PREFIX) }
                        ?.removePrefix(PermissionCatalog.NETWORK_PREFIX)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                .map { it.lowercase() }
                .toSet()
        if (hosts.size > MAX_HOSTS) {
            Log.w(TAG, "扩展包网络域数量超过 $MAX_HOSTS，截断（声明 ${hosts.size} 个）")
        }
        return hosts.take(MAX_HOSTS).toSet()
    }

    /** Flow 发射入口（可测）：快照整体替换（volatile 写，读者要么见旧集要么见新集）。 */
    internal fun onPacksChanged(packs: List<ExtensionPackEntity>) {
        extensionHosts = computeExtensionHosts(packs)
    }

    /**
     * 启动常驻收集（幂等）：应用容器在 registerBuiltinExtensions 时调用（与
     * ExtensionPreloadProvider.start 并排）。
     */
    fun start(daoProvider: () -> ExtensionPackDao) {
        if (!started.compareAndSet(false, true)) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            daoProvider().observeEnabled().collect { packs -> onPacksChanged(packs) }
        }
    }

    private const val TAG = "NetworkAllowlist"

    /** CSP 串长度防御：扩展域硬上限（超出截断并告警；正常用量个位数） */
    private const val MAX_HOSTS = 64
}
