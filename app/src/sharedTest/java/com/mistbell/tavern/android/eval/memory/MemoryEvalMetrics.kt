package com.mistbell.tavern.android.eval.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory

/** 百分比换算（ratio 展示用；档位数值本身就是定义，MagicNumber 豁免） */
@Suppress("MagicNumber")
private const val PERCENT = 100

/** 失败样例里"期望"陈述的截取长度 */
private const val EXPECTED_SAMPLE_CHARS = 40

/** 失败样例里"实际"注入行正文的截取长度 */
private const val ROW_SAMPLE_CHARS = 36

/** 回放结果与夹具会话的配对（指标与报告的唯一入口） */
data class ReplayWithFixture(
    val replay: SessionReplay,
    val fixture: EvalSession,
)

/** 一行注入条目的匹配结果 */
data class RowMatch(
    val row: InjectedMemoryRow,
    val factId: String?,
    val version: String?,
    val score: Double,
    val matchedBy: String,
)

/**
 * 会话记忆评测指标（纯函数）：输入是回放产出（各探针楼注入的记忆行 + 回放结束后的记忆库）
 * 与夹具，输出带分子/分母与来源标签的数字与明细。
 *
 * 匹配规则（一行注入条目 ↔ 一条埋点事实版本）：归一化后，条目内容包含该事实的 `object`，
 * **或**与该版本的陈述文本（原版本 = 原话 + 重述；更新版本 = `update.statement`）的
 * 字符二元组重合度（Dice）≥ 0.5，取其中最高者；
 * 一行只归给重合度最高的（事实, 版本）对；都不满足即不匹配。
 * 匹配明细进报告失败样例，供人工核对匹配规则本身。
 *
 * 纯函数集合：一个指标一个函数（带分子/分母），不按职责再拆对象（TooManyFunctions 豁免）。
 */
@Suppress("TooManyFunctions")
object MemoryEvalMatcher {
    const val BIGRAM_THRESHOLD = 0.5

    private val stripRegex = Regex("[\\s\\p{Punct}，。！？、；：\u201C\u201D\u2018\u2019（）《》【】…·—－]+")

    fun normalize(text: String): String = text.lowercase().replace(stripRegex, "")

    /** 归一化后的字符二元组集合；单字符串退化为自身（避免空集） */
    fun bigrams(text: String): Set<String> {
        val normalized = normalize(text)
        return when {
            normalized.isEmpty() -> emptySet()
            normalized.length == 1 -> setOf(normalized)
            else -> normalized.windowed(2).toSet()
        }
    }

    /** Dice 系数的分子系数：2|A∩B| / (|A|+|B|) */
    private const val DICE_NUMERATOR = 2.0

    /** Dice 系数：2|A∩B| / (|A|+|B|)；任一侧为空 → 0 */
    fun dice(
        a: Set<String>,
        b: Set<String>,
    ): Double =
        if (a.isEmpty() || b.isEmpty()) {
            0.0
        } else {
            DICE_NUMERATOR * a.intersect(b).size / (a.size + b.size)
        }

    /**
     * 事实的全部版本及其陈述文本。原版本含**原话与重述**：重述是同一事实的另一种说法，
     * 属于原版本——由重述抽出的记忆条目要能归回这条事实，否则会被算成噪声，
     * 「重述后恰好一条」也会把去重失败（多出一条重述条目）误记成成功。
     */
    fun versionTextsOf(fact: EvalFact): List<Pair<String, List<String>>> =
        buildList {
            add("original" to listOfNotNull(fact.statement, fact.restatement))
            fact.update?.let { add("updated" to listOf(it.statement)) }
        }

    /** 事实的全部版本：(版本名, 主陈述)；只需要版本名与主陈述的调用方用这个 */
    fun versionsOf(fact: EvalFact): List<Pair<String, String>> {
        val versions = versionTextsOf(fact)
        return versions.map { (version, statements) -> version to statements.first() }
    }

    fun containsObject(
        rowContent: String,
        fact: EvalFact,
    ): Boolean {
        val objectValue = normalize(fact.`object`)
        return objectValue.isNotBlank() && normalize(rowContent).contains(objectValue)
    }

