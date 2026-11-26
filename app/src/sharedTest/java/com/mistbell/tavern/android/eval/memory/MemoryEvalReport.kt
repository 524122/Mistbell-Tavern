package com.mistbell.tavern.android.eval.memory

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 评测报告（Markdown）：总表 + 每会话明细 + 指标定义 + 来源标签 + 闸门判断 + 失败样例。
 * 格式刻意稳定（固定列序、固定小数位），方便与上一版对比。
 *
 * render 是章节编排顺序（每节已拆成 append 助手），保持一屏可读的目录式结构
 * （TooManyFunctions / LongMethod 豁免）。
 */
@Suppress("TooManyFunctions", "LongMethod")
object MemoryEvalReport {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    private const val GATE_RECALL = 0.60
    private const val GATE_HIT = 0.50

    /** 落库重要度分布的 1–10 各档（档位本身就是定义，MagicNumber 豁免） */
    @Suppress("MagicNumber")
    private val IMPORTANCE_LEVELS = 1..10

    /** 百分比换算（pct 展示用） */
    private const val PERCENT = 100

    @Suppress("MagicNumber")
    private fun pct(value: Double?): String = if (value == null) "n/a" else "%.1f%%".format(value * PERCENT)

    /** 闸门：抽取召回率 < 60% 或注入命中率 < 50% 时触发 */
    fun gateLine(input: EvalInput): String {
        val recall = MemoryEvalMetrics.extractionRecall(input)
        val hit = MemoryEvalMetrics.injectionHit(input)
        val triggered =
            listOfNotNull(
                recall.value?.takeIf { it < GATE_RECALL }?.let { "抽取召回率 ${pct(it)} < 60%" },
                hit.value?.takeIf { it < GATE_HIT }?.let { "注入命中率 ${pct(it)} < 50%" },
            )
        val fixed =
            "按 P2 规则只修一轮管线错误，然后重录基线继续；不改提示词语义、不调参刷分。"
        return if (triggered.isEmpty()) {
            "闸门：未触发（抽取召回率=${pct(recall.value)} ≥ 60%，注入命中率=${pct(hit.value)} ≥ 50%）"
        } else {
            "闸门：**触发闸门** —— ${triggered.joinToString("；")}。$fixed"
        }
    }

    private fun perSessionInput(
        input: EvalInput,
        pair: ReplayWithFixture,
    ): EvalInput = EvalInput(input.mode, listOf(pair), recallChannel = input.recallChannel)

    /** 总表的一行（固定列序，稳定格式） */
    private fun sessionRow(
        input: EvalInput,
        pair: ReplayWithFixture,
    ): String {
        val replay = pair.replay
        val one = perSessionInput(input, pair)
        val classCells =
            ProbeClass.entries.map { probeClass ->
                val probes = replay.probes.filter { it.probeClass == probeClass }
                "${probes.count { it.valid }}/${probes.size}"
            }
        val earliest = replay.probes.mapNotNull { it.earliestCoveredFloor }
        val window = if (earliest.isEmpty()) "n/a" else "${earliest.min()}..${earliest.max()}"
        return buildString {
            append("| ${replay.sessionId} ")
            append("| ${replay.floorCount} ")
            append("| ${replay.avgMessageChars} ")
            append("| ${replay.contextTokenLimit} ")
            append("| ${replay.probes.size} ")
            append("| ${replay.probes.count { it.valid }} ")
            append("| ${classCells[0]} | ${classCells[1]} | ${classCells[2]} ")
            append("| ${pct(MemoryEvalMetrics.extractionRecall(one).value)} ")
            append("| ${pct(MemoryEvalMetrics.injectionHit(one).value)} ")
            append("| ${pct(MemoryEvalMetrics.staleInjectionRowRate(one).value)} ")
            append("| ${pct(MemoryEvalMetrics.noiseRate(one).value)} ")
            append("| ${replay.extractionCalls} ")
            append("| ${replay.gateSkippedBatches} ")
            append("| $window |")
        }
    }

    private fun appendMetricRow(
        lines: MutableList<String>,
        metric: MetricValue,
    ) {
        val cell = "${metric.numerator}/${metric.denominator}"
        lines += "| ${metric.name} | $cell | ${pct(metric.value)} | ${metric.label} |"
    }

