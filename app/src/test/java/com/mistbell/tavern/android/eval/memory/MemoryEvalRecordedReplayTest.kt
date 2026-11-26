package com.mistbell.tavern.android.eval.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * 会话记忆回放测试（RECORDED）：真实模型对同一批对话产出的抽取输出回放。
 * 录制文件不存在时 Assume 跳过（并在控制台说明怎么录制）；存在时跑全部被覆盖的会话并写报告。
 */
class MemoryEvalRecordedReplayTest {
    @After
    fun tearDown() {
        EvalRuntime.restore()
    }

    @Test
    fun `RECORDED回放_录制覆盖的会话`() =
        runBlocking {
            val loaded = MemoryEvalFixtures.load(JvmEvalResources.resolver())
            val recordingContent =
                JvmEvalResources
                    .resolver()
                    .open(MemoryEvalFixtures.RECORDING_PATH)
                    ?.use { it.readBytes().decodeToString() }
            Assume.assumeTrue(
                "抽取录制文件不存在（app/src/sharedTest/resources/eval/memory/extraction-recording.json）。" +
                    "录制方法：设置环境变量 MISTBELL_EVAL_API_KEY / MISTBELL_EVAL_BASE_URL / MISTBELL_EVAL_MODEL " +
                    "（可选 MISTBELL_EVAL_SESSIONS、MISTBELL_EVAL_CONCURRENCY）后运行 MemoryExtractionRecorderTest。",
                recordingContent != null,
            )
            val recording = ExtractionRecordingCodec.decode(recordingContent!!)
            val sessions = loaded.sessions.filter { recording.hasSession(it.id) }
            Assume.assumeTrue(
                "录制文件存在但不含任何夹具会话的批次（model=${recording.model}，会话=${recording.batches.keys}）",
                sessions.isNotEmpty(),
            )
            val reportDir = File(MemoryEvalRepo.root(), "app/build/reports/memory-eval")
            val config =
                DriverConfig(
                    ExtractionMode.RECORDED,
                    recording = recording,
                    pendingExtractionFile = File(reportDir, "pending-extraction-recorded.json"),
                )
            val replays = MemoryEvalReplayRunner.replayAll(sessions, config)
            val input =
                EvalInput(
                    ExtractionMode.RECORDED,
                    replays,
                    loaded.loadFailures,
                    runLabel = "RECORDED·真实模型录制回放",
                    recordingModel = recording.model,
                )
            MemoryEvalReport.writeAndPrint(input, File(reportDir, "report-recorded.md"))

            // 管线还在断言（与 ORACLE 同一判据的轻量版）
            input.replays.forEach { pair ->
                assertEquals(
                    "会话 ${pair.replay.sessionId}：探针楼必须全部装配",
                    pair.fixture.probes.map { it.floor }.sorted(),
                    pair.replay.probes.map { it.probe.floor }.sorted(),
                )
            }
            assertTrue(
                "RECORDED 回放没有有效探针，指标会是空分母",
                MemoryEvalMetrics.validProbes(input) > 0,
            )
            println(MemoryEvalReport.gateLine(input))
        }
}
