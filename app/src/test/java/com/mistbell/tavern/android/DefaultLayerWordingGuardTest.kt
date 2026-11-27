package com.mistbell.tavern.android

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 默认层措辞守护（P3 第 4 项验收，docs/ROADMAP.md「P3 门面」：
 * **全新安装 5 分钟内不出现酒馆术语**；docs/UI_MODE.md「首启与默认层」）。
 *
 * 「默认层」= 全新安装、简洁模式下用户在前几分钟能走到的界面：首启三屏、主界面三个 Tab、
 * 聊天列表空态、角色 Tab、新建会话页、聊天页、记忆面板、设置页（简洁模式下可见的部分）。
 * 本测试把默认层涉及的源文件列成清单，断言其**字符串字面量**（注释不算——注释不是界面文案）
 * 里不出现酒馆生态术语（SillyTavern、酒馆、角色卡、埋卡、世界书、正则、规则包、扩展包、
 * tavern_helper、MVU、破甲 等）。以后有人往这些文件里加术语会直接失败。
 *
 * 例外（ALLOWED，每条写明理由）：规划本身要求出现的说明文案（界面模式的「哪些能力不运行」）、
 * 仅高级模式渲染的入口/对话框（老用户不丢入口）、仅在用户主动执行导入动作后出现的反馈、
 * 协议里的 JSON 键名、以及明确保留的应用名「Mistbell Tavern」。
 *
 * **不在清单里**（有意豁免，勿顺手加进来）：
 * - `ui/import`：「导入」页是用户主动进入的地方，允许点名酒馆生态（P3 验收原文的例外）；
 * - `ui/settings/SettingsViewModel.kt`：版本更新日志是历史记录（提到当年的生态互通），不是界面文案；
 * - `ui/character/CharacterEditorScreen.kt`：角色编辑器是新建/编辑角色的深层页面，不在默认层
 *   清单内；其世界书/正则入口的收拢属 P3 第 2 项范畴；
 * - `ui/settings/PromptManagement.kt`、`SettingsPromptCards.kt` 与 worldbook / regex / rulepack /
 *   extensionpack / htmlui / theme 各包：高级模式专属界面。
 * 清单与禁用词都放在本测试里，构成 P3 验收的守护。
 */
class DefaultLayerWordingGuardTest {
    private val root: File by lazy { ArchitectureScan.repoRoot() }

    private val srcPrefix = "app/src/main/java/com/mistbell/tavern/android/"

    /** 禁用词（字符串子串匹配，区分大小写）：酒馆生态术语与其常见写法 */
    private val bannedTerms: List<String> =
        listOf(
            "SillyTavern",
            "酒馆",
            // 应用名「Mistbell Tavern」走 ALLOWED 例外
            "Tavern",
            "角色卡",
            "埋卡",
            "世界书",
            "正则",
            "规则包",
            "扩展包",
            "tavern_helper",
            "MVU",
            "破甲",
            "STscript",
        )

