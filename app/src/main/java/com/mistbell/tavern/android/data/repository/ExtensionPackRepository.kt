package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.extensionpack.ExtensionPackStore
import com.mistbell.tavern.android.data.extensionpack.PermissionCatalog
import com.mistbell.tavern.android.data.local.dao.ExtensionPackDao
import com.mistbell.tavern.android.data.local.entity.ExtensionPackEntity
import com.mistbell.tavern.android.data.rulepack.RulePackParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扩展包仓库：导入（两段式 preview→persist）/启停/删除/覆盖重载编排。
 *
 * 与 RulePackRepository（Context 单例取库、不可 JVM 测）的有意偏差：依赖收窄为
 * [filesDir] + [daoProvider] 两个注入点，zip 字节由调用方读好传入（[previewFromBytes]）——
 * 解析/落库决策/文件系统全链路可进 JVM 单测（未确认不可启用、覆盖安装 hash 比对、
 * 删除清理目录都在测试覆盖内）；Uri→字节的 contentResolver 读取留在 ViewModel 装配层。
 */
class ExtensionPackRepository(
    private val filesDir: File,
    private val daoProvider: () -> ExtensionPackDao,
) {
    private val dao get() = daoProvider()

    /** 两段式导入的第一段产物：解析结果 + 内容哈希 + 已附中文元数据的权限清单 */
    data class ExtensionPreview(
        val bundle: RulePackParser.Bundle,
        val contentHash: String,
        val permissions: List<PermissionCatalog.Entry>,
        /** 是否需要用户确认（声明权限或携带任一 JS 制品） */
        val needsConfirmation: Boolean,
    )

    /** persist 结果：包 id/名 + 非致命警告 + 落库模式（UI 提示用） */
    data class ImportResult(
        val id: String,
        val name: String,
        val warnings: List<String>,
        val mode: ExtensionPackStore.PersistMode,
    )

    fun observePacks(): Flow<List<ExtensionPackEntity>> = dao.observeAll()

    /**
     * 预解析 zip 字节（不落库不落盘）：manifest 校验、权限目录校验、JS 制品存在性/大小校验
     * 全在解析器完成，坏包抛 IllegalArgumentException（中文消息，UI 直接展示）。
     */
    fun previewFromBytes(zipBytes: ByteArray): ExtensionPreview {
        val bundle = RulePackParser.parseZip(zipBytes)
        return ExtensionPreview(
            bundle = bundle,
            contentHash = ExtensionPackStore.contentHash(zipBytes),
            permissions = PermissionCatalog.resolveAll(bundle.manifest.permissions),
            needsConfirmation = ExtensionPackStore.needsConfirmation(bundle),
        )
    }

    /**
     * 落库已预览的包（用户确认权限后的第二步）：先解压落盘、后写 DB 行——
     * 行永远不引用尚未写完的目录；落盘成功而 DB 写失败只会留下无行孤儿目录，
     * 下次同 id 导入整体换名覆盖，无一致性问题。同 hash 覆盖安装跳过解压。
     */
    suspend fun persist(
        preview: ExtensionPreview,
        confirmedByUser: Boolean,
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val manifest = preview.bundle.manifest
            val old = dao.getById(manifest.id)
            val plan =
                ExtensionPackStore.planPersist(
                    old = old,
                    manifest = manifest,
                    contentHash = preview.contentHash,
                    confirmedByUser = confirmedByUser,
                    now = now(),
                )
            if (plan.extract) {
                ExtensionPackStore.extractContent(filesDir, manifest.id, preview.bundle.files)
            }
            dao.upsert(plan.entity)
            ImportResult(
                id = manifest.id,
                name = manifest.name,
                warnings = preview.bundle.warnings,
                mode = plan.mode,
            )
        }

    /**
     * 启停切换。启用走守卫：未确认权限的包在此拒绝（UI 已禁开关，数据层兜底防绕过）；
     * 禁用放行（未授权包本来就处于禁用态，无需拦截）。
     */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (enabled) {
            val entity = dao.getById(id) ?: throw IllegalArgumentException("扩展包不存在（id=$id）")
            ExtensionPackStore.requireEnableAllowed(entity)
        }
        dao.setEnabled(id, enabled)
    }

    /** 删行 + 递归删 filesDir/extensions/<id>（DB 与目录一并清理） */
    suspend fun deletePack(id: String) =
        withContext(Dispatchers.IO) {
            dao.deleteById(id)
            ExtensionPackStore.deleteContent(filesDir, id)
        }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** 生产装配：DAO 走应用容器（ViewModel 侧调用） */
        fun forApplication(filesDir: File): ExtensionPackRepository =
            ExtensionPackRepository(
                filesDir = filesDir,
                daoProvider = { TavernApplication.instance.container.database.extensionPackDao() },
            )
    }
}
