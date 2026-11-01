package com.mistbell.tavern.android.data.rulepack

import com.mistbell.tavern.android.data.extensionpack.PermissionCatalog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 规则包 zip 解析器：纯 JVM 实现（java.util.zip + kotlinx.serialization），无 Android 依赖，
 * 单元测试直接覆盖；Android 侧（RulePackRepository）只负责取流与落库。
 */
@Suppress("TooManyFunctions") // 解析/校验/节读取按职责拆小函数，优于挤进少数大函数
object RulePackParser {
    const val MANIFEST_FILE = "manifest.json"
    const val PROMPTS_FILE = "prompts.json"
    const val REGEX_FILE = "regex.json"
    const val MACROS_FILE = "macros.json"
    const val QUICK_REPLIES_FILE = "quickreplies.json"

    val VALID_QR_ACTIONS = setOf("send", "insert", "command")
    val VALID_REGEX_SCOPES = setOf("output", "input")

    /** 脚本源码体积上限（防止异常大包拖垮导入/存储）；hostHooks 沿用同一上限 */
    const val MAX_SCRIPT_BYTES = 256 * 1024

    /** 扩展包 webviewPreload 体积上限（注入串拼进每个卡片 WebView，64KB 防拖慢装配） */
    const val MAX_PRELOAD_BYTES = 64 * 1024

    private const val BYTES_PER_KB = 1024

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** provides 元素格式（EXTENSION_PLATFORM_DESIGN.md §5.3）：小写字母/数字/冒号/点/下划线/连字符 */
    val PROVIDES_PATTERN = Regex("^[a-z0-9:._-]{1,64}$")

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** 解析结果：manifest + 合并内容 + 非致命警告（如坏正则条目被跳过） */
    data class Bundle(
        val manifest: RulePackManifest,
        val content: RulePackContent,
        val warnings: List<String>,
        /**
         * zip 全部条目（文件名→UTF-8 文本）。扩展包仓库据此把多文件制品落盘到
         * filesDir/extensions/<id>/（规则包仓库只消费 content，不用此字段）。
         * 解析器只读 UTF-8 文本条目——二进制资产 P1 不支持，与规则包解析器限制一致。
         */
        val files: Map<String, String> = emptyMap(),
    )

    /**
     * 解析 zip 字节流。坏包抛 IllegalArgumentException（中文消息，UI 直接展示）。
     *
     * 致命错误：缺 manifest、manifest 解析失败、id/type 不合法、未知权限、
     * 声明的 JS 文件（script/webviewPreload/hostHooks）缺失/超限/含路径、路径穿越。
     * 非致命：内容文件解析失败/单条正则编译失败 → 跳过并记 warning，不拒整包。
     */
    fun parseZip(bytes: ByteArray): Bundle {
        val files = readEntries(bytes)
        val warnings = mutableListOf<String>()

        val manifest =
            files[MANIFEST_FILE]?.let { parseManifest(it) }
                ?: invalid("规则包无效：缺少 manifest.json")
        validateType(manifest)
        validatePermissions(manifest)
        validateProvides(manifest)

        val prompts = parseList<RulePackPrompt>(files[PROMPTS_FILE], PROMPTS_FILE, warnings)
        val regex =
            parseList<RulePackRegexRule>(files[REGEX_FILE], REGEX_FILE, warnings)
                .filter { rule -> compilableRegex(rule, warnings) && validRegexScopes(rule, warnings) }
        val macros =
            parseList<RulePackMacro>(files[MACROS_FILE], MACROS_FILE, warnings)
                .filter { macro ->
                    macro.name.isNotBlank().also { ok ->
                        if (!ok) warnings.add("存在名称为空的宏条目，已跳过")
                    }
                }
        val quickReplies =
            parseList<RulePackQuickReply>(files[QUICK_REPLIES_FILE], QUICK_REPLIES_FILE, warnings)
                .filter { qr -> validQuickReply(qr, warnings) }
        val scriptSource = parseScript(files, manifest)
        val webviewPreloadSource =
            parseDeclaredJs(files, manifest.webviewPreload, "webviewPreload", MAX_PRELOAD_BYTES, invalidPrefix = "扩展包")
        val hostHooksSource =
            parseDeclaredJs(files, manifest.hostHooks, "hostHooks", MAX_SCRIPT_BYTES, invalidPrefix = "扩展包")

        return Bundle(
            manifest = manifest,
            content =
                RulePackContent(
                    prompts = prompts,
                    regex = regex,
                    macros = macros,
                    quickReplies = quickReplies,
                    scriptSource = scriptSource,
                    webviewPreloadSource = webviewPreloadSource,
                    hostHooksSource = hostHooksSource,
                ),
            warnings = warnings,
            files = files,
        )
    }

