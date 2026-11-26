package com.mistbell.tavern.android

import java.io.File

/**
 * P1「依赖棘轮」的扫描引擎（docs/ROADMAP.md「P1 立规矩与冻结」第 2 项）。
 * 只被 [ArchitectureRulesTest] 与 LintBaselineRatchetTest 复用，不是生产代码。
 *
 * 口径（与 architecture-baseline.json 的 meta 一致）：
 * - 扫描对象：app/src/main/java 下全部 .kt；
 * - 引用 = import 行 + 代码正文里的全限定名 `com.mistbell.tavern.android.*`
 *   （本项目大量用法不 import、直接写全限定名，只扫 import 会漏）；
 * - 注释与字符串字面量内容一律剔除（剔除后行号不变，便于失败信息直接定位）；
 * - 目标归组：对每个引用取「最长已知包前缀」（已知包 = 全 app/src 的 package 声明，
 *   因为 import 的可能是类、顶层函数或嵌套类，不能简单丢最后一段）；
 *   data、ui 两棵子树的三级包若主源集文件数 >= 3 则按三级包成组，否则按二级包；其余按一级包成组；
 *   根包记作 `(root)`。
 */
object ArchitectureScan {
    const val ROOT_PACKAGE = "com.mistbell.tavern.android"
    const val ROOT_GROUP = "(root)"

    /** data 与 ui 的三级包成组所需的最少主源集文件数（评审口径） */
    private const val THREE_LEVEL_GROUP_MIN_FILES = 3

    /** 这两个一级包下的引用按二/三级细分；其余（render/extension/util/navigation/service/di）按一级包成组 */
    private val MULTI_LEVEL_HEADS = setOf("data", "ui")

    /** 代码正文里的全限定名；前面不能是标识符字符或点（避免匹配更长路径的尾部） */
    private val FQN = Regex("""(?<![A-Za-z0-9_.])com\.mistbell\.tavern\.android(?:\.[A-Za-z_]\w*)+""")

    private val INSTANCE = Regex("""\bTavernApplication\s*\.\s*instance\b""")
    private const val PACKAGE_KEYWORD = "package "
    private const val IMPORT_KEYWORD = "import "
    private const val TRIPLE_QUOTE_LENGTH = 3

    private const val STATE_CODE = 0
    private const val STATE_LINE_COMMENT = 1
    private const val STATE_BLOCK_COMMENT = 2
    private const val STATE_STRING = 3
    private const val STATE_TRIPLE_STRING = 4
    private const val STATE_CHAR_LITERAL = 5

    /** 一处引用：源文件（仓库相对路径，'/' 分隔）+ 行号 + 引用原文 */
    data class Ref(val file: String, val line: Int, val text: String)

    /** from 组 → to 组 的全部引用 */
    class Edge(val from: String, val to: String) {
        val refs = mutableListOf<Ref>()
    }

    class ScanResult(
        val edges: List<Edge>,
        /** 代码里 TavernApplication.instance 的出现处（注释已剔除） */
        val instanceRefs: List<Ref>,
        /** ui.* 文件里指向 data.local.entity 的 import 与全限定名（受基线约束，只减不增） */
        val uiEntityRefs: List<Ref>,
        /** ui.* 文件里指向 data.local.dao 的 import 与全限定名（必须为 0） */
        val uiDaoRefs: List<Ref>,
    ) {
        fun edge(
            from: String,
            to: String,
        ): Edge? = edges.find { it.from == from && it.to == to }

        /** 互赖对：A→B 与 B→A 同时存在记一对；无序对以字典序小者在前，`↔` 连接，排序输出 */
        fun mutualPairs(): List<String> =
            edges.mapNotNull { e ->
                if (e.from < e.to && edge(e.to, e.from) != null) "${e.from}↔${e.to}" else null
            }.sorted()
    }