    fun bigramOverlap(
        rowContent: String,
        statement: String,
    ): Double = dice(bigrams(rowContent), bigrams(statement))

    /** 条目与一个版本的各陈述文本之间的最大二元组重合度 */
    private fun bestOverlap(
        rowContent: String,
        texts: List<String>,
    ): Double = texts.maxOf { bigramOverlap(rowContent, it) }

    /** 是否匹配（包含 object 或与该版本任一陈述文本的二元组重合度达阈） */
    fun matches(
        rowContent: String,
        fact: EvalFact,
        version: String,
    ): Boolean =
        containsObject(rowContent, fact) ||
            versionTextsOf(fact)
                .filter { (v, _) -> v == version }
                .any { (_, texts) -> bestOverlap(rowContent, texts) >= BIGRAM_THRESHOLD }

    /** 一行注入条目 ↔ 埋点事实版本的最佳匹配（含明细）；无匹配时 factId 为 null */
    fun matchRow(
        row: InjectedMemoryRow,
        session: EvalSession,
    ): RowMatch {
        val candidates =
            session.facts
                .flatMap { fact -> versionTextsOf(fact).map { (version, texts) -> Triple(fact, version, texts) } }
                .mapNotNull { (fact, version, texts) -> scoreOf(row, fact, version, texts) }
        val best = candidates.maxByOrNull { it.score }
        return RowMatch(
            row = row,
            factId = best?.fact?.id,
            version = best?.version,
            score = best?.score ?: 0.0,
            matchedBy = best?.matchedBy ?: "none",
        )
    }

    /** 单个（事实, 版本）候选的最佳得分 */
    private data class ScoredMatch(
        val fact: EvalFact,
        val version: String,
        val score: Double,
        val matchedBy: String,
    )

    /** 单个（事实, 版本）的匹配分；不满足匹配规则返回 null */
    private fun scoreOf(
        row: InjectedMemoryRow,
        fact: EvalFact,
        version: String,
        texts: List<String>,
    ): ScoredMatch? {
        val byObject = containsObject(row.content, fact)
        val overlap = bestOverlap(row.content, texts)
        if (!(byObject || overlap >= BIGRAM_THRESHOLD)) return null
        val score = if (byObject) maxOf(1.0, overlap) else overlap
        return ScoredMatch(fact, version, score, if (byObject) "object" else "bigram")
    }

    /** 回放结束后的记忆库中，与某事实（任意版本）匹配的记忆条数 */
    fun matchingMemoryCount(
        fact: EvalFact,
        memories: List<StructuredMemory>,
    ): Int =
        memories.count { memory ->
            versionsOf(fact).any { (version, _) -> matches(memory.content, fact, version) }
        }
}

/** 一个带分子/分母与来源标签的指标值 */
data class MetricValue(
    val name: String,
    val numerator: Int,
    val denominator: Int,
    val label: String,
) {
    val value: Double?
        get() = if (denominator == 0) null else numerator.toDouble() / denominator

    fun ratio(): String {
        if (denominator == 0) return "n/a（分母 0）"
        return "$numerator/$denominator = " + "%.1f%%".format(value!! * PERCENT)
    }
}

/** 探针分类的有效数/命中数/过期注入数 */
data class ClassHit(
    val probeClass: ProbeClass,
    val total: Int,
    val valid: Int,
    val hits: Int,
    val staleInjectedProbes: Int,
)

/** 失败样例（报告与人工核对用） */
data class FailureSample(
    val sessionId: String,
    val probeFloor: Int,
    val factId: String,
    val statement: String,
    val probeText: String,
    val expected: String,
    val actual: String,
    val matchDetails: String,
)

