package com.mistbell.tavern.android.eval.memory

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.repository.buildVectorContent
import com.mistbell.tavern.android.data.vector.LocalEmbeddingService
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** 查询侧 BGE 检索指令前缀（正文侧永远不加） */
private const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："

/** P3 池去掉的「探针之前最近 N 楼」（设备侧实测历史窗口的中位数） */
private const val RECENT_WINDOW = 50

/** 毫秒换算（纳秒计时的展示口径） */
private const val NANOS_PER_MILLISECOND = 1_000_000L

private const val LOG_TAG = "DeviceRecallDiag"

/** instrumentation 开关：只有 -e evalRealStack true 时才运行 */
private const val GATE_ARG = "evalRealStack"

/** 查询口径：不加前缀 / 加检索指令前缀 */
private enum class QueryKind {
    PLAIN,
    PREFIXED,
}

/** 候选池口径：P1=探针前全部消息；P2=P1 去其他探针楼；P3=P2 再去最近 50 楼 */
private enum class PoolKind {
    P1,
    P2,
    P3,
}

/** 一条记忆摘要候选（生产 buildVectorContent 的向量 + 它归属的事实版本） */
private class SummaryEntry(
    val memoryId: Long,
    val factId: String?,
    val version: String?,
    val vector: FloatArray,
)

/** 一次排名的单条打分 */
private class ScoredFloor(val floor: Int, val score: Double)

private class ScoredSummary(val memoryId: Long, val score: Double)

/** 跨会话累加的聚合读数 */
private class RecallDiagAggregate {
    var probesTotal = 0
    val ranks = HashMap<String, MutableList<Int>>()
    val excluded = HashMap<String, Int>()
    val hits5 = HashMap<String, Int>()
    val hits15 = HashMap<String, Int>()
    val poolSizes = HashMap<String, MutableList<Int>>()
    val classHits5 = HashMap<String, Int>()
    val classTotals = HashMap<String, Int>()
    val targetCos = mutableListOf<Double>()
    val top5NonTargetCos = mutableListOf<Double>()
    val recentInTop5 = mutableListOf<Int>()
    var summariesCovered = 0
    val summaryRanksPlain = mutableListOf<Int>()
    val summaryRanksPrefix = mutableListOf<Int>()
    var summaryHits5Plain = 0
    var summaryHits5Prefix = 0
    var embedCount = 0
    var embedMs = 0L
}

/**
 * 设备侧召回排名诊断（只测量，不改任何行为）：回答「向量召回块里为什么总是探针自己」——
 * 把三个候选原因拆开单独量：
 * (a) 模型用法：查询侧加/不加 BGE 检索指令前缀「为这个句子生成表示以用于检索相关文章：」
 *     （正文侧永远不加），其余全部相同；
 * (b) 夹具：P2 池去掉其他探针楼的消息（探针之间互相最像）；
 * (c) 生产缺陷（已确认）：P3 池再去掉探针之前最近 50 楼（= 不排除当前消息与已在历史窗口内
 *     的消息的对照组）；另直接数 P1 原样口径下前五里「最近 50 楼」占掉几个名额。
 *
 * 测量口径：
 * - 每会话把第 2..300 楼消息正文各编码一次（复用 LocalEmbeddingService，与生产同一入口）；
 *   记忆摘要向量 = 生产落库记忆（驱动器 ORACLE 回放产出）里 importance ≥ 7 者的
 *   buildVectorContent() 向量（与 VectorSyncRecovery 提交的内容逐字同源）；
 * - 目标消息：updated → updateFloor；original → statementFloor（该事实带重述且重述楼早于
 *   探针时，取 statement 与 restatement 中排名更好者）；
 * - 排名：池内按余弦降序、并列按楼号升序；目标不在池内记一次「被池排除」；
 * - 只测量：不改生产代码、不改既有评测测试的行为（复用 DeviceSessionStack / 评测驱动器）。
 *
 * ## 运行命令（模拟器已启动时）
 * ```bash
 * adb shell am instrument -w \
 *   -e class com.mistbell.tavern.android.eval.memory.DeviceRecallRankDiagnosticTest \
 *   -e evalRealStack true \
 *   com.mistbell.tavern.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 不带 `-e evalRealStack true` 时 Assume 跳过。报告写到被测应用 cacheDir 的
 * memory-eval-device/report-device-recall-rank-diagnostic.md，关键行以 tag [LOG_TAG] 打 logcat。
 */
