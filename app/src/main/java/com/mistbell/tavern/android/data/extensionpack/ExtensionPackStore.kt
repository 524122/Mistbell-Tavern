package com.mistbell.tavern.android.data.extensionpack

import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.rulepack.RulePackManifest
import com.mistbell.tavern.android.data.rulepack.RulePackParser
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 扩展包纯 JVM 生命周期逻辑：内容哈希、覆盖安装落库计划、filesDir 落盘/清理、启用守卫。
 *
 * 为什么单独成 object：Android 仓库壳（Uri 读取 + DAO）无法进 JVM 单测，
 * 把「决策 + 文件系统」收敛到纯函数层（GlobalRegexStore 同款分层），仓库只做编排。
 */
@Suppress("TooManyFunctions") // 哈希/计划/落盘/守卫按职责拆小函数，优于挤进少数大函数（GlobalRegexStore 同型）
object ExtensionPackStore {
    /** filesDir 下扩展包内容根目录（主题包 themes/ 同款目录模型） */
    const val CONTENT_DIR = "extensions"

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    // ---- 内容哈希（覆盖安装比对）----

    /**
     * zip 字节 SHA-256 十六进制。ScriptRuntimeManager 用 Int hashCode 做脚本源码比对，
     * 这里升级为 SHA-256：比对对象是整包字节（含 JS 制品），Int 撞碰撞概率不可忽略。
     * 语义一致：hash 相同=不重载，不同=重载（P2 reconcile 消费同一字段）。
     */
    fun contentHash(zipBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(zipBytes).joinToString("") { "%02x".format(it) }

    // ---- permissionsJson 编解码 ----

    fun encodePermissions(perms: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), perms)