/** 汇总输入：回放结果（与夹具配对）+ 夹具加载失败 + 运行元信息 */
data class EvalInput(
    val mode: ExtractionMode,
    val replays: List<ReplayWithFixture>,
    val fixtureFailures: List<SessionLoadException> = emptyList(),
    /** 召回通道的如实描述：MockEmbeddingService 下 PromptBuilder 走词法回退 */
    val recallChannel: String = "词法回退",
    /** 运行标签（如 "默认预算" / "预算覆盖 1024" / "退化·清空抽取输出"） */
    val runLabel: String = "",
    val recordingModel: String? = null,
    /**
     * 证据来源（三种证据分开看）：默认 JVM 侧；设备侧真栈运行传
     * "设备侧 / 真 ONNX·sqlite-vec"。只影响来源标签与报告里 JVM 专有的措辞，
     * 不影响任何指标计算。
     */
    val origin: String = MemoryEvalMetrics.DEFAULT_ORIGIN,
)

/**
 * 纯函数集合：一个指标一个函数（分子/分母与来源标签固定结构），拆对象只会打散同类指标
 * （TooManyFunctions 豁免）。
 */
@Suppress("TooManyFunctions")
object MemoryEvalMetrics {
    /** 证据来源的默认值（JVM 侧）；设备侧真栈运行覆写（见 [EvalInput.origin]） */
    const val DEFAULT_ORIGIN = "JVM / 回放夹具"

    /** 来源标签：三种证据各证各的（JVM / 设备侧真 ONNX），召回通道如实标注 */
    fun sourceLabel(input: EvalInput): String = "${input.origin} / ${input.mode.label}·${input.recallChannel}"

    private fun fixtureOf(
        input: EvalInput,
        probe: ProbeRecord,
    ): EvalSession = input.replays.first { it.replay.sessionId == probe.sessionId }.fixture

    /** 每个有效探针的注入行匹配明细（一次算好，多个指标共用） */
    fun matchProbes(input: EvalInput): List<Pair<ProbeRecord, List<RowMatch>>> =
        input.replays
            .flatMap { pair -> pair.replay.probes.map { probe -> pair.fixture to probe } }
            .filter { (_, probe) -> probe.valid }
            .map { (fixture, probe) ->
                probe to probe.injectedRows.map { row -> MemoryEvalMatcher.matchRow(row, fixture) }
            }

    private fun hitProbe(
        probe: ProbeRecord,
        rowMatches: List<RowMatch>,
    ): Boolean = rowMatches.any { it.factId == probe.probe.targetFactId && it.version == probe.probe.targetVersion }

    fun totalProbes(input: EvalInput): Int = input.replays.sumOf { it.replay.probes.size }

    fun validProbes(input: EvalInput): Int = input.replays.sumOf { pair -> pair.replay.probes.count { it.valid } }

    /** 无效探针数（原因恒为"目标事实仍在历史窗口"，计数进报告） */
    fun invalidProbes(input: EvalInput): Int = totalProbes(input) - validProbes(input)

    /** 抽取召回率：埋点事实版本（原 + 更新）中，回放结束后记忆库至少有一条与之对应的 */
    fun extractionRecall(input: EvalInput): MetricValue {
        val totalVersions =
            input.replays.sumOf { pair ->
                pair.fixture.facts.sumOf { fact -> MemoryEvalMatcher.versionsOf(fact).size }
            }
        val recalled =
            input.replays.sumOf { pair ->
                countRecalledVersions(pair.fixture.facts, pair.replay.finalMemories)
            }
        return MetricValue("抽取召回率", recalled, totalVersions, sourceLabel(input))
    }

    private fun countRecalledVersions(
        facts: List<EvalFact>,
        memories: List<StructuredMemory>,
    ): Int =
        facts
            .flatMap { fact -> MemoryEvalMatcher.versionsOf(fact).map { it.first } }
            .count { version ->
                facts.any { fact -> memories.any { MemoryEvalMatcher.matches(it.content, fact, version) } }
            }

    /** 注入命中率：目标事实的**目标版本**在该探针楼注入记忆里出现的比例（仅有效探针） */
    fun injectionHit(input: EvalInput): MetricValue {
        val matches = matchProbes(input)
        val hits = matches.count { (probe, rowMatches) -> hitProbe(probe, rowMatches) }
        return MetricValue("注入命中率", hits, matches.size, sourceLabel(input))
    }