    private fun appendDefinitions(lines: MutableList<String>) {
        lines += "## 指标定义"
        lines += ""
        lines += "- **抽取召回率**：埋点的事实版本（原版本 + 更新版本）中，回放结束后记忆库里至少有一条与之对应的记忆的比例。"
        lines += "- **注入命中率**：有效探针中，目标事实的**目标版本**在该探针楼的注入记忆里出现过的比例；「更新版本的探针」为其子集。"
        lines += "- **过期注入率（行级）**：探针楼注入的记忆行里，匹配到「在该楼已被更新的旧版本」的行数 ÷ 匹配到任何埋点事实的行数。"
        lines += "- **过期注入率（探针级）**：后段探针（目标事实的更新已生效）中，旧版本仍被注入的探针数 ÷ 该类探针数。"
        lines += "- **重述负例**：带重述且**不带**更新的事实里——(a) 回放结束后库内对应记忆**恰好一条**的比例（多于一条 = 重复未被去重）；"
        lines += "  (b) 探针指向它时仍命中的比例（未命中 = 重述被误判成更新而失效）。"
        lines += "- **噪声率**：注入行里不匹配任何埋点事实的行数 ÷ 注入行数。"
        lines += "- **成本**：每个有效探针楼注入记忆的 token 估算（应用自己的 estimateTokens 口径）与抽取调用次数（实际走了解析管线的批次数）。"
        lines += "- **匹配规则**：归一化（小写、去空白标点）后，记忆行包含该事实的 `object`，或与该版本陈述文本的字符二元组重合度（Dice）≥ " +
            "${MemoryEvalMatcher.BIGRAM_THRESHOLD}（原版本的陈述文本 = 原话 + 重述，取最高；更新版本 = update.statement）；" +
            "一行只归给重合度最高的（事实, 版本）对；都不满足即不匹配。"
        lines += "- **有效探针**：目标事实的 statementFloor / restatementFloor / updateFloor（非 null 者）对应的楼都不在最终提示词" +
            "的历史窗口里（历史窗口是会话消息的连续后缀，按历史段条数算）；无效探针不计入命中率与过期注入的分母（窗口明细见各会话探针表）。"
        lines += ""
    }

    private fun appendProbesWindow(
        lines: MutableList<String>,
        input: EvalInput,
    ) {
        lines += "## 各会话探针窗口明细"
        lines += ""
        input.replays.forEach { pair ->
            lines += "### ${pair.replay.sessionId}（${pair.fixture.title}）"
            lines += ""
            lines += "| 探针楼 | 分类 | 有效 | 窗口最早楼层 | 注入(结构化/召回) | 结构化类型 | 记忆token | 命中 |"
            lines += "|---|---|---|---|---|---|---|---|"
            pair.replay.probes.forEach { probe ->
                val rowMatches = probe.injectedRows.map { MemoryEvalMatcher.matchRow(it, pair.fixture) }
                val hit =
                    rowMatches.any {
                        it.factId == probe.probe.targetFactId && it.version == probe.probe.targetVersion
                    }
                val invalid = if (!probe.valid) "（${probe.invalidReason}）" else ""
                val types = probe.structuredTypes.distinct().joinToString("/").ifBlank { "-" }
                lines += "| ${probe.probe.floor} | ${probe.probeClass.label} | ${if (probe.valid) "是" else "否"} " +
                    "| ${probe.earliestCoveredFloor ?: "n/a"} | ${probe.structuredCount}/${probe.recallCount} " +
                    "| $types | ${probe.injectedMemoryTokens} | ${if (hit) "是" else "否"}$invalid |"
            }
            lines += ""
        }
    }

    private fun appendFailureSamples(
        lines: MutableList<String>,
        input: EvalInput,
    ) {
        val samples = MemoryEvalMetrics.failureSamples(input)
        lines += "## 失败样例（${samples.size} 条）"
        lines += ""
        if (samples.isEmpty()) lines += "（无未命中/过期注入样例）"
        samples.forEachIndexed { index, sample ->
            lines += "### 样例 ${index + 1}：会话 ${sample.sessionId} 第 ${sample.probeFloor} 楼 · 事实 ${sample.factId}"
            lines += "- 事实原话：${sample.statement}"
            lines += "- 探针：${sample.probeText}"
            lines += "- 期望：${sample.expected}"
            lines += "- 实际：${sample.actual}"
            lines += "- 匹配明细：${sample.matchDetails}"
            lines += ""
        }
    }

