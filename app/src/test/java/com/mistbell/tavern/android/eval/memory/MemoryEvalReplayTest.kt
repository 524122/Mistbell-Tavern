package com.mistbell.tavern.android.eval.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话记忆回放测试（ORACLE）：断言的是「管线还在」而不是分数达标——
 * 驱动器对全部可用夹具会话跑通、每个探针楼都装配出了提示词、报告写出、指标在 [0,1]。
 *
 * 不变量（防评测空转）：
 * - 有效探针数 > 0；三类探针（前/中/后段）合计的**每类**有效探针数 > 0，否则该评测对那一类
 *   是空分母（过期注入率会失去意义），测试明确失败并指出是哪一类；
 * - 有效探针的定义：目标事实的 statement/restatement/update 楼层都不在最终历史窗口里。
 */
class MemoryEvalReplayTest {
    private val fixturesDir = File(MemoryEvalRepo.root(), "app/build/reports/memory-eval")

    @After
    fun tearDown() {
        EvalRuntime.restore()
    }

    /** ORACLE 全会话回放（默认预算 = 夹具的 contextTokenLimit） */
    @Test
    fun `ORACLE回放_全部夹具会话_管线还在`() =
        runBlocking {
            val loaded = MemoryEvalFixtures.load(JvmEvalResources.resolver())
            val sessions = evalSessions(loaded)
            val config = DriverConfig(ExtractionMode.ORACLE, pendingExtractionFile = pendingFile("oracle-default"))
            val input = runAndReport(sessions, config, loaded.loadFailures, "ORACLE·默认预算(夹具值)")
            assertPipelineAlive(input)
        }

    /** 预算覆盖 1024 的对照运行（评测专用覆盖参数；报告单独成文） */
    @Test
    fun `ORACLE回放_预算覆盖1024_对照`() =
        runBlocking {
            val loaded = MemoryEvalFixtures.load(JvmEvalResources.resolver())
            val sessions = evalSessions(loaded)
            val config =
                DriverConfig(
                    ExtractionMode.ORACLE,
                    contextTokenLimitOverride = 1024,
                    pendingExtractionFile = pendingFile("oracle-1024"),
                )
            val input = runAndReport(sessions, config, loaded.loadFailures, "ORACLE·预算覆盖 1024")
            assertPipelineAlive(input)
        }

    /** ORACLE-flat 对照：全部事实同一重要度 0.6，观察命中对该常数的依赖 */
    @Test
    fun `ORACLE回放_flat对照_重要度常数0点6`() =
        runBlocking {
            val loaded = MemoryEvalFixtures.load(JvmEvalResources.resolver())
            val sessions = evalSessions(loaded)
            val config =
                DriverConfig(
                    ExtractionMode.ORACLE,
                    oracleImportanceOverride = 0.6,
                    pendingExtractionFile = pendingFile("oracle-flat"),
                )
            val input = runAndReport(sessions, config, loaded.loadFailures, "ORACLE-flat·重要度常数 0.6")
            assertPipelineAlive(input)
        }

    /** RECORDED 模式在录制缺失时必须明确失败（不悄悄退回 ORACLE） */
    @Test
    fun `RECORDED_录制缺失时明确失败`() =
        runBlocking {
            val session = evalSessions(MemoryEvalFixtures.load(JvmEvalResources.resolver())).first()
            val config =
                DriverConfig(
                    ExtractionMode.RECORDED,
                    recording = null,
                    pendingExtractionFile = pendingFile("recorded-missing"),
                )
            val error =
                runCatching { MemoryEvalReplayRunner.replayOne(session, config) }
                    .exceptionOrNull()
            assertNotNull("RECORDED 缺录制必须失败", error)
            assertTrue(
                "失败信息必须说明缺录制（实际：${error?.message}）",
                (error?.message ?: "").contains("录制"),
            )
        }

    // ---- 共用 ----

    private fun pendingFile(label: String): File = File(fixturesDir, "pending-extraction-$label.json")

