package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.theme.ThemeColors
import com.mistbell.tavern.android.data.theme.ThemeSupport
import com.mistbell.tavern.android.data.theme.ThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 主题包 manifest（manifest.json）；internal 供 JVM 单测校验示例包 */
@Serializable
internal data class ThemeManifest(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "1",
    val capabilities: String = "cosmetic",
    // 目标模板版本（THEME_FRAMEWORK_DESIGN.md §3.2）：与引擎支持版本不匹配的包拒绝启用
    val templateVersion: Int = 1,
)

/**
 * 引擎可用的布局主题包（M4）：[ThemePackRepository.observeResolvedLayoutPack] 命中产物。
 * 纯数据（无 Android 类型），宿主据此构建 PanelRenderer 请求；未命中/条件不满足 → null = 引擎
 * 不激活，消息内容走 legacy 渲染。
 */
data class ResolvedLayoutPack(
    val packId: String,
    /** zip 根 template.html（导入时落 filesDir/themes/<id>/） */
    val templateHtml: String,
    /** zip 根 style.css（可选；缺省 null = 只用内置默认样式表级联） */
    val styleCss: String?,
    /** 包声明的目标模板版本（引擎只激活受支持版本） */
    val templateVersion: Int,
    /** --mt-* token（浅色模式取值） */
    val themeVarsLight: Map<String, String>,
    /** --mt-* token（深色模式取值；dark 覆盖块逐位覆盖 light） */
    val themeVarsDark: Map<String, String>,
)

/** capabilities 取值：layout = 包携带 template.html/style.css，走原生渲染引擎 */
const val THEME_CAPABILITY_LAYOUT = "layout"

/** 引擎支持的模板版本（v0 冻结为 1，设计文档 §4.5；不支持的版本拒绝启用） */
const val THEME_SUPPORTED_TEMPLATE_VERSION = 1

/**
 * tokens → --mt-* 变量（纯函数，设计文档 §5.4 token 桥）。10 个规范变量只注入已设置的色位；
 * 返回 (light, dark) 两套：dark 为 tokens.dark 覆盖块逐位覆盖 light 的合并结果（缺省同 light），
 * 与 ThemeTokens.resolved(isDark) 语义对齐——样式表无需感知模式，宿主按当前深浅取一套注入。
 */
internal fun layoutThemeVars(tokens: ThemeTokens): Pair<Map<String, String>, Map<String, String>> {
    fun varsOf(c: ThemeColors): Map<String, String> {
        val candidates: List<Pair<String, String?>> =
            listOf(
                "--mt-primary" to c.primary,
                "--mt-background" to c.background,
                "--mt-surface" to c.surface,
                "--mt-surface-variant" to c.surfaceVariant,
                "--mt-user-bubble" to c.userBubble,
                "--mt-assistant-bubble" to c.assistantBubble,
                "--mt-on-user-bubble" to c.onUserBubble,
                "--mt-on-assistant-bubble" to c.onAssistantBubble,
                "--mt-on-background" to c.onBackground,
                "--mt-on-surface" to c.onSurface,
            )
        // 未设置的色位不注入该变量（var 回退值语义交由样式表自行处理，设计文档 §5.4）
        return candidates.fold(emptyMap()) { acc, (name, value) ->
            if (value == null) acc else acc + (name to value)
        }
    }
    // dark 合并语义与 ThemeTokens.resolved(isDark) 对齐：dark 非空位逐位覆盖 light（缺省同 light）
    val light = varsOf(tokens.colors)
    val dark = light + (tokens.dark?.let(::varsOf) ?: emptyMap())
    return light to dark
}

/**
 * 主题包仓库：导入/导出 zip、增删查、应用链 tokens 解析。
 * 皮肤级：纯数据 tokens，无代码执行。
 */
