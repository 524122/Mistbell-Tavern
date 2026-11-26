package com.mistbell.tavern.android

import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * P1「依赖棘轮」（docs/ROADMAP.md「P1 立规矩与冻结」第 2 项）。
 *
 * 只减不增：超过基线 → 失败并列出具体是谁引用了谁；低于基线 → 同样失败，
 * 提示用 -ParchBaselineUpdate=true 更新基线（变好必须锁住，否则不算棘轮）。
 * 扫描口径见 [ArchitectureScan] 与基线文件 app/src/test/resources/architecture-baseline.json 的 meta。
 */
class ArchitectureRulesTest {
    private val root: File by lazy { ArchitectureScan.repoRoot() }
    private val scanned: ArchitectureScan.ScanResult by lazy { ArchitectureScan.scanMainSources(root) }

    // —— 规则 1：render 不依赖 ui.*（第一部分下沉 MarkdownParser 之后必须为 0） ——

    @Test
    fun `render 不依赖 ui`() {
        verify { result, _ ->
            val refs = result.edges.filter { isRenderGroup(it.from) && isUiGroup(it.to) }
            if (refs.isNotEmpty()) {
                fail("render 是叶子包：出现对 ui 的引用（必须为 0）\n" + edgesWithRefs(refs))
            }
        }
    }

    // —— 规则 2：data.* 不依赖 ui.*（硬性为 0）；extension 对 ui.* 受基线约束 ——

    @Test
    fun `data 包不依赖 ui`() {
        verify { result, _ ->
            val refs = result.edges.filter { isDataGroup(it.from) && isUiGroup(it.to) }
            if (refs.isNotEmpty()) {
                fail("data.* 不允许依赖 ui.*（必须为 0）\n" + edgesWithRefs(refs))
            }
        }
    }

    @Test
    fun `extension 对 ui 的依赖不超过基线`() {
        verify { result, baseline ->
            val problems = mutableListOf<String>()
            val allowed = baseline.extensionToUiEdges
            for (edge in result.edges.filter { it.from == "extension" && isUiGroup(it.to) }) {
                val key = "${edge.from}->${edge.to}"
                val limit = allowed[key]
                when {
                    limit == null -> problems += "新边 $key（${edge.refs.size} 条），基线中不存在：\n" + describe(edge)
                    edge.refs.size > limit ->
                        problems += "$key 现有 ${edge.refs.size} 条，基线上限 $limit 条：\n" + describe(edge)
                    edge.refs.size < limit ->
                        problems += "$key 现有 ${edge.refs.size} 条，低于基线 $limit 条。" + REDUCED_HINT
                }
            }
            for (key in allowed.keys) {
                if (allowed.getValue(key) > 0 && result.edge("extension", key.substringAfter("->")) == null) {
                    problems += "$key 已无引用。" + REDUCED_HINT
                }
            }
            if (problems.isNotEmpty()) fail("extension→ui 基线棘轮未满足：\n" + problems.joinToString("\n"))
        }
    }

    // —— 规则 3：ui.* 不引用 DAO（import 与全限定名都为 0）；ui.* → data.local.entity 的引用只减不增 ——

    @Test
    fun `ui 不引用 DAO`() {
        verify { result, _ ->
            if (result.uiDaoRefs.isNotEmpty()) {
                fail(
                    "ui.* 不允许引用 data.local.dao（import 与全限定名都算，必须为 0）\n" +
                        refLines(result.uiDaoRefs, MAX_SAMPLE_REFS),
                )
            }
        }
    }

    @Test
    fun `ui 对 entity 的引用只减不增`() {
        verify { result, baseline ->
            val refs = result.uiEntityRefs
            val msg =
                ratchet(
                    label = "ui→data.local.entity 引用",
                    currentCount = refs.size,
                    limitCount = baseline.uiToEntityRefCount,
                    currentFiles = distinctFiles(refs),
                    limitFiles = baseline.uiToEntityRefFiles,
                )
            if (msg != null) fail(msg + "\n" + refLines(refs, MAX_SAMPLE_REFS))
        }
    }

    // —— 规则 4：TavernApplication.instance 只减不增 ——

    @Test
    fun `TavernApplication_instance 出现次数不超过基线`() {
        verify { result, baseline ->
            val msg =
                ratchet(
                    label = "TavernApplication.instance",
                    currentCount = result.instanceRefs.size,
                    limitCount = baseline.tavernInstanceCount,
                    currentFiles = distinctFiles(result.instanceRefs),
                    limitFiles = baseline.tavernInstanceFiles,
                )
            if (msg != null) fail(msg + "\n" + refLines(result.instanceRefs, MAX_SAMPLE_REFS))
        }
    }

    // —— 规则 5：包组互赖对不允许超出基线 ——

    @Test
    fun `包组互赖对不超出基线`() {
        verify { result, baseline ->
            val current = result.mutualPairs().toSet()
            val allowed = baseline.mutualPairs.toSet()
            val added = (current - allowed).sorted()
            if (added.isNotEmpty()) {
                fail(
                    "出现基线之外的新互赖对（${added.size} 对）\n" +
                        added.joinToString("\n") { pair -> "$pair\n${sampleRefs(result, pair)}" },
                )
            }
            val gone = (allowed - current).sorted()
            if (gone.isNotEmpty()) {
                fail("互赖对减少（${gone.size} 对）：${gone.joinToString("、")}。" + REDUCED_HINT)
            }
        }
    }

    // —— 更新模式：重写基线后直接通过 ——

    private fun verify(block: (ArchitectureScan.ScanResult, ArchitectureBaseline) -> Unit) {
        if (ArchitectureBaselineStore.updateRequested) return
        block(scanned, loadBaseline())
    }