    /** 从当前工作目录逐级向上找 settings.gradle 定位仓库根（单测工作目录是模块目录） */
    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile
        }
        error("未能定位仓库根：从 ${System.getProperty("user.dir")} 逐级向上没有找到 settings.gradle")
    }

    /** 已知包（相对根包的路径，根包本身记 ""）→ 主源集中声明该包的文件数（测试源集的包计入已知集合，文件数记 0） */
    fun knownPackageFileCounts(root: File): Map<String, Int> {
        val srcRoot = File(root, "app/src")
        val mainRoot = File(root, "app/src/main/java")
        val counts = linkedMapOf<String, Int>()
        val files = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (file in files) {
            val pkg = packageOf(stripComments(file.readText())) ?: continue
            val rel = relativePath(pkg)
            if (file.startsWith(mainRoot)) {
                counts.merge(rel, 1, Int::plus)
            } else {
                counts.putIfAbsent(rel, 0)
            }
        }
        return counts
    }

    /**
     * 剔除注释与字符串字面量内容，全部替换为空格、只保留换行——剔除前后行号一致。
     * 状态机按 Kotlin 语法处理行注释、块注释、单引号字符串、三引号字符串与字符字面量。
     * 已知不完美：字符串模板 `${...}` 内的代码按字符串内容剔除（全仓库当前没有这种用法）。
     *
     * 六状态扫描器（代码/行注释/块注释/字符串/三引号/字符字面量）；ktlint 展开单行分支后行数
     * 超过 LongMethod 门槛，复杂度与嵌套同理——状态机本身不可再拆，按仓库惯例就地抑制。
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
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
                            out.append("  ")
                            i += 2
                        }
                        c == '/' && n == '*' -> {
                            state = STATE_BLOCK_COMMENT
                            out.append("  ")
                            i += 2
                        }
                        c == '"' && n == '"' && text.getOrNull(i + 2) == '"' ->
                            {
                                state = STATE_TRIPLE_STRING
                                out.append("\"\"\"")
                                i += TRIPLE_QUOTE_LENGTH
                            }
                        c == '"' -> {
                            state = STATE_STRING
                            out.append(c)
                            i += 1
                        }
                        c == '\'' -> {
                            state = STATE_CHAR_LITERAL
                            out.append(c)
                            i += 1
                        }
                        else -> {
                            out.append(c)
                            i += 1
                        }
                    }
                STATE_LINE_COMMENT ->
                    if (c == '\n') {
                        state = STATE_CODE
                        out.append(c)
                        i += 1
                    } else {
                        out.append(' ')
                        i += 1
                    }
                STATE_BLOCK_COMMENT ->
                    when {
                        c == '*' && n == '/' -> {
                            state = STATE_CODE
                            out.append("  ")
                            i += 2
                        }
                        else -> {
                            out.append(if (c == '\n') c else ' ')
                            i += 1
                        }
                    }
                STATE_STRING ->
                    when {
                        c == '\\' -> {
                            blankEscaped(out, n)
                            i += 2
                        }
                        c == '"' -> {
                            state = STATE_CODE
                            out.append(c)
                            i += 1
                        }
                        else -> {
                            out.append(if (c == '\n') c else ' ')
                            i += 1
                        }
                    }
                STATE_TRIPLE_STRING ->
                    when {
                        c == '\\' -> {
                            blankEscaped(out, n)
                            i += 2
                        }
                        c == '"' && n == '"' && text.getOrNull(i + 2) == '"' ->
                            {
                                state = STATE_CODE
                                out.append("\"\"\"")
                                i += TRIPLE_QUOTE_LENGTH
                            }
                        else -> {
                            out.append(if (c == '\n') c else ' ')
                            i += 1
                        }
                    }
                else ->
                    when {
                        c == '\\' -> {
                            blankEscaped(out, n)
                            i += 2
                        }
                        c == '\'' -> {
                            state = STATE_CODE
                            out.append(c)
                            i += 1
                        }
                        else -> {
                            out.append(if (c == '\n') c else ' ')
                            i += 1
                        }
                    }
            }
        }
        return out.toString()
    }

    /** 转义序列置空（若被转义的是换行则保留换行，维持行号） */
    private fun blankEscaped(
        out: StringBuilder,
        next: Char,
    ) {
        out.append(' ')
        if (next == '\n') out.append('\n')
    }

    /** 引用目标（去掉根包前缀的路径）→ 包组。见类 KDoc 的包组口径。 */
    fun groupOf(
        restPath: String,
        known: Map<String, Int>,
    ): String {
        val segs = longestKnownPackage(restPath, known).orEmpty().split('.').filter { it.isNotEmpty() }
        val head = segs.firstOrNull()
        val threeLevel = segs.take(THREE_LEVEL_GROUP_MIN_FILES).joinToString(".")
        return when {
            head == null -> ROOT_GROUP
            head !in MULTI_LEVEL_HEADS -> head
            segs.size >= THREE_LEVEL_GROUP_MIN_FILES &&
                (known[threeLevel] ?: 0) >= THREE_LEVEL_GROUP_MIN_FILES -> threeLevel
            else -> segs.take(2).joinToString(".")
        }
    }

    /** 根包自身 → ""；其余剥掉 `com.mistbell.tavern.android.` 前缀 */
    private fun relativePath(fqn: String): String = if (fqn == ROOT_PACKAGE) "" else fqn.removePrefix("$ROOT_PACKAGE.")

    private fun longestKnownPackage(
        restPath: String,
        known: Map<String, Int>,
    ): String? = known.keys.filter { p -> restPath == p || restPath.startsWith("$p.") }.maxByOrNull { it.length }

    /** 扫描主源集全部 .kt，聚合出边与各专项规则的引用清单 */
    fun scanMainSources(root: File): ScanResult {
        val known = knownPackageFileCounts(root)
        val mainRoot = File(root, "app/src/main/java")
        val sink = Sink(known)
        val files =
            mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .toList().sortedBy { it.path }
        for (file in files) {
            val stripped = stripComments(file.readText())
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val pkg = packageOf(stripped).orEmpty()
            val fromGroup = groupOf(relativePath(pkg), known)
            val sourceIsUi = pkg == "$ROOT_PACKAGE.ui" || pkg.startsWith("$ROOT_PACKAGE.ui.")
            scanFile(stripped, rel, fromGroup, sourceIsUi, sink)
        }
        return ScanResult(sink.edges.values.toList(), sink.instanceRefs, sink.entityRefs, sink.daoRefs)
    }

    private fun scanFile(
        stripped: String,
        rel: String,
        fromGroup: String,
        sourceIsUi: Boolean,
        sink: Sink,
    ) {
        val lines = stripped.lines()
        for (idx in lines.indices) {
            val trimmed = lines[idx].trim()
            if (!trimmed.startsWith(PACKAGE_KEYWORD)) {
                sink.addLine(trimmed, rel, idx + 1, fromGroup, sourceIsUi)
            }
        }
    }

    private fun packageOf(strippedText: String): String? =
        strippedText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(PACKAGE_KEYWORD) }
            ?.removePrefix(PACKAGE_KEYWORD)
            ?.trim()

    /** 扫描过程中的可变聚合桶 */
    private class Sink(val known: Map<String, Int>) {
        val edges = linkedMapOf<Pair<String, String>, Edge>()
        val instanceRefs = mutableListOf<Ref>()
        val entityRefs = mutableListOf<Ref>()
        val daoRefs = mutableListOf<Ref>()

        /** 单行分发：import 行（`import a.b.C as X` 取 `a.b.C`；`.*` 通配后缀剥掉）与其余代码行 */
        fun addLine(
            trimmed: String,
            rel: String,
            lineNo: Int,
            fromGroup: String,
            sourceIsUi: Boolean,
        ) {
            if (trimmed.startsWith(IMPORT_KEYWORD)) {
                val target = trimmed.removePrefix(IMPORT_KEYWORD).trim().substringBefore(' ').trimEnd(';')
                if (target.isNotEmpty()) addImport(target, rel, lineNo, fromGroup, sourceIsUi)
            } else {
                addCode(trimmed, rel, lineNo, fromGroup, sourceIsUi)
            }
        }

        fun addImport(
            target: String,
            file: String,
            line: Int,
            fromGroup: String,
            sourceIsUi: Boolean,
        ) {
            if (!target.startsWith("$ROOT_PACKAGE.")) return
            val path = target.removePrefix("$ROOT_PACKAGE.").removeSuffix(".*")
            if (sourceIsUi && underPackage(path, "data.local.dao")) daoRefs += Ref(file, line, target)
            if (sourceIsUi && underPackage(path, "data.local.entity")) entityRefs += Ref(file, line, target)
            addEdge(path, target, file, line, fromGroup)
        }

        fun addCode(
            lineText: String,
            file: String,
            line: Int,
            fromGroup: String,
            sourceIsUi: Boolean,
        ) {
            for (m in INSTANCE.findAll(lineText)) instanceRefs += Ref(file, line, m.value)
            for (m in FQN.findAll(lineText)) {
                val path = m.value.removePrefix("$ROOT_PACKAGE.")
                if (sourceIsUi && underPackage(path, "data.local.dao")) daoRefs += Ref(file, line, m.value)
                // entity 与 DAO 同口径：全限定名写法也计入，否则删掉 import 改写全限定名就能绕过棘轮
                if (sourceIsUi && underPackage(path, "data.local.entity")) entityRefs += Ref(file, line, m.value)
                addEdge(path, m.value, file, line, fromGroup)
            }
        }

        private fun addEdge(
            path: String,
            text: String,
            file: String,
            line: Int,
            fromGroup: String,
        ) {
            val toGroup = groupOf(path, known)
            if (toGroup == fromGroup) return
            val key = fromGroup to toGroup
            val edge = edges.getOrPut(key) { Edge(fromGroup, toGroup) }
            edge.refs += Ref(file, line, text)
        }

        private fun underPackage(
            path: String,
            pkg: String,
        ): Boolean = path == pkg || path.startsWith("$pkg.")
    }
}