    fun render(input: EvalInput): String {
        val label = MemoryEvalMetrics.sourceLabel(input)
        val lines = mutableListOf<String>()
        lines += "# 会话记忆评测报告（$label）"
        lines += ""
        lines += "- 生成时间（UTC）：${TIMESTAMP.format(Instant.now())}"
        lines += "- 运行：${input.runLabel.ifBlank { input.mode.label }}"
        input.recordingModel?.let { lines += "- 录制模型：$it" }
        lines += "- 召回通道：${input.recallChannel}（MockEmbeddingService 为确定性词法回退，向量路不参与注入；" +
            "向量召回由设备侧「真 ONNX」证据单独测）"
        input.replays.firstOrNull()?.let { first ->
            lines += "- 夹具预算=${first.fixture.contextTokenLimit}，运行预算=${first.replay.contextTokenLimit}" +
                "（驱动器一律读夹具/覆盖参数，不写死）"
        }
        val degradations = input.replays.flatMap { it.replay.degradations }.distinct()
        if (degradations.isNotEmpty()) {
            lines += "- **人为退化注入**：${degradations.joinToString("；")}"
        }
        lines += ""
        lines += "## 总表（每会话一行）"
        lines += ""
        lines += "| 会话 | 楼层 | 平均字数/条 | 预算 | 探针 | 有效探针 | 前段有效/总 | 中段有效/总 | 后段有效/总 " +
            "| 抽取召回率 | 注入命中率 | 过期注入(行) | 噪声率 | 抽取调用 | 质量闸跳过 | 窗口最早楼层(min..max) |"
        lines += "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
        input.replays.forEach { pair -> lines += sessionRow(input, pair) }
        lines += ""
        lines += "### 汇总（全部分会话合计）"
        lines += ""
        lines += "| 指标 | 分子/分母 | 值 | 来源 |"
        lines += "|---|---|---|---|"
        appendMetricRow(lines, MemoryEvalMetrics.extractionRecall(input))
        appendMetricRow(lines, MemoryEvalMetrics.injectionHit(input))
        appendMetricRow(lines, MemoryEvalMetrics.injectionHitOnUpdatedProbes(input))
        appendMetricRow(lines, MemoryEvalMetrics.staleInjectionRowRate(input))
        appendMetricRow(lines, MemoryEvalMetrics.staleInjectionProbeRate(input))
        appendMetricRow(lines, MemoryEvalMetrics.restatementDedup(input))
        appendMetricRow(lines, MemoryEvalMetrics.restatementHit(input))
        appendMetricRow(lines, MemoryEvalMetrics.noiseRate(input))
        val tokens = MemoryEvalMetrics.avgInjectedTokensPerValidProbe(input)
        val tokensText = tokens?.let { "%.0f".format(it) } ?: "n/a"
        lines += "| 每个有效探针楼的注入记忆 token | - | $tokensText | $label |"
        val skipped = MemoryEvalMetrics.gateSkippedBatches(input)
        lines += "| 抽取调用次数 | - | ${MemoryEvalMetrics.extractionCalls(input)}（质量闸跳过 $skipped 批） | $label |"
        val invalid = MemoryEvalMetrics.invalidProbes(input)
        lines += "| 探针 | ${MemoryEvalMetrics.validProbes(input)} 有效 / ${MemoryEvalMetrics.totalProbes(input)} 总" +
            "（无效 $invalid：目标事实仍在历史窗口） | - | $label |"
        lines += ""
        lines += "### 探针三分类"
        lines += ""
        lines += "| 分类 | 总数 | 有效 | 命中 | 过期注入 | 有效/总 |"
        lines += "|---|---|---|---|---|---|"
        MemoryEvalMetrics.hitByClass(input).forEach { classHit ->
            val validity = if (classHit.total == 0) null else classHit.valid.toDouble() / classHit.total
            lines += "| ${classHit.probeClass.label} | ${classHit.total} | ${classHit.valid} | ${classHit.hits} " +
                "| ${classHit.staleInjectedProbes} | ${pct(validity)} |"
        }
        lines += ""
        appendDefinitions(lines)
        lines += "## 来源标签"
        lines += ""
        lines += "每个数字标注来源：**$label**。三种证据各证各的——"
        lines += "- 回放夹具（本报告）：抽取、去重、召回、注入**管线还在**；JVM 上 embedding 为确定性 Mock，召回走词法回退。"
        lines += "- 真 ONNX（设备侧 androidTest，后续任务）：向量召回的真实命中率/过期注入/噪声。"
        lines += "- 真实探针（发版时 20 条）：抽取的真实水平（RECORDED 模式以录制数据在 JVM 预演）。"
        lines += ""
        appendOraclePolicy(lines, input)
        appendImportanceDistribution(lines, input)
        lines += gateLine(input)
        lines += ""
        appendFailureSamples(lines, input)
        appendProbesWindow(lines, input)
        if (input.fixtureFailures.isNotEmpty()) {
            lines += "## 夹具问题（加载失败的会话，未参与评测）"
            lines += ""
            input.fixtureFailures.forEach { failure -> lines += "- ${failure.message}" }
            lines += ""
        }
        lines += "## 夹具汇总"
        lines += ""
        val sessionIds = input.replays.joinToString("、") { it.replay.sessionId }
        lines += "- ${input.replays.size} 个会话参与回放：$sessionIds"
        return lines.joinToString("\n")
    }

