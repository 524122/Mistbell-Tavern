package com.mistbell.tavern.android.data.repository

import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.extensionpack.BuiltinPackSeeder
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
 * 扩展包仓库：导入（两段式 preview→persist）/启停/删除/覆盖重载/内置包种子编排。
 *
 * 与 RulePackRepository（Context 单例取库、不可 JVM 测）的有意偏差：依赖收窄为
 * [filesDir] + [daoProvider] + [transactionRunner] 三个注入点，zip 字节由调用方读好传入
 * （[previewFromBytes]）——解析/落库决策/文件系统全链路可进 JVM 单测（未确认不可启用、
 * 覆盖安装 hash 比对、删除清理目录、提供者互斥、种子计划都在测试覆盖内）；
 * Uri→字节的 contentResolver 读取留在 ViewModel 装配层。
 *
 * [transactionRunner] 默认直跑（JVM 测试无 Room）；生产传 `db.withTransaction`——
 * 启用/导入/种子时「停用同 provides 启用包 + 写入本包」必须原子（互斥不变量）。
 */
class ExtensionPackRepository(
    private val filesDir: File,
    private val daoProvider: () -> ExtensionPackDao,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block -> block() },
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

    /** persist 结果：包 id/名 + 非致命警告 + 落库模式（UI 提示用）+ 被互斥停用的包名 */
    data class ImportResult(
        val id: String,
        val name: String,
        val warnings: List<String>,
        val mode: ExtensionPackStore.PersistMode,
        /** 因提供者互斥被自动停用的扩展包名（Snackbar 说明「已停用提供相同能力的扩展：X」） */
        val disabledCompetitors: List<String> = emptyList(),
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
     *
     * 新包启用态（或覆盖安装保留启用态）时在同一事务内执行提供者互斥：声明相同
     * provides 名的启用包被自动停用（[ImportResult.disabledCompetitors] 上报 UI）。
     * [isBuiltin] 仅内置包种子路径传 true（官方包随产品发布等同用户已同意）。
     */
    suspend fun persist(
        preview: ExtensionPreview,
        confirmedByUser: Boolean,
        isBuiltin: Boolean = false,
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
            val entity = if (isBuiltin) plan.entity.copy(isBuiltin = true) else plan.entity
            var disabledCompetitors: List<ExtensionPackEntity> = emptyList()
            transactionRunner {
                if (plan.extract) {
                    ExtensionPackStore.extractContent(filesDir, manifest.id, preview.bundle.files)
                }
                dao.upsert(entity)
                if (entity.enabled) {
                    disabledCompetitors =
                        ExtensionPackStore.competingProviders(entity, dao.getEnabled())
                    disabledCompetitors.forEach { competitor -> dao.setEnabled(competitor.id, false) }
                }
            }
            ImportResult(
                id = manifest.id,
                name = manifest.name,
                warnings = preview.bundle.warnings,
                mode = plan.mode,
                disabledCompetitors = disabledCompetitors.map { it.name },
            )
        }

    /**
     * 内置官方扩展包种子（启动期静默执行）：与 preview 同一套解析校验 + planPersist——
     * 无行/hash 变化才落库落盘（is_builtin=1、enabled=true、permissions_confirmed=true），
     * 同 hash 跳过（保留用户停用态）。删除后下次启动重播种（它是产品功能）。
     * 返回 null = 无需种子（已存在同 hash 行）。
     */
    suspend fun seedBuiltinPack(zipBytes: ByteArray): ImportResult? =
        withContext(Dispatchers.IO) {
            val preview = previewFromBytes(zipBytes)
            val old = dao.getById(preview.bundle.manifest.id)
            when (BuiltinPackSeeder.decide(old, preview.contentHash)) {
                BuiltinPackSeeder.SeedAction.SKIP -> null
                BuiltinPackSeeder.SeedAction.INSTALL, BuiltinPackSeeder.SeedAction.REFRESH ->
                    persist(preview, confirmedByUser = true, isBuiltin = true)
            }
        }

    /**
     * 启停切换。启用走守卫：未确认权限的包在此拒绝（UI 已禁开关，数据层兜底防绕过）；
     * 禁用放行（未授权包本来就处于禁用态，无需拦截）。
     * 启用时在同一事务内执行提供者互斥：返回被自动停用的同 provides 包名（UI Snackbar 说明）。
     */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val entity = dao.getById(id) ?: throw IllegalArgumentException("扩展包不存在（id=$id）")
            if (enabled) {
                ExtensionPackStore.requireEnableAllowed(entity)
            }
            var disabledCompetitors: List<ExtensionPackEntity> = emptyList()
            transactionRunner {
                if (enabled) {
                    disabledCompetitors =
                        ExtensionPackStore.competingProviders(entity, dao.getEnabled())
                    disabledCompetitors.forEach { competitor -> dao.setEnabled(competitor.id, false) }
                }
                dao.setEnabled(id, enabled)
            }
            disabledCompetitors.map { it.name }
        }

    /** 删行 + 递归删 filesDir/extensions/<id>（DB 与目录一并清理；内置包删除后重启种子重播种） */
    suspend fun deletePack(id: String) =
        withContext(Dispatchers.IO) {
            dao.deleteById(id)
            ExtensionPackStore.deleteContent(filesDir, id)
        }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** 生产装配：DAO 与 Room 事务均走应用容器（ViewModel/种子侧调用） */
        fun forApplication(filesDir: File): ExtensionPackRepository =
            ExtensionPackRepository(
                filesDir = filesDir,
                daoProvider = { TavernApplication.instance.container.database.extensionPackDao() },
                transactionRunner = { block ->
                    TavernApplication.instance.container.database.withTransaction { block() }
                },
            )
    }
}