    fun decodePermissions(permissionsJson: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), permissionsJson) }
            .getOrDefault(emptyList())

    // ---- 落库计划（覆盖安装语义）----

    /** persist 模式：新包 / 同 hash 仅刷新元数据 / hash 变化更新内容并重置 */
    enum class PersistMode { FRESH, REFRESH_METADATA, UPDATE_CONTENT }

    data class PersistPlan(
        val mode: PersistMode,
        /** 要 upsert 的实体行（REFRESH_METADATA 时仅元数据变化） */
        val entity: ExtensionPackEntity,
        /** 是否需要重新解压落盘（同 hash 跳过，避免无谓 IO） */
        val extract: Boolean,
    )

    /**
     * 覆盖安装决策（纯函数）：
     * - 新包：enabled/confirmed = 本轮是否已确认（无权限包 UI 直接传 true，确认后立即启用，
     *   与规则包「新包默认启用」一致）；
     * - 同 hash：仅刷新元数据（name/author/version/loadingOrder/installedAt），启停与确认态原样保留；
     * - hash 变化：更新内容文件与全部 manifest 元数据；enabled 保留（老包未被卸载），
     *   但权限集扩大时必须基于对新清单的确认——[confirmedByUser]=false 的防御路径
     *   （未来任何绕过 UI 的自动刷新）强制回未确认态并禁用（未授权不可启用的安全不变量）。
     *   UI 导入路径永远先弹完整新权限清单的确认框，用户「信任并导入」即视为完成再确认。
     */
    fun planPersist(
        old: ExtensionPackEntity?,
        manifest: RulePackManifest,
        contentHash: String,
        confirmedByUser: Boolean,
        now: String,
    ): PersistPlan =
        when {
            old == null -> freshPlan(manifest, contentHash, confirmedByUser, now)
            old.contentHash == contentHash -> refreshPlan(old, manifest, now)
            else -> updatePlan(old, manifest, contentHash, confirmedByUser, now)
        }

    private fun freshPlan(
        manifest: RulePackManifest,
        contentHash: String,
        confirmedByUser: Boolean,
        now: String,
    ): PersistPlan =
        PersistPlan(
            mode = PersistMode.FRESH,
            entity =
                ExtensionPackEntity(
                    id = manifest.id,
                    name = manifest.name,
                    author = manifest.author,
                    version = manifest.version,
                    loadingOrder = manifest.loadingOrder,
                    permissionsJson = encodePermissions(manifest.permissions),
                    hasPreload = manifest.webviewPreload != null,
                    hasHooks = manifest.hostHooks != null,
                    enabled = confirmedByUser,
                    permissionsConfirmed = confirmedByUser || manifest.permissions.isEmpty(),
                    contentHash = contentHash,
                    installedAt = now,
                ),
            extract = true,
        )

    private fun refreshPlan(
        old: ExtensionPackEntity,
        manifest: RulePackManifest,
        now: String,
    ): PersistPlan =
        PersistPlan(
            mode = PersistMode.REFRESH_METADATA,
            entity =
                old.copy(
                    name = manifest.name,
                    author = manifest.author,
                    version = manifest.version,
                    loadingOrder = manifest.loadingOrder,
                    installedAt = now,
                ),
            extract = false,
        )

    private fun updatePlan(
        old: ExtensionPackEntity,
        manifest: RulePackManifest,
        contentHash: String,
        confirmedByUser: Boolean,
        now: String,
    ): PersistPlan {
        val expanded = manifest.permissions.any { it !in decodePermissions(old.permissionsJson) }
        val confirmedNow = confirmedByUser || manifest.permissions.isEmpty()
        val unconfirmedExpansion = expanded && !confirmedNow
        return PersistPlan(
            mode = PersistMode.UPDATE_CONTENT,
            entity =
                ExtensionPackEntity(
                    id = manifest.id,
                    name = manifest.name,
                    author = manifest.author,
                    version = manifest.version,
                    loadingOrder = manifest.loadingOrder,
                    permissionsJson = encodePermissions(manifest.permissions),
                    hasPreload = manifest.webviewPreload != null,
                    hasHooks = manifest.hostHooks != null,
                    enabled = if (unconfirmedExpansion) false else old.enabled,
                    permissionsConfirmed =
                        if (unconfirmedExpansion) false else confirmedNow || old.permissionsConfirmed,
                    contentHash = contentHash,
                    installedAt = now,
                ),
            extract = true,
        )
    }

    // ---- filesDir 落盘 / 清理 ----

    fun packDir(
        filesRoot: File,
        id: String,
    ): File = File(File(filesRoot, CONTENT_DIR), id)

    /**
     * 解压落盘：先写同目录旁的 incoming 暂存、成功后备份换名、最后删备份——
     * 「先写新、后清旧」主题包先例的加强版（扩展包内容只在文件里，DB 不存内容，
     * 中途失败绝不能把旧内容清成空目录）。调用方保证 DB 行在落盘成功后才写。
     */
    fun extractContent(
        filesRoot: File,
        id: String,
        files: Map<String, String>,
    ) {
        require(!id.contains('/') && !id.contains('\\') && id != "." && id != "..") {
            "扩展包非法：id 不能用作目录名（$id）"
        }
        val dir = packDir(filesRoot, id)
        val incoming = File(dir.parentFile, "${dir.name}.incoming")
        val backup = File(dir.parentFile, "${dir.name}.old")
        incoming.deleteRecursively()
        incoming.mkdirs()
        files.forEach { (name, content) ->
            // 解析器已拒绝含 ".." 的条目路径；这里仍按子目录拼装（保留未来多文件结构）
            val target = File(incoming, name)
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
        }
        backup.deleteRecursively()
        if (dir.exists()) dir.renameTo(backup)
        if (!incoming.renameTo(dir)) {
            // 换名失败：把备份还原，旧内容不丢（罕见：同名目录被外部占用等）
            if (backup.exists()) backup.renameTo(dir)
            error("扩展包落盘失败：无法写入 ${dir.absolutePath}")
        }
        backup.deleteRecursively()
    }

    /** 删行配套：递归删 filesDir/extensions/<id>（含可能的 .old/.incoming 残留） */
    fun deleteContent(
        filesRoot: File,
        id: String,
    ) {
        require(!id.contains('/') && !id.contains('\\') && id != "." && id != "..") {
            "扩展包非法：id 不能用作目录名（$id）"
        }
        val dir = packDir(filesRoot, id)
        dir.deleteRecursively()
        File(dir.parentFile, "${dir.name}.old").deleteRecursively()
        File(dir.parentFile, "${dir.name}.incoming").deleteRecursively()
    }

    // ---- 启用守卫 ----

    /** 未确认权限的包不允许启用（UI 禁开关之外的数据层兜底，仓库 setEnabled 调用） */
    fun requireEnableAllowed(entity: ExtensionPackEntity) {
        check(entity.permissionsConfirmed) {
            "扩展包「${entity.name}」尚未确认权限清单，不能启用（请重新导入并确认权限）"
        }
    }

    // ---- 预览辅助 ----

    /** 是否需要权限/脚本确认：声明了权限或携带任一 JS 制品（preload/hooks/规则包 script） */
    fun needsConfirmation(bundle: RulePackParser.Bundle): Boolean =
        bundle.manifest.permissions.isNotEmpty() ||
            bundle.content.webviewPreloadSource != null ||
            bundle.content.hostHooksSource != null ||
            bundle.content.scriptSource != null
}
