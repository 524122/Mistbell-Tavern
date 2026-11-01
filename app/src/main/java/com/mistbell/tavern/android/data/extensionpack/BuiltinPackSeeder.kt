package com.mistbell.tavern.android.data.extensionpack

import android.content.Context
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 官方内置扩展包种子（P2，EXTENSION_PLATFORM_DESIGN.md §5 门面预注入的制品来源）。
 *
 * 内置包源码以原始文件入库（assets/extensions/<id>/manifest.json + facade.js，不造 zip 二进制），
 * 应用启动时由 AppContainer 种子：读 assets → 确定性 zip（见 [zipOf]）→ 复用 P1 解析管线
 * （preview 同款校验 + planPersist）→ 静默落库落盘（is_builtin=1、enabled=true、
 * permissions_confirmed=true——官方包随产品发布等同用户已同意，第三方包绝不走此路径）。
 *
 * 种子语义：无行或 contentHash 变化才落库；删除后下次启动重播种（它是产品功能）；
 * 用户停用则保留停用态（仅 hash 变化时刷新内容）。
 *
 * 解析与决策函数全部纯 JVM 可测；assets 读取是生产装配的独立小函数。
 */
object BuiltinPackSeeder {
    /** 内置官方扩展包清单：id ↔ assets 目录（新增内置包在此登记） */
    data class Source(
        val id: String,
        val assetDir: String,
    )

    val SOURCES: List<Source> =
        listOf(
            Source("tavernhelper-compat", "extensions/tavernhelper-compat"),
        )

    /** 种子决策：无行 → INSTALL；hash 变化 → REFRESH（保留启停态）；同 hash → SKIP */
    enum class SeedAction { INSTALL, REFRESH, SKIP }

    fun decide(
        old: ExtensionPackEntity?,
        contentHash: String,
    ): SeedAction =
        when {
            old == null -> SeedAction.INSTALL
            old.contentHash != contentHash -> SeedAction.REFRESH
            else -> SeedAction.SKIP
        }

    /**
     * 确定性 zip：条目按名字排序、时间戳固定为 0——同内容字节稳定，
     * 种子的 contentHash 跨进程/跨重启比对才成立（否则每次启动 hash 都变，反复重解压）。
     * 产物直接喂 [com.mistbell.tavern.android.data.rulepack.RulePackParser.parseZip]
     * （manifest/权限/provides/JS 制品全套校验零特判复用）。
     */
    fun zipOf(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.toSortedMap().forEach { (name, content) ->
                val entry = ZipEntry(name)
                entry.time = 0L
                entry.comment = null
                entry.extra = null
                zip.putNextEntry(entry)
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 读取 assets 目录下全部文件为 name→UTF-8 内容（生产装配；JVM 测试直接构造 map） */
    fun readAssets(
        context: Context,
        assetDir: String,
    ): Map<String, String> {
        val names = context.assets.list(assetDir) ?: return emptyMap()
        val files = LinkedHashMap<String, String>()
        names.forEach { name ->
            context.assets.open("$assetDir/$name").bufferedReader(Charsets.UTF_8).use { reader ->
                files[name] = reader.readText()
            }
        }
        return files
    }

    /** zip 字节 → 内容哈希（复用扩展包仓库同款 SHA-256，种子与导入比对同一把尺子） */
    fun contentHash(zipBytes: ByteArray): String = ExtensionPackStore.contentHash(zipBytes)
}