    /**
     * 例外清单：默认层文件相对路径 → 该文件里允许出现禁用词的字面量子串（每条注明理由）。
     * 语义：一条字面量只要**包含**某个允许子串即豁免——因此允许子串要尽量长、贴近原文。
     */
    private val allowed: Map<String, List<String>> =
        mapOf(
            "ui/onboarding/OnboardingScreen.kt" to
                listOf(
                    // 应用名保留（「明确不改名」）
                    "Mistbell Tavern",
                ),
            "ui/settings/AboutScreen.kt" to
                // 应用名保留
                listOf("Mistbell Tavern"),
            "ui/main/MainScreen.kt" to
                listOf(
                    // 高级模式专属 Tab 的标签（简洁模式不渲染这两个 Tab）
                    "世界书",
                    "正则",
                ),
            "ui/chat/ChatSetupScreen.kt" to
                listOf(
                    // 世界书选择器只在高级模式渲染（P3 第 2/4 项收拢；自动默认不受影响）
                    "世界书",
                    "未知世界书",
                    "未命名世界书",
                ),
            "ui/character/CharacterListScreen.kt" to
                listOf(
                    // 批量文件夹导入完成后的报告——仅在用户主动执行导入动作后出现
                    "世界书：新增",
                    "跳过无埋卡图片",
                ),
            "ui/character/CharacterListViewModel.kt" to
                listOf(
                    // 导入成功反馈（世界书新增/更新 N 条）——仅在导入动作后出现
                    "（世界书",
                    // 导入失败反馈（支持 JSON 与 PNG 埋卡）——同上
                    "导入失败：无法解析角色卡文件",
                ),
            "ui/chat/ChatViewModel.kt" to
                listOf(
                    // extensions 里的 JSON 键名（非界面文案；取值与诊断日志用）
                    "tavern_helper",
                ),
            "ui/chat/ChatSettingsScreen.kt" to
                listOf(
                    // 世界书绑定只在高级模式渲染（TechnicalDetailFeature 收拢）
                    "世界书",
                    // 附加指令深度说明（「对齐酒馆 AN」），仅高级模式
                    "对齐酒馆",
                ),
            "ui/settings/ModernSettingsScreen.kt" to
                listOf(
                    // 仅高级折叠组的副标题
                    "提示词、规则包、扩展包与实验功能",
                    // 规划要求的模式说明（简洁可见）
                    "简洁模式下正则、规则包、脚本、扩展钩子、HTML 界面与 MVU 不运行",
                    // 仅高级：提示词管理行
                    "管理破甲、文风等提示词",
                    // 仅高级：规则包行副标题
                    "导入/分享规则包：提示词块、输出正则、自定义宏",
                    // 仅高级：扩展包行副标题
                    "导入/管理扩展包：权限清单确认、启停、覆盖安装",
                    // 仅高级的两个分组行标题（与上两条同区）
                    "规则包",
                    "扩展包",
                    // 实验功能的 HTML 界面说明，仅高级/实验
                    "部分角色卡（如含酒馆助手脚本的卡）",
                ),
            "ui/settings/UserModeComponents.kt" to
                listOf(
                    // 规划原文（P3 第 2 项要求写明关掉什么）
                    "简洁模式下这些能力不运行",
                    // 简洁模式卡片要点
                    "正则、脚本、HTML 界面不运行",
                    // 高级模式卡片说明（升级弹层/设置对话框）
                    "完整功能，与酒馆生态导入能力配合",
                    // 高级模式卡片要点
                    "全部设置与世界书入口",
                    // 高级模式卡片要点
                    "正则、脚本、HTML 界面照常运行",
                    // 高级模式卡片要点
                    "适合导入酒馆资产的用户",
                ),
        )

    /** 默认层清单：首启、主导航、聊天列表空态、角色 Tab、新建会话、聊天页、记忆面板、设置（简洁可见部分） */
    private val defaultLayerFiles: List<String> =
        listOf(
            // 首启三屏
            "ui/onboarding/OnboardingScreen.kt",
            // 主导航三个 Tab + 挂在主导航层的两个一次性弹层
            "ui/main/MainScreen.kt",
            "ui/main/MainTab.kt",
            "ui/main/UiModePromptViewModel.kt",
            "ui/main/SessionMemoryResetNoticeViewModel.kt",
            // 聊天 Tab（含空态）与列表项
            "ui/chatlist/ChatListScreen.kt",
            "ui/chatlist/ModernChatListItem.kt",
            "ui/chatlist/ChatListViewModel.kt",
            // 角色 Tab
            "ui/character/CharacterListScreen.kt",
            "ui/character/CharacterListViewModel.kt",
            "ui/character/ModernCharacterCard.kt",
            // 新建会话页
            "ui/chat/ChatSetupScreen.kt",
            "ui/chat/ChatSetupViewModel.kt",
            // 聊天页（消息气泡、快捷回复条）
            "ui/chat/ChatScreen.kt",
            "ui/chat/MessageBubble.kt",
            "ui/chat/ChatViewModel.kt",
            "ui/chat/QuickReplyBar.kt",
            // 记忆面板（聊天页「记得 N 件」进入）
            "ui/memory/StructuredMemoryScreen.kt",
            // 设置页（简洁模式下可见的部分）与关于页
            "ui/settings/ModernSettingsScreen.kt",
            "ui/settings/UserModeComponents.kt",
            "ui/settings/AboutScreen.kt",
            // 聊天设置页（简洁模式可见开关的宿主文件；高级专属区走 ALLOWED 例外）
            "ui/chat/ChatSettingsScreen.kt",
            // 公共组件（聊天空态 / 顶栏）
            "ui/components/EmptyStateView.kt",
            "ui/common/ModernTopBar.kt",
        )