    /**
     * 读取 manifest.script 声明的脚本文件：纯文件名（不允许目录/穿越）、存在于 zip、
     * 且未超 [MAX_SCRIPT_BYTES]；manifest 未声明 script 时返回 null。
     */
    private fun parseScript(
        files: Map<String, String>,
        manifest: RulePackManifest,
    ): String? = parseDeclaredJs(files, manifest.script, "script", MAX_SCRIPT_BYTES, invalidPrefix = "规则包")

    /**
     * 读取 manifest 声明的 JS 制品文件的通用骨架（script/webviewPreload/hostHooks 共用）：
     * 必须是 zip 内纯文件名（拒绝目录分隔与 ".." 穿越）、必须存在于 zip、且未超 [maxBytes]。
     * 未声明（fileName=null）返回 null。P1 对 preload/hooks 只校验与落盘，不解析内容不执行。
     */
    private fun parseDeclaredJs(
        files: Map<String, String>,
        fileName: String?,
        manifestKey: String,
        maxBytes: Int,
        invalidPrefix: String,
    ): String? {
        fileName ?: return null
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains("..")) {
            "${invalidPrefix}非法：manifest.$manifestKey 必须是 zip 内的纯文件名（当前：$fileName）"
        }
        val source =
            files[fileName]
                ?: throw IllegalArgumentException(
                    "${invalidPrefix}无效：manifest.$manifestKey 声明的 $fileName 在 zip 中不存在",
                )
        require(source.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            "${invalidPrefix}非法：$fileName 超过 ${maxBytes / BYTES_PER_KB}KB 上限"
        }
        return source
    }

    /** 快捷回复条目校验：label 空白或 action 非法（send/insert/command 之外）跳过并告警 */
    private fun validQuickReply(
        qr: RulePackQuickReply,
        warnings: MutableList<String>,
    ): Boolean {
        val reason =
            when {
                qr.label.isBlank() -> "存在标签为空的快捷回复条目，已跳过"
                qr.action.lowercase() !in VALID_QR_ACTIONS ->
                    "快捷回复「${qr.label}」的 action 非法（${qr.action}），已跳过（合法值：send/insert/command）"
                else -> null
            }
        reason?.let { warnings.add(it) }
        return reason == null
    }

    /** 坏正则在导入期编译一次：编译不过的条目跳过并告警，避免运行时逐条炸 */
    private fun compilableRegex(
        rule: RulePackRegexRule,
        warnings: MutableList<String>,
    ): Boolean {
        runCatching { Regex(rule.pattern) }
            .onFailure { warnings.add("正则「${rule.name}」编译失败，已跳过：${it.message}") }
            .getOrNull() ?: return false
        return true
    }

    /** 作用域校验：applyTo 为空或含 output/input 之外的值时跳过并告警 */
    private fun validRegexScopes(
        rule: RulePackRegexRule,
        warnings: MutableList<String>,
    ): Boolean {
        val scopes = rule.applyTo.map { it.lowercase() }
        if (scopes.isEmpty() || scopes.any { it !in VALID_REGEX_SCOPES }) {
            warnings.add("正则「${rule.name}」的 applyTo 非法（${rule.applyTo}），已跳过（合法值：output/input）")
            return false
        }
        return true
    }

    /** 统一的包校验失败出口：集中 throw，调用方保持单返回 */
    private fun invalid(reason: String): Nothing = throw IllegalArgumentException(reason)

    /** 读取 zip 全部条目为 name→内容；条目路径含 ".." 一律拒绝（防路径穿越） */
    private fun readEntries(bytes: ByteArray): Map<String, String> {
        val files = mutableMapOf<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> zip.collectEntries(files) }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IOException) {
            invalid("规则包导入失败：zip 解压异常（${e.message ?: "未知错误"}）")
        }
        return files
    }

    private fun ZipInputStream.collectEntries(files: MutableMap<String, String>) {
        var entry: ZipEntry? = nextEntry ?: invalid("规则包无效：zip 内没有任何文件")
        while (entry != null) {
            readEntryInto(files, entry)
            closeEntry()
            entry = nextEntry
        }
    }

    private fun ZipInputStream.readEntryInto(
        files: MutableMap<String, String>,
        entry: ZipEntry,
    ) {
        if (entry.name.split('/').any { it == ".." }) {
            invalid("规则包非法：条目路径包含 \"..\"（疑似路径穿越）")
        }
        if (!entry.isDirectory) {
            files[entry.name] = readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun parseManifest(text: String): RulePackManifest {
        val manifest =
            try {
                json.decodeFromString<RulePackManifest>(text)
            } catch (e: SerializationException) {
                invalid("规则包无效：manifest.json 解析失败（${e.message ?: "格式错误"}）")
            }
        require(ID_PATTERN.matches(manifest.id)) { "规则包非法：manifest.id 不合法（仅允许 1-64 位字母/数字/连字符/下划线）" }
        require(manifest.name.isNotBlank()) { "规则包非法：manifest.name 为空" }
        return manifest
    }

    /** type 校验：仅 rulepack（缺省）/ extension 两种；未知类型显式拒绝（从严，不给静默降级） */
    private fun validateType(manifest: RulePackManifest) {
        require(manifest.type == RulePackManifest.TYPE_RULEPACK || manifest.type == RulePackManifest.TYPE_EXTENSION) {
            "扩展包非法：manifest.type 未知（${manifest.type}），仅允许 \"rulepack\"（缺省）或 \"extension\""
        }
    }

    /**
     * 权限目录校验（严格白名单）：manifest.permissions 非空即逐条过 PermissionCatalog，
     * 目录外权限直接拒整包并给出明确原因（含动态 network:<host> 形态校验）。
     * 不区分 type——规则包声明权限同样校验（从严；存量规则包不声明此字段，向后兼容不受影响）。
     */
    private fun validatePermissions(manifest: RulePackManifest) {
        if (manifest.permissions.isEmpty()) return
        PermissionCatalog.resolveAll(manifest.permissions)
    }

    /** provides 元素格式校验（§5.3）：非法元素直接拒整包；不声明则不参与提供者互斥 */
    private fun validateProvides(manifest: RulePackManifest) {
        manifest.provides.forEach { name ->
            require(PROVIDES_PATTERN.matches(name)) {
                "扩展包非法：manifest.provides 声明了非法元素「$name」" +
                    "（格式：^[a-z0-9:._-]{1,64}$，如 facade:tavernhelper）"
            }
        }
    }

    /** 解析可选内容文件；文件不存在返回空表，解析失败记 warning 并按空表处理 */
    private inline fun <reified T> parseList(
        text: String?,
        fileName: String,
        warnings: MutableList<String>,
    ): List<T> {
        if (text == null) return emptyList()
        return try {
            json.decodeFromString<List<T>>(text)
        } catch (e: SerializationException) {
            warnings.add("$fileName 解析失败，该节按空处理（${e.message ?: "格式错误"}）")
            emptyList()
        }
    }
}