    /** ORACLE 运行的合成口径（重要度映射 / rawText / tags / aliases），固定写进报告 */
    private fun appendOraclePolicy(
        lines: MutableList<String>,
        input: EvalInput,
    ) {
        if (input.mode != ExtractionMode.ORACLE) return
        // 变体（按类型定档 / ORACLE-flat 同一常数）由报告头的"运行"行区分
        lines += "## ORACLE 合成口径"
        lines += ""
        lines += "- rawText：夹具**原话本身**（完美抽取 = 逐字记录，不做第三人称转写；原话也是 enrichKeywords 的关键词来源）。"
        lines += "- tags = [subject, object]，aliases = [object]，subject/relation/object 取夹具结构化字段。"
        val mapping =
            OracleOutputs.TYPE_IMPORTANCE.entries
                .joinToString("，") { "${it.key}=${it.value}" }
        lines += "- importance 按 memoryType 定档（无额外加成，加成由生产 enhanceImportance 完成）：$mapping；" +
            "未知类型 = ${OracleOutputs.DEFAULT_IMPORTANCE}。"
        lines += "- 更新与重述按它们所在的批次进入。"
        lines += ""
    }

    /** 每会话落库后的重要度分布（1–10 各多少条；来自回放结束后的记忆库快照） */
    private fun appendImportanceDistribution(
        lines: MutableList<String>,
        input: EvalInput,
    ) {
        lines += "## 落库重要度分布（1–10 各档条数）"
        lines += ""
        lines += "| 会话 | " + IMPORTANCE_LEVELS.joinToString(" | ") { "$it" } + " | 合计 |"
        lines += "|---|" + IMPORTANCE_LEVELS.joinToString("|") { "---" } + "|---|"
        input.replays.forEach { pair ->
            val counts = IMPORTANCE_LEVELS.map { level -> pair.replay.finalMemories.count { it.importance == level } }
            val total = pair.replay.finalMemories.size
            lines += "| ${pair.replay.sessionId} | " + counts.joinToString(" | ") + " | $total |"
        }
        lines += ""
    }

    /** 写文件 + 控制台打印总表（单测控制台可见） */
    fun writeAndPrint(
        input: EvalInput,
        outputFile: File,
    ): String {
        val markdown = render(input)
        outputFile.parentFile.mkdirs()
        outputFile.writeText(markdown)
        println("===== 会话记忆评测（${MemoryEvalMetrics.sourceLabel(input)}）=====")
        println("| 会话 | 有效/探针 | 抽取召回率 | 注入命中率 | 过期注入(行) | 噪声率 | 窗口最早楼层 |")
        println("|---|---|---|---|---|---|---|")
        input.replays.forEach { pair ->
            val one = perSessionInput(input, pair)
            val earliest = pair.replay.probes.mapNotNull { it.earliestCoveredFloor }
            val window = if (earliest.isEmpty()) "n/a" else "${earliest.min()}..${earliest.max()}"
            val valid = MemoryEvalMetrics.validProbes(one)
            val total = MemoryEvalMetrics.totalProbes(one)
            println(
                "| ${pair.replay.sessionId} | $valid/$total | ${pct(MemoryEvalMetrics.extractionRecall(one).value)} " +
                    "| ${pct(MemoryEvalMetrics.injectionHit(one).value)} " +
                    "| ${pct(MemoryEvalMetrics.staleInjectionRowRate(one).value)} " +
                    "| ${pct(MemoryEvalMetrics.noiseRate(one).value)} | $window |",
            )
        }
        val recall = MemoryEvalMetrics.extractionRecall(input)
        val hit = MemoryEvalMetrics.injectionHit(input)
        println(
            "合计: 抽取召回率 ${recall.ratio()} | 注入命中率 ${hit.ratio()} | " +
                "噪声率 ${MemoryEvalMetrics.noiseRate(input).ratio()} | " +
                "抽取调用 ${MemoryEvalMetrics.extractionCalls(input)} 次",
        )
        println(gateLine(input))
        println("报告已写入: ${outputFile.absolutePath}")
        return markdown
    }
}