    /** 单个默认层文件的违例（字面量含禁用词且未被 ALLOWED 例外豁免） */
    private fun violationsInFile(rel: String): List<Violation> {
        val file = File(root, srcPrefix + rel)
        assertTrue("默认层清单里有文件不存在: $rel", file.isFile)
        val text = file.readText()
        return stringLiterals(text).flatMap { (lineNo, literal) ->
            bannedTerms
                .filter { term -> !excused(rel, literal) && literal.contains(term) }
                .map { term -> Violation(rel, lineNo, term, literal.take(80)) }
        }
    }

    /** 字面量是否被例外豁免（包含任一登记的允许子串） */
    private fun excused(
        rel: String,
        literal: String,
    ): Boolean = allowed[rel].orEmpty().any { substring -> literal.contains(substring) }

    // —— 状态机常量（与 ArchitectureScan 同构：代码/行注释/块注释/字符串/三引号/字符字面量）——
    private companion object {
        const val STATE_CODE = 0
        const val STATE_LINE_COMMENT = 1
        const val STATE_BLOCK_COMMENT = 2
        const val STATE_STRING = 3
        const val STATE_TRIPLE_STRING = 4
        const val STATE_CHAR_LITERAL = 5
    }

    /** 一条命中的字面量：文件、行号、命中的禁用词、字面量原文（截断展示） */
    private data class Violation(
        val file: String,
        val line: Int,
        val term: String,
        val literal: String,
    )

    @Test
    fun `守护清单里的文件都存在（改名或移动时更新清单）`() {
        val missing =
            defaultLayerFiles
                .map { File(root, srcPrefix + it) }
                .filter { !it.isFile }
                .map { it.path }
        if (missing.isNotEmpty()) {
            fail("默认层清单里有文件不存在了（文件改名/移动后请同步清单）：\n" + missing.joinToString("\n"))
        }
    }

    @Test
    fun `默认层字符串字面量不出现酒馆生态术语`() {
        val violations = defaultLayerFiles.flatMap(::violationsInFile)
        if (violations.isNotEmpty()) {
            fail(
                "默认层出现酒馆生态术语（P3 验收：全新安装 5 分钟内不出现酒馆术语）。\n" +
                    "界面文案请改用中性措辞；确属规划要求或仅高级模式渲染的文案，请带理由登记进 ALLOWED 例外：\n" +
                    violations.joinToString("\n") { v -> "${v.file}:${v.line} [${v.term}] ${v.literal}" },
            )
        }
    }