@Suppress("TooManyFunctions") // 导入/导出/解析链按职责各一小函数，拆分优先于合并
class ThemePackRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database

    private val manifestJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp")

    private companion object {
        /** zip 根模板/样式文件名（THEME_FRAMEWORK_DESIGN.md §3.1） */
        const val TEMPLATE_FILE = "template.html"
        const val STYLE_FILE = "style.css"
    }

    fun observePacks(): Flow<List<ThemePackEntity>> = db.themePackDao().observeAll()

    fun observeActiveThemeId(): Flow<String?> = db.settingsDao().observeValue("active_theme_id")

    /** null/空串 = 恢复默认 */
    suspend fun setActiveTheme(id: String?) =
        withContext(Dispatchers.IO) {
            val value = id?.trim().orEmpty()
            db.settingsDao().upsert(SettingsEntity("active_theme_id", value))
        }

    /**
     * 从 zip 导入主题包，返回主题 id。
     * zip 结构: manifest.json / theme.json / 背景图放 assets/；zip 根可选 template.html/style.css
     * （THEME_FRAMEWORK_DESIGN.md §3.1：capabilities=layout 时 template.html 必须存在，否则拒绝）。
     * 同 id 覆盖安装；Entry 名含 ".." 一律拒绝（防路径穿越）。
     * 坏包抛 IllegalArgumentException（中文消息）。
     */
    suspend fun importFromUri(uri: Uri): String =
        withContext(Dispatchers.IO) {
            var manifest: ThemeManifest? = null
            var themeJson: String? = null
            var templateHtml: String? = null
            var styleCss: String? = null
            var backgroundName: String? = null
            val backgroundBytes = mutableListOf<Pair<String, ByteArray>>()

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        if (entry == null) throw IllegalArgumentException("主题包无效：zip 内没有任何文件")
                        while (entry != null) {
                            val name = entry!!.name
                            if (name.split('/').any { it == ".." }) throw IllegalArgumentException("主题包非法：条目路径包含 \"..\"（疑似路径穿越）")
                            if (!entry!!.isDirectory) {
                                val content = zip.readBytes()
                                when {
                                    name == "manifest.json" ->
                                        manifest = parseManifest(content.toString(Charsets.UTF_8))
                                    name == "theme.json" ->
                                        themeJson = content.toString(Charsets.UTF_8)
                                    name == "template.html" ->
                                        templateHtml = content.toString(Charsets.UTF_8)
                                    name == "style.css" ->
                                        styleCss = content.toString(Charsets.UTF_8)
                                    name.startsWith("assets/") -> {
                                        val fileName = name.removePrefix("assets/").substringAfterLast('/')
                                        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                                        if (ext in imageExtensions && backgroundName == null) {
                                            backgroundName = fileName
                                            backgroundBytes.add(fileName to content)
                                        }
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: throw IllegalArgumentException("主题包导入失败：无法打开所选文件")
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("主题包导入失败：zip 解压异常（${e.message ?: "未知错误"}）")
            }

            val m = manifest ?: throw IllegalArgumentException("主题包无效：缺少 manifest.json")
            if (themeJson == null) throw IllegalArgumentException("主题包无效：缺少 theme.json")
            // 落库前校验 tokens 可解析，坏包直接拒绝（避免坏 JSON 入库后打死应用链）
            if (ThemeSupport.parseTokens(themeJson!!) == null) {
                throw IllegalArgumentException("主题包无效：theme.json 无法解析为有效的主题定义")
            }
            if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(m.id)) {
                throw IllegalArgumentException("主题包非法：manifest.id 不合法（仅允许 1-64 位字母/数字/下划线/连字符）")
            }
            // layout 能力双闸之一（导入闸）：声明 layout 却缺 template.html 的包直接拒绝，
            // 避免"启用了 layout 包但引擎拿不到模板"的静默降级
            if (m.capabilities == THEME_CAPABILITY_LAYOUT && templateHtml == null) {
                throw IllegalArgumentException(
                    "主题包无效：manifest.capabilities 为 \"layout\" 但 zip 中缺少 template.html" +
                        "（布局主题必须携带模板，缺省即整包拒绝；纯换肤包请声明 \"cosmetic\"）",
                )
            }
            // 非 layout 一律按 cosmetic 处理（未知取值防御性归一），cosmetic 包行为与存量完全一致
            val effectiveCapability = m.capabilities.takeIf { it == THEME_CAPABILITY_LAYOUT } ?: "cosmetic"

            // 覆盖安装采用"先写新、后清旧"顺序：中途失败时旧行仍在（最多背景文件被同名覆盖），
            // 不会出现"旧包已删、新包未落库"的数据丢失窗口
            val packDir = File(context.filesDir, "themes/${m.id}")
            packDir.mkdirs()
            var storedBackground: String? = null
            if (backgroundName != null) {
                val bg = backgroundBytes.first()
                File(packDir, bg.first).writeBytes(bg.second)
                storedBackground = bg.first
            }
            // 模板/样式落包目录（实体只存 capabilities/template_version 列，内容不进数据库）
            if (templateHtml != null) File(packDir, TEMPLATE_FILE).writeText(templateHtml!!, Charsets.UTF_8)
            if (styleCss != null) File(packDir, STYLE_FILE).writeText(styleCss!!, Charsets.UTF_8)

            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            db.themePackDao().upsert(
                ThemePackEntity(
                    id = m.id,
                    name = m.name,
                    author = m.author,
                    version = m.version,
                    tokensJson = themeJson!!,
                    backgroundFile = storedBackground,
                    createdAt = now,
                    capabilities = effectiveCapability,
                    templateVersion = m.templateVersion,
                ),
            )

            // 清理包目录中不再被引用的遗留文件（覆盖安装后旧背景/旧模板等）
            val keep =
                setOfNotNull(storedBackground, templateHtml?.let { TEMPLATE_FILE }, styleCss?.let { STYLE_FILE })
            packDir.listFiles()?.forEach { f ->
                if (f.name !in keep) f.delete()
            }
            m.id
        }

    private fun parseManifest(text: String): ThemeManifest {
        val m =
            try {
                manifestJson.decodeFromString<ThemeManifest>(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("主题包无效：manifest.json 解析失败（${e.message ?: "格式错误"}）")
            }
        if (m.id.isBlank()) throw IllegalArgumentException("主题包非法：manifest.id 为空")
        return m
    }

    /** 删行 + 递归删 filesDir/themes/<id> */
    suspend fun deletePack(id: String) =
        withContext(Dispatchers.IO) {
            db.themePackDao().deleteById(id)
            File(context.filesDir, "themes/$id").deleteRecursively()
        }

    /** 背景图文件：filesDir/themes/<id>/<backgroundFile>，不存在返回 null */
    fun backgroundFile(pack: ThemePackEntity): File? {
        val name = pack.backgroundFile ?: return null
        val f = File(File(context.filesDir, "themes/${pack.id}"), name)
        return if (f.exists()) f else null
    }

    /**
     * 应用链命中的包实体（会话 → 角色 → 全局 → null）；tokens 与背景图共用此解析保证语义一致。
     * sessionId 空白时跳过会话层，不订阅 observeById（避免无谓查询）。
     */
    fun observeResolvedPack(
        sessionId: String?,
        characterId: String?,
    ): Flow<ThemePackEntity?> {
        val sid = sessionId?.trim().orEmpty()
        val sessionTheme =
            if (sid.isEmpty()) {
                kotlinx.coroutines.flow.flowOf<com.mistbell.tavern.android.data.local.entity.SessionEntity?>(null)
            } else {
                db.sessionDao().observeById(sid)
            }
        val charId = characterId?.trim().orEmpty()
        val characterTheme =
            if (charId.isEmpty()) {
                kotlinx.coroutines.flow.flowOf<String?>(null)
            } else {
                db.characterDao().getAll().map { list -> list.find { it.id == charId }?.themeId }
            }
        return combine(
            sessionTheme.map { it?.themeId },
            characterTheme,
            db.themePackDao().observeAll(),
            db.settingsDao().observeValue("active_theme_id"),
        ) { sessionThemeId, charThemeId, packs, activeId ->
            val map = packs.associateBy { it.id }
            ThemeSupport.resolvePackId(sessionThemeId, charThemeId, activeId, map)?.let { map[it] }
        }
    }

    /** 角色主题 tokens 流（保留原签名，供 Theme.kt 使用）：characterId 空 → 全局主题；无 → null */
    fun observeTokensForCharacter(characterId: String?): Flow<ThemeTokens?> =
        observeResolvedPack(null, characterId)
            .map { pack -> pack?.let { ThemeSupport.parseTokens(it.tokensJson) } }

    /**
     * 引擎布局包解析流（M4）：复用 resolvePackId 的 会话 → 角色 → 全局 链，命中且
     * capabilities=layout 且 templateVersion 受支持且 template.html 在盘时返回 [ResolvedLayoutPack]
     * （含模板/样式与 light/dark 两套 --mt-* token）。任一条件不满足 → null：引擎不激活，
     * 消息内容走 legacy 渲染（设计文档 §7 降级路由的"无包即 legacy"层）。
     */
    fun observeResolvedLayoutPack(
        sessionId: String?,
        characterId: String?,
    ): Flow<ResolvedLayoutPack?> =
        observeResolvedPack(sessionId, characterId)
            .map { pack ->
                if (pack == null) return@map null
                if (pack.capabilities != THEME_CAPABILITY_LAYOUT) return@map null
                if (pack.templateVersion != THEME_SUPPORTED_TEMPLATE_VERSION) return@map null
                val tokens = ThemeSupport.parseTokens(pack.tokensJson) ?: return@map null
                val packDir = packDirectory(pack.id)
                val template = File(packDir, TEMPLATE_FILE).takeIf { it.isFile }?.readText() ?: return@map null
                val style = File(packDir, STYLE_FILE).takeIf { it.isFile }?.readText()
                val (light, dark) = layoutThemeVars(tokens)
                ResolvedLayoutPack(
                    packId = pack.id,
                    templateHtml = template,
                    styleCss = style,
                    templateVersion = pack.templateVersion,
                    themeVarsLight = light,
                    themeVarsDark = dark,
                )
            }
            // 模板/样式为磁盘读 + tokens JSON 解析，整体切到 IO 线程，不阻塞收集方（主线程）
            .flowOn(Dispatchers.IO)

    /** 包目录：filesDir/themes/<id>（背景/模板/样式落盘点） */
    fun packDirectory(packId: String): File = File(context.filesDir, "themes/$packId")

    /** 重建 zip 到 cacheDir/themes/<id>.zip 供分享，不存在则抛 IllegalArgumentException */
    suspend fun exportPack(id: String): File =
        withContext(Dispatchers.IO) {
            val pack =
                db.themePackDao().getById(id)
                    ?: throw IllegalArgumentException("导出失败：主题包不存在（id=$id）")
            val outFile = File(File(context.cacheDir, "themes"), "$id.zip")
            outFile.parentFile?.mkdirs()
            ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                val manifest =
                    ThemeManifest(
                        id = pack.id,
                        name = pack.name,
                        author = pack.author,
                        version = pack.version,
                        capabilities = pack.capabilities,
                        templateVersion = pack.templateVersion,
                    )
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson.encodeToString(ThemeManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("theme.json"))
                zip.write(pack.tokensJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // layout 三件套：template.html/style.css 随包回写（zip 根，与导入通道对称）
                val packDir = packDirectory(pack.id)
                val template = File(packDir, TEMPLATE_FILE)
                if (template.isFile) {
                    zip.putNextEntry(ZipEntry(TEMPLATE_FILE))
                    zip.write(template.readBytes())
                    zip.closeEntry()
                }
                val style = File(packDir, STYLE_FILE)
                if (style.isFile) {
                    zip.putNextEntry(ZipEntry(STYLE_FILE))
                    zip.write(style.readBytes())
                    zip.closeEntry()
                }

                val bg = backgroundFile(pack)
                if (bg != null) {
                    zip.putNextEntry(ZipEntry("assets/${bg.name}"))
                    bg.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            outFile
        }
}
