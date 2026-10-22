package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.RulePackEntity
import com.mistbell.tavern.android.data.rulepack.RulePackContent
import com.mistbell.tavern.android.data.rulepack.RulePackMacro
import com.mistbell.tavern.android.data.rulepack.RulePackManifest
import com.mistbell.tavern.android.data.rulepack.RulePackParser
import com.mistbell.tavern.android.data.rulepack.RulePackPrompt
import com.mistbell.tavern.android.data.rulepack.RulePackQuickReply
import com.mistbell.tavern.android.data.rulepack.RulePackRegexRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 规则包仓库：导入/导出 zip、启停、增删查。
 *
 * 与主题包同安全模型：纯数据、无代码执行；zip 解析全部在 [RulePackParser]（纯 JVM）。
 */
class RulePackRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database

    companion object {
        private const val EXPORT_SCRIPT_FILE = "script.js"
    }

    private val contentJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun observePacks(): Flow<List<RulePackEntity>> = db.rulePackDao().observeAll()

    /** 导入结果：包 id + 非致命警告 + 是否含可执行脚本（UI 据此先弹确认再落库） */
    data class ImportResult(
        val id: String,
        val name: String,
        val warnings: List<String>,
        val hasScript: Boolean = false,
    )

    /**
     * 预解析 zip（不落库）：供 UI 在含脚本时先弹确认。[parseZip] 成功返回 Bundle。
     */
    fun previewFromUri(uri: Uri): RulePackParser.Bundle {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("规则包导入失败：无法打开所选文件")
        return RulePackParser.parseZip(bytes)
    }

    /**
     * 从 zip 导入规则包。同 id 覆盖安装（沿用主题包"先写新"语义，实体单行无残留文件问题）。
     * 坏包抛 IllegalArgumentException（中文消息）。
     */
    suspend fun importFromUri(uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val bundle = previewFromUri(uri)
            persist(bundle)
        }

    /** 落库已解析的包（previewFromUri 之后、用户确认脚本风险后的第二步） */
    suspend fun persist(bundle: RulePackParser.Bundle): ImportResult =
        withContext(Dispatchers.IO) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            db.rulePackDao().upsert(
                RulePackEntity(
                    id = bundle.manifest.id,
                    name = bundle.manifest.name,
                    author = bundle.manifest.author,
                    version = bundle.manifest.version,
                    loadingOrder = bundle.manifest.loadingOrder,
                    // 覆盖安装保留旧启用状态；新包默认启用
                    enabled = db.rulePackDao().getById(bundle.manifest.id)?.enabled ?: true,
                    contentJson = contentJson.encodeToString(RulePackContent.serializer(), bundle.content),
                    createdAt = now,
                ),
            )
            ImportResult(
                id = bundle.manifest.id,
                name = bundle.manifest.name,
                warnings = bundle.warnings,
                hasScript = bundle.content.scriptSource != null,
            )
        }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        db.rulePackDao().setEnabled(id, enabled)
    }

    suspend fun deletePack(id: String) =
        withContext(Dispatchers.IO) {
            db.rulePackDao().deleteById(id)
        }

    /** 重建 zip 到 cacheDir/rulepacks/<id>.zip 供分享，不存在则抛 IllegalArgumentException */
    suspend fun exportPack(id: String): java.io.File =
        withContext(Dispatchers.IO) {
            val pack =
                db.rulePackDao().getById(id)
                    ?: throw IllegalArgumentException("导出失败：规则包不存在（id=$id）")
            val content = contentJson.decodeFromString<RulePackContent>(pack.contentJson)
            val manifest =
                RulePackManifest(
                    id = pack.id,
                    name = pack.name,
                    author = pack.author,
                    version = pack.version,
                    loadingOrder = pack.loadingOrder,
                    // 脚本文件名不单独存储：导出统一规范化为 script.js
                    script = if (content.scriptSource != null) EXPORT_SCRIPT_FILE else null,
                )
            val outFile = java.io.File(java.io.File(context.cacheDir, "rulepacks"), "$id.zip")
            outFile.parentFile?.mkdirs()
            ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                writeZipEntry(zip, RulePackParser.MANIFEST_FILE, encode(RulePackManifest.serializer(), manifest))
                // 三个内容节为可选文件：空节不产出（与导入侧"文件不存在=空表"对称）
                if (content.prompts.isNotEmpty()) {
                    val text = encodeList(RulePackPrompt.serializer(), content.prompts)
                    writeZipEntry(zip, RulePackParser.PROMPTS_FILE, text)
                }
                if (content.regex.isNotEmpty()) {
                    val text = encodeList(RulePackRegexRule.serializer(), content.regex)
                    writeZipEntry(zip, RulePackParser.REGEX_FILE, text)
                }
                if (content.macros.isNotEmpty()) {
                    val text = encodeList(RulePackMacro.serializer(), content.macros)
                    writeZipEntry(zip, RulePackParser.MACROS_FILE, text)
                }
                if (content.quickReplies.isNotEmpty()) {
                    val text = encodeList(RulePackQuickReply.serializer(), content.quickReplies)
                    writeZipEntry(zip, RulePackParser.QUICK_REPLIES_FILE, text)
                }
                // 脚本源码对称写回（导出统一规范化为 script.js）
                if (content.scriptSource != null) {
                    writeZipEntry(zip, EXPORT_SCRIPT_FILE, content.scriptSource)
                }
            }
            outFile
        }

    private fun writeZipEntry(
        zip: ZipOutputStream,
        fileName: String,
        text: String,
    ) {
        zip.putNextEntry(ZipEntry(fileName))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): String = contentJson.encodeToString(serializer, value)

    private fun <T> encodeList(
        serializer: KSerializer<T>,
        list: List<T>,
    ): String = contentJson.encodeToString(ListSerializer(serializer), list)
}

