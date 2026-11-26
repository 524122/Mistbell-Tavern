package com.mistbell.tavern.android.eval.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 反向验证：评测必须会咬人。构造三种人为退化并确认指标如实变差——
 * (a) 抽取输出清空 → 抽取召回率 0、注入命中率下降；
 * (b) 去重后人为删掉一半记忆 → 记忆库变小、命中率不升；
 * (c) 人为把被更新事实的旧版本塞回记忆库 → 过期注入率上升。
 * 全部经驱动器的可控注入口完成，不改夹具。
 */
class MemoryEvalDegradationTest {
    @After
    fun tearDown() {
        EvalRuntime.restore()
    }

    private fun reportDir(): File = File(MemoryEvalRepo.root(), "app/build/reports/memory-eval")

    private fun sessions(): List<EvalSession> {
        val loaded = MemoryEvalFixtures.load(JvmEvalResources.resolver())
        return if (loaded.sessions.isEmpty()) InlineMinimalSessions.build() else loaded.sessions
    }

    /** 取一个有「被更新事实的后段探针」的会话做退化对照（没有则取第一个） */
    private fun sessionForDegradation(): EvalSession =
        sessions().firstOrNull { session ->
            session.probes.any { session.probeClass(it) == ProbeClass.UPDATED }
        } ?: sessions().first()

    private suspend fun run(
        session: EvalSession,
        runLabel: String,
        configure: (DriverConfig) -> DriverConfig,
    ): EvalInput {
        val base =
            DriverConfig(
                ExtractionMode.ORACLE,
                pendingExtractionFile = File(reportDir(), "pending-extraction-degradation.json"),
            )
        val replays = listOf(MemoryEvalReplayRunner.replayOne(session, configure(base)))
        return EvalInput(ExtractionMode.ORACLE, replays, runLabel = runLabel)
    }

    /** (a) 抽取输出清空 → 抽取召回率 0、注入命中率严格下降 */
    @Test
    fun `退化A_抽取输出清空_召回率归零_命中率下降`() =
        runBlocking {
            val session = sessionForDegradation()
            val baseline = run(session, "退化A基线") { it }
            val degraded =
                run(session, "退化A·清空抽取输出") {
                    it.copy(
                        extractionOutputOverride = { _, _, _ -> "{\"triplets\":[]}" },
                    )
                }
            val baselineRecall = MemoryEvalMetrics.extractionRecall(baseline)
            val degradedRecall = MemoryEvalMetrics.extractionRecall(degraded)
            val baselineHit = MemoryEvalMetrics.injectionHit(baseline)
            val degradedHit = MemoryEvalMetrics.injectionHit(degraded)
            assertTrue("退化A基线本身必须有抽取召回（分子>0），否则本对照无意义", baselineRecall.numerator > 0)
            assertEquals("清空抽取输出后抽取召回率必须为 0", 0, degradedRecall.numerator)
            assertTrue(
                "清空抽取输出后注入命中率必须下降（基线 ${baselineHit.ratio()} → 退化 ${degradedHit.ratio()}）",
                (degradedHit.value ?: 0.0) < (baselineHit.value ?: 0.0),
            )
            degraded.replays.forEach { pair ->
                assertTrue(
                    "清空抽取输出后结构化块必须为空（会话 ${pair.replay.sessionId}）",
                    pair.replay.probes.all { it.structuredCount == 0 },
                )
            }
        }