    private fun loadBaseline(): ArchitectureBaseline {
        val file = ArchitectureBaselineStore.file(root)
        if (!file.isFile) {
            fail("基线文件不存在（${file.path}），先运行 $UPDATE_CMD 生成")
        }
        return ArchitectureBaselineStore.load(file)
    }

    companion object {
        private const val UPDATE_CMD = "./gradlew :app:testDebugUnitTest -ParchBaselineUpdate=true"
        private const val MAX_SAMPLE_REFS = 12

        /** 依赖减少时的固定提示（棘轮语义：变好必须锁住） */
        private const val REDUCED_HINT = "依赖减少了，请运行 `$UPDATE_CMD` 更新基线并一并提交"

        private val isUiGroup: (String) -> Boolean = { it == "ui" || it.startsWith("ui.") }
        private val isDataGroup: (String) -> Boolean = { it == "data" || it.startsWith("data.") }
        private val isRenderGroup: (String) -> Boolean = { it == "render" || it.startsWith("render.") }

        @BeforeClass
        @JvmStatic
        fun maybeRewriteBaseline() {
            if (!ArchitectureBaselineStore.updateRequested) return
            val root = ArchitectureScan.repoRoot()
            val file = ArchitectureBaselineStore.file(root)
            ArchitectureBaselineStore.write(file, buildBaseline(root))
            println("architecture-baseline.json 已重写：${file.path}")
        }

        private fun buildBaseline(root: File): ArchitectureBaseline {
            val result = ArchitectureScan.scanMainSources(root)
            val extToUi =
                result.edges
                    .filter { it.from == "extension" && isUiGroup(it.to) }
                    .associate { "${it.from}->${it.to}" to it.refs.size }
                    .toSortedMap()
            return ArchitectureBaseline(
                meta = orderedMeta(),
                extensionToUiEdges = extToUi,
                mutualPairs = result.mutualPairs(),
                uiToEntityRefCount = result.uiEntityRefs.size,
                uiToEntityRefFiles = distinctFiles(result.uiEntityRefs),
                tavernInstanceCount = result.instanceRefs.size,
                tavernInstanceFiles = distinctFiles(result.instanceRefs),
            )
        }

        /** 口径说明：键有序，随基线一起入库，便于以后读懂当时怎么数的 */
        private fun orderedMeta(): Map<String, String> =
            linkedMapOf(
                "生成日期" to LocalDate.now().toString(),
                "更新方式" to "./gradlew :app:testDebugUnitTest -ParchBaselineUpdate=true（更新后与代码一并提交）",
                "扫描范围" to "app/src/main/java 下全部 .kt",
                "引用口径" to "import 行 + 正文全限定名；注释与字符串内容剔除（行号保留）",
                "包组口径" to "最长已知包前缀归组；data.*/ui.* 三级包主源集文件数>=3 成三级组，否则二级组；" +
                    "其余一级组；根包记 (root)",
                "render口径" to "render 组不得指向 ui 组（硬性为 0，无基线）",
                "data口径" to "data.* 组不得指向 ui 组（硬性为 0，无基线）",
                "extension口径" to "extension→ui.* 各边受 extensionToUiEdges 约束，只减不增",
                "dao口径" to "ui.* 里指向 data.local.dao 的 import 与全限定名都必须为 0",
                "entity口径" to
                    "ui.* 里指向 data.local.entity 的 import 与全限定名都计入，只减不增" +
                    "（规划原文写的是 entity import，实施时按意图收紧为与 DAO 同口径，防改写全限定名绕过）",
                "instance口径" to "代码里 TavernApplication.instance 的出现次数（注释已剔除），只减不增",
                "互赖对口径" to "组 A→B 与 B→A 同时存在记一对；允许对全列于 mutualPairs，出现新对即失败",
            )

        private fun distinctFiles(refs: List<ArchitectureScan.Ref>): Int = refs.map { it.file }.distinct().size

        /** 通用计数棘轮：超过基线列出差异；低于基线提示更新（变好也要锁住）。null = 通过 */
        private fun ratchet(
            label: String,
            currentCount: Int,
            limitCount: Int,
            currentFiles: Int,
            limitFiles: Int,
        ): String? {
            val problems = mutableListOf<String>()
            if (currentCount > limitCount) {
                problems += "$label 出现 $currentCount 处，超过基线上限 $limitCount 处"
            }
            if (currentFiles > limitFiles) {
                problems += "$label 涉及 $currentFiles 个文件，超过基线上限 $limitFiles 个"
            }
            if (currentCount < limitCount || currentFiles < limitFiles) {
                problems += "依赖减少了，请运行 $UPDATE_CMD 更新基线并一并提交"
            }
            return if (problems.isEmpty()) null else problems.joinToString("；")
        }

        private fun edgesWithRefs(edges: List<ArchitectureScan.Edge>): String =
            edges.joinToString("\n") { "${it.from}→${it.to}\n" + describe(it) }

        /** 引用明细样例：文件:行号 + 引用文本，让人能直接去改 */
        private fun describe(edge: ArchitectureScan.Edge): String = refLines(edge.refs, MAX_SAMPLE_REFS)

        private fun refLines(
            refs: List<ArchitectureScan.Ref>,
            max: Int,
        ): String = refs.take(max).joinToString("\n") { "  ${it.file}:${it.line} ${it.text}" }

        private fun sampleRefs(
            result: ArchitectureScan.ScanResult,
            pair: String,
        ): String {
            val (a, b) = pair.split("↔")
            val forward = result.edge(a, b)?.refs.orEmpty()
            val back = result.edge(b, a)?.refs.orEmpty()
            return "  " +
                (forward + back).take(MAX_SAMPLE_REFS)
                    .joinToString("\n  ") { "${it.file}:${it.line} ${it.text}" }
        }
    }
}