/** 启用包的解析后视图（钩子链路消费） */
data class EnabledRulePack(
    val id: String,
    val name: String,
    val loadingOrder: Int,
    val content: RulePackContent,
)

/**
 * 规则包来源抽象：钩子链路经此读缓存，测试可注入假实现（不经 Room）。
 *
 * packs 为 null 表示"首次加载未完成"——挂起读取方（PromptInterceptor/ReplyPostProcessor）
 * 应 await 首个非 null 值，否则冷启动后首轮生成会静默丢规则包内容（实测复现）；
 * 同步读取方（MacroProvider.resolve）退化为 null 按空表处理。
 */
interface RulePackSource {
    val packs: StateFlow<List<EnabledRulePack>?>
}

/** 挂起等待首个真实加载结果（null=未加载，等待而非按空表放行） */
suspend fun RulePackSource.awaitPacks(): List<EnabledRulePack> = packs.filterNotNull().first()

/** 同步读（宏解析等不能挂起的场合）：未加载完成按空表处理 */
fun RulePackSource.currentPacks(): List<EnabledRulePack> = packs.value ?: emptyList()

/**
 * 启用规则包缓存（全进程唯一，挂 AppContainer）：持续收集 Room Flow 并解析 contentJson，
 * 三个钩子统一读内存，不每轮查库。AppContainer 注册扩展时即触碰创建（预热），
 * 加上 awaitPacks 的确定性等待，双保险消除冷启动竞态。
 * 单条 content 解析失败跳过该包（导入期已校验，理论上不会发生；兜底防手改库）。
 */
class EnabledRulePacksCache(
    db: AppDatabase,
    scope: CoroutineScope,
) : RulePackSource {
    private val contentJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override val packs: StateFlow<List<EnabledRulePack>?> =
        db.rulePackDao()
            .observeEnabled()
            .map { entities ->
                entities.mapNotNull { entity ->
                    runCatching {
                        EnabledRulePack(
                            id = entity.id,
                            name = entity.name,
                            loadingOrder = entity.loadingOrder,
                            content = contentJson.decodeFromString<RulePackContent>(entity.contentJson),
                        )
                    }.getOrNull()
                }
            }.stateIn(scope, SharingStarted.Eagerly, null)
}