    /** 「更新版本的探针」子集（targetVersion=updated）的命中率 */
    fun injectionHitOnUpdatedProbes(input: EvalInput): MetricValue {
        val matches = matchProbes(input).filter { (probe, _) -> probe.probe.targetVersion == "updated" }
        val hits = matches.count { (probe, rowMatches) -> hitProbe(probe, rowMatches) }
        return MetricValue("注入命中率·更新版本探针", hits, matches.size, sourceLabel(input))
    }

    /** 三类探针各自的 总数/有效数/命中数/过期注入数 */
    fun hitByClass(input: EvalInput): List<ClassHit> =
        ProbeClass.entries.map { probeClass ->
            val all = input.replays.flatMap { pair -> pair.replay.probes }.filter { it.probeClass == probeClass }
            val validWithMatches =
                input.replays
                    .flatMap { pair -> pair.replay.probes.filter { it.valid && it.probeClass == probeClass } }
                    .map { probe ->
                        probe to probe.injectedRows.map { MemoryEvalMatcher.matchRow(it, fixtureOf(input, probe)) }
                    }
            ClassHit(
                probeClass = probeClass,
                total = all.size,
                valid = validWithMatches.size,
                hits = validWithMatches.count { (probe, rowMatches) -> hitProbe(probe, rowMatches) },
                staleInjectedProbes =
                    validWithMatches.count { (probe, _) -> staleRowsInProbe(input, probe).isNotEmpty() },
            )
        }

    /** 过期注入率（行级）：探针楼注入行里，匹配到"在该楼已被更新的旧版本"的行 ÷ 匹配到任何埋点事实的行 */
    fun staleInjectionRowRate(input: EvalInput): MetricValue {
        val matches = matchProbes(input)
        val matchedRows = matches.sumOf { (_, rowMatches) -> rowMatches.count { it.factId != null } }
        val staleRows = matches.sumOf { (probe, rowMatches) -> rowMatches.count { isSupersededRow(input, probe, it) } }
        return MetricValue("过期注入率·行级", staleRows, matchedRows, sourceLabel(input))
    }

    /** 该行是否匹配到"在该楼已被更新的旧版本" */
    private fun isSupersededRow(
        input: EvalInput,
        probe: ProbeRecord,
        rowMatch: RowMatch,
    ): Boolean {
        if (rowMatch.factId == null || rowMatch.version != "original") return false
        val fact = fixtureOf(input, probe).facts.first { it.id == rowMatch.factId }
        return fact.hasUpdate && (fact.updateFloor ?: 0) <= probe.probe.floor
    }

    /** 过期注入率（探针级）：后段探针（更新已生效）里，旧版本仍被注入的探针 ÷ 该类探针（仅有效探针） */
    fun staleInjectionProbeRate(input: EvalInput): MetricValue {
        val updatedProbes =
            input.replays
                .flatMap { pair -> pair.replay.probes }
                .filter { it.valid && it.probeClass == ProbeClass.UPDATED }
        val stale = updatedProbes.count { probe -> staleRowsInProbe(input, probe).isNotEmpty() }
        return MetricValue("过期注入率·探针级（后段）", stale, updatedProbes.size, sourceLabel(input))
    }

    /** 一楼注入行里匹配到"该楼已被更新的旧版本"的行（absentFactIds 的旧版本即此类） */
    fun staleRowsInProbe(
        input: EvalInput,
        probe: ProbeRecord,
    ): List<RowMatch> {
        val fixture = fixtureOf(input, probe)
        return probe.injectedRows
            .map { MemoryEvalMatcher.matchRow(it, fixture) }
            .filter { rowMatch ->
                if (rowMatch.factId == null || rowMatch.version != "original") {
                    false
                } else {
                    val fact = fixture.facts.first { it.id == rowMatch.factId }
                    fact.hasUpdate && (fact.updateFloor ?: 0) <= probe.probe.floor
                }
            }
    }

    /** 重述负例 (a)：带重述且不带更新的事实里，库内对应记忆恰好一条的比例（多于一条=没被去重） */
    fun restatementDedup(input: EvalInput): MetricValue {
        var facts = 0
        var exactlyOne = 0
        input.replays.forEach { pair ->
            pair.fixture.facts
                .filter { !it.restatement.isNullOrBlank() && !it.hasUpdate }
                .forEach { fact ->
                    facts++
                    if (MemoryEvalMatcher.matchingMemoryCount(fact, pair.replay.finalMemories) == 1) exactlyOne++
                }
        }
        return MetricValue("重述负例·恰好一条", exactlyOne, facts, sourceLabel(input))
    }

