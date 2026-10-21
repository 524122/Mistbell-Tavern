package com.mistbell.tavern.android.data.rulepack

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
object RulePackParser {
    const val MANIFEST_FILE = "manifest.json"
    const val PROMPTS_FILE = "prompts.json"
    const val REGEX_FILE = "regex.json"
    const val MACROS_FILE = "macros.json"
    const val QUICK_REPLIES_FILE = "quickreplies.json"

    val VALID_QR_ACTIONS = setOf("send", "insert", "command")

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

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
    )

    /**
     * 解析 zip 字节流。坏包抛 IllegalArgumentException（中文消息，UI 直接展示）。
     *
     * 致命错误：缺 manifest、manifest 解析失败、id 不合法、路径穿越。
     * 非致命：内容文件解析失败/单条正则编译失败 → 跳过并记 warning，不拒整包。
     */
    fun parseZip(bytes: ByteArray): Bundle {
        val files = readEntries(bytes)
        val warnings = mutableListOf<String>()

        val manifest =
            files[MANIFEST_FILE]?.let { parseManifest(it) }
                ?: invalid("规则包无效：缺少 manifest.json")

        val prompts = parseList<RulePackPrompt>(files[PROMPTS_FILE], PROMPTS_FILE, warnings)
        val regex =
            parseList<RulePackRegexRule>(files[REGEX_FILE], REGEX_FILE, warnings)
                .filter { rule -> compilableRegex(rule, warnings) }
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

        return Bundle(
            manifest = manifest,
            content =
                RulePackContent(
                    prompts = prompts,
                    regex = regex,
                    macros = macros,
                    quickReplies = quickReplies,
                ),
            warnings = warnings,
        )
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