    private fun evalSessions(loaded: LoadedFixtures): List<EvalSession> {
        val sessions = if (loaded.sessions.isEmpty()) InlineMinimalSessions.build() else loaded.sessions
        assertTrue(
            "没有任何可用夹具会话（${loaded.summary}；加载失败：${loaded.loadFailures.joinToString { it.message ?: "" }}）",
            sessions.isNotEmpty(),
        )
        return sessions
    }

    private suspend fun runAndReport(
        sessions: List<EvalSession>,
        config: DriverConfig,
        loadFailures: List<SessionLoadException>,
        runLabel: String,
    ): EvalInput {
        val replays = MemoryEvalReplayRunner.replayAll(sessions, config)
        val input = EvalInput(config.mode, replays, loadFailures, runLabel = runLabel)
        MemoryEvalReport.writeAndPrint(input, File(fixturesDir, reportName(config)))
        return input
    }

    private fun reportName(config: DriverConfig): String =
        when {
            config.oracleImportanceOverride != null -> "report-oracle-flat.md"
            config.contextTokenLimitOverride != null -> "report-budget1024.md"
            else -> "report.md"
        }

    private fun assertPipelineAlive(input: EvalInput) {
        // 每个探针楼都装配出了提示词（驱动器内部已断言最终用户消息=探针原话；这里核对覆盖数）
        input.replays.forEach { pair ->
            val probeFloors = pair.fixture.probes.map { it.floor }.sorted()
            val recordedFloors = pair.replay.probes.map { it.probe.floor }.sorted()
            assertEquals("会话 ${pair.replay.sessionId}：探针楼必须全部装配", probeFloors, recordedFloors)
            assertTrue(
                "会话 ${pair.replay.sessionId}：没有任何抽取调用（管线没在跑）",
                pair.replay.extractionCalls > 0 || pair.replay.gateSkippedBatches > 0,
            )
            // 有效探针的历史必须真的被截断（否则"注入"无意义）
            pair.replay.probes
                .filter { it.valid }
                .forEach { probe ->
                    assertTrue(
                        "会话 ${pair.replay.sessionId} 探针楼 ${probe.probe.floor}：有效探针却历史未被截断（窗口判定失真）",
                        probe.historyTruncated,
                    )
                }
        }

        // 指标都在 [0,1]
        listOf(
            MemoryEvalMetrics.extractionRecall(input),
            MemoryEvalMetrics.injectionHit(input),
            MemoryEvalMetrics.injectionHitOnUpdatedProbes(input),
            MemoryEvalMetrics.staleInjectionRowRate(input),
            MemoryEvalMetrics.staleInjectionProbeRate(input),
            MemoryEvalMetrics.restatementDedup(input),
            MemoryEvalMetrics.restatementHit(input),
            MemoryEvalMetrics.noiseRate(input),
        ).forEach { metric ->
            val value = metric.value
            if (metric.denominator > 0 && value != null) {
                assertTrue("指标 ${metric.name} 越界：${metric.ratio()}", value in 0.0..1.0)
            }
        }

        // 不变量：有效探针数 > 0，且三类各自合计的有效探针数 > 0
        val totalValid = MemoryEvalMetrics.validProbes(input)
        assertTrue("有效探针数为 0：历史窗口判定或夹具预算失真，评测在空转", totalValid > 0)
        ProbeClass.entries.forEach { probeClass ->
            val classHit = MemoryEvalMetrics.hitByClass(input).first { it.probeClass == probeClass }
            assertTrue(
                "探针分类「${probeClass.label}」在全部会话合计的有效探针数为 0" +
                    "（总数 ${classHit.total}）——该类指标会是空分母，请调整夹具布局或预算",
                classHit.valid > 0,
            )
        }
    }
}

/** 仓库根定位（JVM 专有）：从工作目录逐级向上找 settings.gradle（与 ArchitectureScan 同策略） */
internal object MemoryEvalRepo {
    fun root(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile
        }
        error("未能定位仓库根：从 ${System.getProperty("user.dir")} 逐级向上没有找到 settings.gradle")
    }
}