    /**
     * 内置角色资产（随 APK 分发、全新安装就进角色 Tab）是默认层文案的一部分：
     * 卡的描述、开场白、人设与自带世界书条目都会直接显示在界面上。装机实测发现阿莉雅的卡里
     * 出现「酒馆」——上面的源文件清单只扫 Kotlin 字面量，扫不到 assets 里的 JSON，这里补上。
     * JSON 的键与值一并检查（内置卡不该带任何生态字段）；应用名「Mistbell Tavern」除外。
     */
    @Test
    fun `内置角色资产不出现酒馆生态术语`() {
        val assetDir = File(root, "app/src/main/assets/sample_characters")
        assertTrue("内置角色资产目录不存在: ${assetDir.path}", assetDir.isDirectory)
        val assets =
            assetDir
                .listFiles { file -> file.isFile && file.extension == "json" }
                .orEmpty()
                .sortedBy { it.name }
        assertTrue("内置角色资产目录里没有 JSON 卡", assets.isNotEmpty())
        val violations = mutableListOf<String>()
        assets.forEach { asset ->
            asset.readLines().forEachIndexed { index, line ->
                val visible = line.replace("Mistbell Tavern", "")
                bannedTerms.filter { visible.contains(it) }.forEach { term ->
                    violations += "${asset.name}:${index + 1} [$term] ${line.trim().take(80)}"
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "内置角色资产里出现酒馆生态术语（P3 验收：全新安装 5 分钟内不出现酒馆术语）。\n" +
                    "内置卡的描述 / 开场白 / 人设 / 世界书条目会直接显示给新用户，请改用中性措辞：\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    /**
     * 提取字符串字面量（含三引号），返回（行号, 字面量内容）；注释与字符字面量一律跳过。
     * 状态机与 ArchitectureScan.stripComments 同构（六状态），行号按字面量结束处计。
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 状态机不可再拆，按仓库惯例就地抑制
    private fun stringLiterals(text: String): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        val literal = StringBuilder()
        var state = STATE_CODE
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val n = if (i + 1 < text.length) text[i + 1] else ' '
            when (state) {
                STATE_CODE ->
                    when {
                        c == '/' && n == '/' -> {
                            state = STATE_LINE_COMMENT
                            i += 2
                        }
                        c == '/' && n == '*' -> {
                            state = STATE_BLOCK_COMMENT
                            i += 2
                        }
                        c == '"' && n == '"' && text.getOrNull(i + 2) == '"' -> {
                            state = STATE_TRIPLE_STRING
                            literal.setLength(0)
                            i += 3
                        }
                        c == '"' -> {
                            state = STATE_STRING
                            literal.setLength(0)
                            i += 1
                        }
                        c == '\'' -> {
                            state = STATE_CHAR_LITERAL
                            i += 1
                        }
                        else -> i += 1
                    }
                STATE_LINE_COMMENT ->
                    if (c == '\n') {
                        state = STATE_CODE
                    } else {
                        i += 1
                    }
                STATE_BLOCK_COMMENT ->
                    if (c == '*' && n == '/') {
                        state = STATE_CODE
                        i += 2
                    } else {
                        i += 1
                    }
                STATE_STRING ->
                    when {
                        c == '\\' -> i += 2
                        c == '"' -> {
                            results += lineAt(text, i) to literal.toString()
                            state = STATE_CODE
                            i += 1
                        }
                        else -> {
                            literal.append(c)
                            i += 1
                        }
                    }
                STATE_TRIPLE_STRING ->
                    when {
                        c == '\\' -> i += 2
                        c == '"' && n == '"' && text.getOrNull(i + 2) == '"' -> {
                            results += lineAt(text, i) to literal.toString()
                            state = STATE_CODE
                            i += 3
                        }
                        else -> {
                            literal.append(c)
                            i += 1
                        }
                    }
                else -> // STATE_CHAR_LITERAL
                    when {
                        c == '\\' -> i += 2
                        c == '\'' -> state = STATE_CODE
                        else -> i += 1
                    }
            }
        }
        return results
    }

    /** 字面量结束偏移所在的 1 起始行号 */
    private fun lineAt(
        text: String,
        endOffset: Int,
    ): Int {
        var line = 1
        for (i in 0 until endOffset) {
            if (text[i] == '\n') line += 1
        }
        return line
    }
}