    /** (b) 每次保存后删掉一半记忆 → 记忆库变小、抽取召回率不升 */
    @Test
    fun `退化B_删掉一半记忆_库变小_召回下降`() =
        runBlocking {
            val session = sessionForDegradation()
            val baseline = run(session, "退化B基线") { it }
            val degraded = run(session, "退化B·每次保存删一半") { it.copy(pruneHalfMemoriesAfterSave = true) }
            val baselineMemories = baseline.replays.single().replay.finalMemories.size
            val degradedMemories = degraded.replays.single().replay.finalMemories.size
            assertTrue(
                "删一半后记忆库必须变小（基线 $baselineMemories → 退化 $degradedMemories）",
                degradedMemories < baselineMemories,
            )
            val baselineRecall = MemoryEvalMetrics.extractionRecall(baseline)
            val degradedRecall = MemoryEvalMetrics.extractionRecall(degraded)
            assertTrue(
                "删一半后抽取召回率不得上升（基线 ${baselineRecall.ratio()} → 退化 ${degradedRecall.ratio()}）",
                (degradedRecall.value ?: 1.0) <= (baselineRecall.value ?: 1.0),
            )
            val baselineHit = MemoryEvalMetrics.injectionHit(baseline)
            val degradedHit = MemoryEvalMetrics.injectionHit(degraded)
            assertTrue(
                "删一半后注入命中率不得上升（基线 ${baselineHit.ratio()} → 退化 ${degradedHit.ratio()}）",
                (degradedHit.value ?: 1.0) <= (baselineHit.value ?: 1.0),
            )
        }

    /** (c) 探针前把旧版本塞回记忆库 → 目标事实的旧版本被注入的后段探针数上升（过期注入率上升） */
    @Test
    fun `退化C_旧版本复活_过期注入上升`() =
        runBlocking {
            val candidates =
                sessions().filter { session ->
                    session.probes.any { session.probeClass(it) == ProbeClass.UPDATED }
                }
            assertTrue("需要有带后段探针的会话", candidates.isNotEmpty())
            // 基线：在若干会话里挑「目标事实旧版本已注入的后段探针数」最低的一个——
            // 新版夹具里旧版本注入接近饱和，选饱和度最低的会话退化才有上升空间
            var chosenSession: EvalSession? = null
            var chosenBaseline: EvalInput? = null
            var chosenCount = Int.MAX_VALUE
            for (session in candidates.take(4)) {
                val baseline = run(session, "退化C基线") { it }
                val count = targetStaleProbeCount(baseline)
                if (count < chosenCount) {
                    chosenCount = count
                    chosenSession = session
                    chosenBaseline = baseline
                }
            }
            val session = chosenSession!!
            val baseline = chosenBaseline!!
            val degraded = run(session, "退化C·旧版本复活") { it.copy(resurrectStaleVersionAtProbes = true) }
            val baselineCount = targetStaleProbeCount(baseline)
            val degradedCount = targetStaleProbeCount(degraded)
            val validUpdated =
                degraded.replays.single().replay.probes.count { it.valid && it.probeClass == ProbeClass.UPDATED }
            assertTrue("退化对照需要有有效的后段探针（会话 ${session.id}）", validUpdated > 0)
            assertTrue(
                "旧版本复活后「目标事实旧版本被注入的后段探针数」必须上升" +
                    "（会话 ${session.id}：基线 $baselineCount/$validUpdated → 退化 $degradedCount/$validUpdated）",
                degradedCount > baselineCount,
            )
            // 整体过期注入率不得因退化而下降
            val baselineStale = MemoryEvalMetrics.staleInjectionRowRate(baseline)
            val degradedStale = MemoryEvalMetrics.staleInjectionRowRate(degraded)
            assertTrue(
                "整体过期注入率不得下降（基线 ${baselineStale.ratio()} → 退化 ${degradedStale.ratio()}）",
                (degradedStale.value ?: 1.0) >= (baselineStale.value ?: 1.0),
            )
        }

    /** 后段有效探针里，「目标事实的旧版本」被注入的探针数（过期注入的探针级口径，限定目标事实） */
    private fun targetStaleProbeCount(input: EvalInput): Int =
        input.replays
            .flatMap { pair -> pair.replay.probes }
            .filter { it.valid && it.probeClass == ProbeClass.UPDATED }
            .count { probe ->
                MemoryEvalMetrics
                    .staleRowsInProbe(input, probe)
                    .any { it.factId == probe.probe.targetFactId }
            }
}