    /** 重述负例 (b)：探针指向带重述（且不带更新）事实时仍命中的比例（未命中=重述被误判成更新而失效） */
    fun restatementHit(input: EvalInput): MetricValue {
        val matches =
            matchProbes(input).filter { (probe, _) ->
                val fact = fixtureOf(input, probe).facts.first { it.id == probe.probe.targetFactId }
                !fact.restatement.isNullOrBlank() && !fact.hasUpdate
            }
        val hits = matches.count { (probe, rowMatches) -> hitProbe(probe, rowMatches) }
        return MetricValue("重述负例·探针仍命中", hits, matches.size, sourceLabel(input))
    }

    /** 噪声率：注入行里不匹配任何埋点事实的比例（仅有效探针） */
    fun noiseRate(input: EvalInput): MetricValue {
        val matches = matchProbes(input)
        val rows = matches.sumOf { (_, rowMatches) -> rowMatches.size }
        val unmatched = matches.sumOf { (_, rowMatches) -> rowMatches.count { it.factId == null } }
        return MetricValue("噪声率", unmatched, rows, sourceLabel(input))
    }

    /** 成本：有效探针楼的平均注入记忆 token（应用自己的 estimateTokens 口径） */
    fun avgInjectedTokensPerValidProbe(input: EvalInput): Double? {
        val probes = input.replays.flatMap { pair -> pair.replay.probes }.filter { it.valid }
        if (probes.isEmpty()) return null
        return probes.sumOf { it.injectedMemoryTokens }.toDouble() / probes.size
    }

    fun extractionCalls(input: EvalInput): Int = input.replays.sumOf { it.replay.extractionCalls }

    fun gateSkippedBatches(input: EvalInput): Int = input.replays.sumOf { it.replay.gateSkippedBatches }

    /** 失败样例：未命中 + 过期注入（最多 [limit] 条，含匹配明细供人工核对匹配规则） */
    fun failureSamples(
        input: EvalInput,
        limit: Int = 8,
    ): List<FailureSample> {
        val samples = mutableListOf<FailureSample>()
        matchProbes(input).forEach { (probe, rowMatches) ->
            val fact = fixtureOf(input, probe).facts.first { it.id == probe.probe.targetFactId }
            val hit = hitProbe(probe, rowMatches)
            val stale = staleRowsInProbe(input, probe)
            if (!hit || stale.isNotEmpty()) {
                val expected =
                    "想起「${fact.id}/${probe.probe.targetVersion}」：" +
                        fact.statementOfVersion(probe.probe.targetVersion).take(EXPECTED_SAMPLE_CHARS)
                val injected =
                    if (probe.injectedRows.isEmpty()) {
                        "该楼没有注入任何记忆"
                    } else {
                        probe.injectedRows.joinToString("；") { row ->
                            "${row.source}#${row.id}: ${row.content.take(ROW_SAMPLE_CHARS)}"
                        }
                    }
                val detail =
                    rowMatches.joinToString("；") { match ->
                        "行(${match.row.source}#${match.row.id})→" +
                            (match.factId?.let { "$it/${match.version}" } ?: "无匹配") +
                            "(by=${match.matchedBy}, score=%.2f)".format(match.score)
                    }
                samples +=
                    FailureSample(
                        sessionId = probe.sessionId,
                        probeFloor = probe.probe.floor,
                        factId = fact.id,
                        statement = fact.statementOfVersion(probe.probe.targetVersion),
                        probeText = probe.probe.text,
                        expected = expected,
                        actual =
                            if (!hit) {
                                injected
                            } else {
                                "命中，但注入了旧版本：" +
                                    stale.joinToString("；") { "${it.row.source}#${it.row.id}" }
                            },
                        matchDetails = detail.ifBlank { "（无匹配明细）" },
                    )
            }
        }
        return samples.take(limit)
    }
}