@RunWith(AndroidJUnit4::class)
class DeviceRecallRankDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val targetContext get() = instrumentation.targetContext

    private val reportDir: File by lazy {
        File(targetContext.cacheDir, "memory-eval-device").apply { mkdirs() }
    }

    private val ownerId = "eval-diag-${UUID.randomUUID().toString().take(8)}"

    private var savedGate: (suspend () -> Unit)? = null
    private var savedProvider: (() -> VectorMemoryService)? = null

    @Test
    fun `诊断_召回排名_查询前缀与候选池`() =
        runBlocking {
            Assume.assumeTrue(
                "跳过：召回排名诊断只在 `-e $GATE_ARG true` 时运行。运行命令见 " +
                    "DeviceRecallRankDiagnosticTest 类 KDoc。",
                InstrumentationRegistry.getArguments().getString(GATE_ARG) == "true",
            )
            val access = DeviceEvalResourceAccess.resolve(instrumentation)
            val loaded = MemoryEvalFixtures.load(EvalResourceResolver { path -> access.openOrNull(path) })
            Assume.assumeTrue("没有任何可用夹具会话（${loaded.summary}）", loaded.sessions.isNotEmpty())
            val onnx = LocalEmbeddingService.create(targetContext)
            val agg = RecallDiagAggregate()
            loaded.sessions.forEachIndexed { index, session ->
                // 摘要候选只需要生产落库记忆（ORACLE 回放的 finalMemories）；向量栈本身不参与
                // 测量，用 Mock + 内存库让回放本身尽量快、与被测的 ONNX 编码互不干扰
                val stack =
                    DeviceSessionStack.create(
                        targetContext,
                        reportDir,
                        MockEmbeddingService(),
                        "recall-diag-$index",
                        sqlite = false,
                    )
                installPromptVars(stack.vectorMemoryService)
                try {
                    val config =
                        DriverConfig(
                            ExtractionMode.ORACLE,
                            pendingExtractionFile = File(reportDir, "pending-diag-${session.id}.json"),
                        )
                    val replay = MemoryEvalDriver(stack.pipeline, ownerId).replay(session, stack.driverDb, config)
                    measureSession(session, replay.finalMemories, onnx, agg)
                    Log.i(LOG_TAG, "${session.id} 测量完成（累计探针 ${agg.probesTotal}）")
                } finally {
                    stack.close()
                }
            }
            val markdown = render(agg, loaded.sessions.size)
            val reportFile = File(reportDir, "report-device-recall-rank-diagnostic.md")
            reportFile.writeText(markdown)
            println("召回排名诊断报告已写入: ${reportFile.absolutePath}")
            Log.i(LOG_TAG, "召回排名诊断报告已写入: ${reportFile.absolutePath}")
            Unit
        }

    // ---- 测量 ----

    private fun installPromptVars(vectorMemoryService: VectorMemoryService) {
        if (savedGate == null) {
            savedGate = PromptBuilder.memoryResetGate
            savedProvider = PromptBuilder.vectorMemoryServiceProvider
        }
        PromptBuilder.memoryResetGate = {}
        PromptBuilder.vectorMemoryServiceProvider = { vectorMemoryService }
    }

    @After
    fun restorePromptVars() {
        savedGate?.let { PromptBuilder.memoryResetGate = it }
        savedProvider?.let { PromptBuilder.vectorMemoryServiceProvider = it }
        savedGate = null
        savedProvider = null
    }

    private suspend fun timedEmbed(
        agg: RecallDiagAggregate,
        block: suspend () -> FloatArray,
    ): FloatArray {
        val startedAt = System.nanoTime()
        val vector = block()
        agg.embedCount++
        agg.embedMs += (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        return vector
    }

    /** 一个会话的全部测量：消息向量、摘要向量、逐探针 6 组排名与余弦对照 */
    private suspend fun measureSession(
        session: EvalSession,
        finalMemories: List<StructuredMemory>,
        onnx: LocalEmbeddingService,
        agg: RecallDiagAggregate,
    ) {
        val vectors = HashMap<Int, FloatArray>()
        session.messages.forEach { msg ->
            if (msg.floor >= 2) vectors[msg.floor] = timedEmbed(agg) { onnx.embed(msg.content) }
        }
        val probeFloors = session.probes.map { it.floor }.toSet()
        val summaries =
            finalMemories
                .filter { it.importance >= 7 }
                .map { memory ->
                    val match =
                        MemoryEvalMatcher.matchRow(
                            InjectedMemoryRow(
                                MemoryEvalDriver.SOURCE_STRUCTURED,
                                memory.id.toString(),
                                memory.content,
                                memory.memoryType,
                            ),
                            session,
                        )
                    SummaryEntry(
                        memory.id,
                        match.factId,
                        match.version,
                        timedEmbed(agg) { onnx.embed(memory.buildVectorContent()) },
                    )
                }
        session.probes.forEach { probe ->
            agg.probesTotal++
            val fact = session.requireFact(probe.targetFactId)
            val probeClass = session.probeClass(probe)
            val queryPlain = timedEmbed(agg) { onnx.embed(probe.text) }
            val queryPrefixed = timedEmbed(agg) { onnx.embed(QUERY_PREFIX + probe.text) }
            val targetFloors = targetFloorsOf(fact, probe)
            val otherProbeFloors = probeFloors - probe.floor
            val pools =
                mapOf(
                    PoolKind.P1 to vectors.keys.filter { it < probe.floor },
                    PoolKind.P2 to vectors.keys.filter { it < probe.floor && it !in otherProbeFloors },
                    PoolKind.P3 to vectors.keys.filter { it < probe.floor - RECENT_WINDOW && it !in otherProbeFloors },
                )
            listOf(QueryKind.PLAIN to queryPlain, QueryKind.PREFIXED to queryPrefixed).forEach { (query, q) ->
                pools.forEach { (pool, floors) ->
                    val key = "${query.name}/${pool.name}"
                    agg.poolSizes.getOrPut(key) { mutableListOf() } += floors.size
                    val rank = rankOf(floors, vectors, q, targetFloors)
                    if (rank == null) {
                        agg.excluded[key] = (agg.excluded[key] ?: 0) + 1
                    } else {
                        agg.ranks.getOrPut(key) { mutableListOf() } += rank
                        if (rank <= 5) agg.hits5[key] = (agg.hits5[key] ?: 0) + 1
                        if (rank <= 15) agg.hits15[key] = (agg.hits15[key] ?: 0) + 1
                    }
                    if (pool == PoolKind.P1) {
                        val classKey = "${probeClass.name}/${query.name}"
                        agg.classTotals[classKey] = (agg.classTotals[classKey] ?: 0) + 1
                        if (rank != null && rank <= 5) {
                            agg.classHits5[classKey] = (agg.classHits5[classKey] ?: 0) + 1
                        }
                    }
                }
            }
            measurePlainP1Extras(probe, vectors, queryPlain, targetFloors, agg)
            measureSummaryRank(probe, summaries, queryPlain, queryPrefixed, agg)
        }
    }

    /** 原样查询 · P1 的附加读数：目标余弦 vs 非目标前五余弦、(c) 最近 50 楼占名额 */
    private fun measurePlainP1Extras(
        probe: EvalProbe,
        vectors: Map<Int, FloatArray>,
        queryPlain: FloatArray,
        targetFloors: List<Int>,
        agg: RecallDiagAggregate,
    ) {
        val pool = vectors.keys.filter { it < probe.floor }
        val scored =
            pool
                .map { floor -> ScoredFloor(floor, dot(queryPlain, vectors.getValue(floor))) }
                .sortedWith(compareByDescending<ScoredFloor> { it.score }.thenBy { it.floor })
        val targetScore = targetFloors.maxOfOrNull { floor -> dot(queryPlain, vectors.getValue(floor)) }
        if (targetScore != null) agg.targetCos += targetScore
        val nonTargetTop5 = scored.filter { it.floor !in targetFloors }.take(5)
        if (nonTargetTop5.isNotEmpty()) {
            agg.top5NonTargetCos += nonTargetTop5.map { it.score }.average()
        }
        agg.recentInTop5 += scored.take(5).count { it.floor >= probe.floor - RECENT_WINDOW }
    }

    /** 摘要向量排名（池 = 本会话全部 importance≥7 摘要）；目标 = 匹配目标事实（优先目标版本）的摘要 */
    private suspend fun measureSummaryRank(
        probe: EvalProbe,
        summaries: List<SummaryEntry>,
        queryPlain: FloatArray,
        queryPrefixed: FloatArray,
        agg: RecallDiagAggregate,
    ) {
        if (summaries.isEmpty()) return
        val targetVersionSummaries = summaries.filter { it.factId == probe.targetFactId && it.version == probe.targetVersion }
        val factSummaries =
            if (targetVersionSummaries.isEmpty()) {
                summaries.filter { it.factId == probe.targetFactId }
            } else {
                targetVersionSummaries
            }
        if (factSummaries.isEmpty()) return
        agg.summariesCovered++
        listOf(QueryKind.PLAIN to queryPlain, QueryKind.PREFIXED to queryPrefixed).forEach { (query, q) ->
            val ranked =
                summaries
                    .map { entry -> ScoredSummary(entry.memoryId, dot(q, entry.vector)) }
                    .sortedWith(compareByDescending<ScoredSummary> { it.score }.thenBy { it.memoryId })
            val rankByMemoryId = ranked.withIndex().associate { (index, scored) -> scored.memoryId to index + 1 }
            val best = factSummaries.mapNotNull { entry -> rankByMemoryId[entry.memoryId] }.minOrNull()
            if (best != null) {
                if (query == QueryKind.PLAIN) {
                    agg.summaryRanksPlain += best
                    if (best <= 5) agg.summaryHits5Plain++
                } else {
                    agg.summaryRanksPrefix += best
                    if (best <= 5) agg.summaryHits5Prefix++
                }
            }
        }
    }

    /** 目标消息楼层：updated → updateFloor；original → statementFloor（带早于探针的重述则两个都算） */
    private fun targetFloorsOf(
        fact: EvalFact,
        probe: EvalProbe,
    ): List<Int> =
        if (probe.targetVersion == "updated") {
            listOfNotNull(fact.updateFloor)
        } else {
            buildList {
                add(fact.statementFloor)
                if (fact.restatementFloor != null && fact.restatementFloor < probe.floor) {
                    add(fact.restatementFloor)
                }
            }
        }

    /** 池内按余弦降序（并列按楼号升序）目标消息的最好名次；目标不在池内返回 null */
    private fun rankOf(
        floors: List<Int>,
        vectors: Map<Int, FloatArray>,
        query: FloatArray,
        targetFloors: List<Int>,
    ): Int? {
        val scored =
            floors
                .map { floor -> ScoredFloor(floor, dot(query, vectors.getValue(floor))) }
                .sortedWith(compareByDescending<ScoredFloor> { it.score }.thenBy { it.floor })
        val rankByFloor = scored.withIndex().associate { (index, entry) -> entry.floor to index + 1 }
        return targetFloors.mapNotNull { floor -> rankByFloor[floor] }.minOrNull()
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    // ---- 报告 ----

    private fun hitCell(
        agg: RecallDiagAggregate,
        hits: Map<String, Int>,
        key: String,
        total: Int,
    ): String {
        val count = hits[key] ?: 0
        val ratio = if (total == 0) 0.0 else count.toDouble() / total
        return "$count/$total = " + "%.1f%%".format(ratio * 100)
    }

    private fun medianOf(values: List<Int>): String {
        if (values.isEmpty()) return "n/a"
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val value =
            if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        return if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
    }

    private fun avgOf(values: List<Double>): String = if (values.isEmpty()) "n/a" else "%.3f".format(values.average())

    private fun render(
        agg: RecallDiagAggregate,
        sessionCount: Int,
    ): String {
        val lines = mutableListOf<String>()
        lines += "# 设备侧召回排名诊断（只测量，不改行为）"
        lines += ""
        lines += "- 生成时间（UTC）：${Instant.now()}"
        lines += "- 探针总数 ${agg.probesTotal}（$sessionCount 会话）；ONNX 编码 ${agg.embedCount} 次 / ${agg.embedMs} ms"
        lines += "- 查询：原样 = 探针原话；加前缀 = 「$QUERY_PREFIX」+ 探针原话（只加在查询侧）"
        lines += "- 池：P1 = 探针楼之前全部消息（第 2..300 楼已编码者）；P2 = P1 去掉其他探针楼；" +
            "P3 = P2 再去掉探针之前最近 $RECENT_WINDOW 楼"
        lines += "- 排名：池内余弦降序、并列按楼号升序；目标不在池内记「被池排除」"
        lines += ""
        lines += "## 主表（2 查询 × 3 池）"
        lines += ""
        lines += "| 查询 | 池 | 平均池大小 | recall@5 | recall@15 | 中位排名 | 目标被池排除 |"
        lines += "|---|---|---|---|---|---|---|"
        listOf(QueryKind.PLAIN, QueryKind.PREFIXED).forEach { query ->
            listOf(PoolKind.P1, PoolKind.P2, PoolKind.P3).forEach { pool ->
                val key = "${query.name}/${pool.name}"
                val ranks = agg.ranks[key] ?: emptyList()
                val sizes = agg.poolSizes[key] ?: emptyList()
                val poolSize = if (sizes.isEmpty()) "n/a" else "%.1f".format(sizes.average())
                val cells =
                    listOf(
                        query.name,
                        pool.name,
                        poolSize,
                        hitCell(agg, agg.hits5, key, agg.probesTotal),
                        hitCell(agg, agg.hits15, key, agg.probesTotal),
                        medianOf(ranks),
                        "${agg.excluded[key] ?: 0}",
                    )
                lines += "| " + cells.joinToString(" | ") + " |"
            }
        }
        lines += ""
        lines += "## 目标 vs 非目标前五的余弦（原样查询 · P1）"
        lines += ""
        lines += "- 目标消息平均余弦：${avgOf(agg.targetCos)}"
        lines += "- 非目标前五平均余弦：${avgOf(agg.top5NonTargetCos)}"
        lines += ""
        lines += "## (c) 最近 $RECENT_WINDOW 楼占名额（原样查询 · P1）"
        lines += ""
        val recentAvg = if (agg.recentInTop5.isEmpty()) "n/a" else "%.2f".format(agg.recentInTop5.average())
        lines += "- 前五中属于「探针之前最近 $RECENT_WINDOW 楼」的平均个数：$recentAvg / 5"
        lines += ""
        lines += "## 按探针分类的 recall@5（P1）"
        lines += ""
        lines += "| 分类 | 探针数 | 原样 | 加前缀 |"
        lines += "|---|---|---|---|"
        ProbeClass.entries.forEach { probeClass ->
            val totalPlain = agg.classTotals["${probeClass.name}/PLAIN"] ?: 0
            val cells =
                listOf(
                    probeClass.label,
                    "$totalPlain",
                    hitCell(agg, agg.classHits5, "${probeClass.name}/PLAIN", totalPlain),
                    hitCell(agg, agg.classHits5, "${probeClass.name}/PREFIXED", totalPlain),
                )
            lines += "| " + cells.joinToString(" | ") + " |"
        }
        lines += ""
        lines += "## 记忆摘要向量（池 = 本会话全部 importance ≥ 7 摘要）"
        lines += ""
        val coverage = if (agg.probesTotal == 0) 0.0 else agg.summariesCovered.toDouble() / agg.probesTotal
        lines += "- 覆盖率（目标事实有 importance≥7 摘要的探针）：${agg.summariesCovered}/${agg.probesTotal} = " +
            "%.1f%%".format(coverage * 100)
        lines += "- recall@5：原样 ${agg.summaryHits5Plain}/${agg.summariesCovered} = " +
            "%.1f%%".format(if (agg.summariesCovered == 0) 0.0 else agg.summaryHits5Plain * 100.0 / agg.summariesCovered) +
            "；加前缀 ${agg.summaryHits5Prefix}/${agg.summariesCovered} = " +
            "%.1f%%".format(if (agg.summariesCovered == 0) 0.0 else agg.summaryHits5Prefix * 100.0 / agg.summariesCovered)
        lines += "- 中位排名：原样 ${medianOf(agg.summaryRanksPlain)}；加前缀 ${medianOf(agg.summaryRanksPrefix)}"
        lines += ""
        return lines.joinToString("\n")
    }
}
