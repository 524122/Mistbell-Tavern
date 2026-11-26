package com.mistbell.tavern.android.eval.memory

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.prompt.RecallDiagnostics
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.LocalEmbeddingService
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/** 毫秒换算（纳秒计时的展示口径） */
private const val NANOS_PER_MILLISECOND = 1_000_000L

/** logcat 输出 tag（总表与证据固定打到这里，方便从设备侧归档） */
private const val LOG_TAG = "DeviceMemEval"

/** 证据来源标签（如实：设备侧真栈 = 真 Room + 本地 ONNX embedding + sqlite-vec） */
private const val DEVICE_ORIGIN = "设备侧 / 真 ONNX·sqlite-vec"

/**
 * 设备侧召回通道的如实描述：真栈走 PromptBuilder 的混合召回，但词法路固定读真实容器库
 * （LexicalMemoryService 的 db 取 AppContainer），评测会话不在其中 → 词法命中恒为空，
 * 实际测到的是「向量路 + 结构化记忆选择」。
 */
private const val DEVICE_RECALL_CHANNEL = "向量路（词法路恒为空）"

/** instrumentation 开关：只有 -e evalRealStack true 时才运行（CI 托管设备不跑真 ONNX 栈） */
private const val GATE_ARG = "evalRealStack"

/**
 * 会话记忆评测 · 设备侧真栈（ORACLE 回放）：真 Room + 本地 ONNX embedding + 真 sqlite-vec。
 *
 * 与 JVM 侧（词法回退）共用同一评测核心（sharedTest），复用生产代码做「接变压器」以外的全部工作。
 * 证据断言（每会话，任一不满足即失败）：
 * 1. SqliteVecStore.probe() 为 true（vec0 真的加载）；
 * 2. embedding 是 LocalEmbeddingService、VectorMemoryService.available 为 true；
 * 3. 每个有效探针楼的 RecallDiagnostics：lexicalOnly=false、vectorAvailable=true
 *    （历史未裁剪、召回块被整块丢弃的楼单独计数，不判失败）；
 * 4. 向量库写入条数：消息向量 = 夹具楼数（每楼一条），摘要向量 = importance≥7 的落库记忆数——
 *    对不上即失败，绝不带着疑问出数字；
 * 5. 全部有效探针的向量候选合计 > 0（向量召回对注入确实有贡献）。
 * 另有 embedding 语义健全性检查（同义句 vs 无关句余弦）与反向验证
 * （Mock 栈下同一套断言必须失败，证明断言不是摆设）。
 *
 * ## 运行命令（本机，模拟器 Pixel_10 已启动时）
 * ```bash
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r -t app/build/outputs/apk/debug/app-x86_64-debug.apk
 * adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w \
 *   -e class com.mistbell.tavern.android.eval.memory.DeviceMemoryEvalReplayTest \
 *   -e evalRealStack true \
 *   com.mistbell.tavern.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 不带 `-e evalRealStack true` 时全部用例 Assume 跳过（CI 托管设备任务跑全部 androidTest，不跑本类）。
 * 报告写到被测应用的 cacheDir（/data/data/com.mistbell.tavern.android/cache/memory-eval-device/，
 * 缓存区而非 filesDir 用户数据区；instrumentation 在应用进程内跑，测试包私有目录应用 uid 写不了，
 * 实测 open 即 SQLiteCantOpenDatabaseException code 14）；总表同时以 tag [LOG_TAG] 打到 logcat。
 */
@RunWith(AndroidJUnit4::class)
class DeviceMemoryEvalReplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val targetContext get() = instrumentation.targetContext

    /**
     * 报告与临时文件目录。不能用 instrumentation.context.cacheDir（测试包的目录）：instrumentation
     * 跑在被测应用进程里（应用 uid），实测对该目录 open 即 SQLiteCantOpenDatabaseException(code 14)。
     * 这里用被测应用的 **cacheDir**（系统可随时清理的缓存区，不是 filesDir 用户数据区），
     * 结束后测试自己也会清理。
     */
    private val reportDir: File by lazy {
        File(targetContext.cacheDir, "memory-eval-device").apply { mkdirs() }
    }

    /** 每次运行独立的 owner id：评测数据与用户真实数据零交集（词法路触真实容器库时按 owner+会话隔离） */
    private val ownerId = "eval-device-${UUID.randomUUID().toString().take(8)}"

    private var savedGate: (suspend () -> Unit)? = null
    private var savedProvider: (() -> VectorMemoryService)? = null

    // ---- 用例 ----

    /** ORACLE · 全部 12 个会话 · 默认预算（夹具 2048）· 设备侧真栈 */
    @Test
    fun `ORACLE回放_设备侧真栈_默认预算`() =
        runBlocking {
            val access = requireRealStackGate()
            val embedding = LocalEmbeddingService.create(targetContext)
            val sanity = semanticSanity(embedding)
            assertTrue(
                "embedding 语义健全性检查失败：同义均值 ${"%.3f".format(sanity.synonymMean)} " +
                    "不高于无关均值 ${"%.3f".format(sanity.unrelatedMean)}（模型或分词可能坏了）",
                sanity.synonymMean > sanity.unrelatedMean,
            )
            val output =
                runDeviceReplay(
                    access = access,
                    mode = ExtractionMode.ORACLE,
                    budgetOverride = null,
                    recording = null,
                    label = "oracle-default",
                    reportName = "report-device-oracle-default.md",
                    embedding = embedding,
                    sqlite = true,
                    sanity = sanity,
                )
            assertEvidenceClean(output)
        }

    /** ORACLE · 预算覆盖 1024 对照（窗口只剩约 7 轮的极端情形） */
    @Test
    fun `ORACLE回放_设备侧真栈_预算1024对照`() =
        runBlocking {
            val access = requireRealStackGate()
            val embedding = LocalEmbeddingService.create(targetContext)
            val output =
                runDeviceReplay(
                    access = access,
                    mode = ExtractionMode.ORACLE,
                    budgetOverride = 1024,
                    recording = null,
                    label = "oracle-budget1024",
                    reportName = "report-device-oracle-budget1024.md",
                    embedding = embedding,
                    sqlite = true,
                )
            assertEvidenceClean(output)
        }

    /** RECORDED · 录制文件存在时设备侧回放；不存在时 Assume 跳过（不退回 ORACLE） */
    @Test
    fun `RECORDED回放_设备侧真栈_录制存在时`() =
        runBlocking {
            val access = requireRealStackGate()
            val content = access.readTextOrNull(MemoryEvalFixtures.RECORDING_PATH)
            Assume.assumeTrue(
                "跳过：录制文件 ${MemoryEvalFixtures.RECORDING_PATH} 不存在。" +
                    "先用 JVM 侧 MemoryExtractionRecorderTest 录制真实模型输出，再回来跑设备侧 RECORDED。",
                content != null,
            )
            val recording = ExtractionRecordingCodec.decode(requireNotNull(content))
            val embedding = LocalEmbeddingService.create(targetContext)
            val output =
                runDeviceReplay(
                    access = access,
                    mode = ExtractionMode.RECORDED,
                    budgetOverride = null,
                    recording = recording,
                    label = "recorded",
                    reportName = "report-device-recorded.md",
                    embedding = embedding,
                    sqlite = true,
                )
            assertEvidenceClean(output)
        }

    /**
     * 反向验证：把向量栈换成 MockEmbeddingService + 内存向量库再跑一个会话，
     * 上面的证据断言必须失败——证明它们不是摆设（失败原文打到 logcat 与 stdout）。
     */
    @Test
    fun `反向验证_MockEmbedding栈_证据断言必须失败`() =
        runBlocking {
            val access = requireRealStackGate()
            val loaded = MemoryEvalFixtures.load(resolverOf(access))
            Assume.assumeTrue("没有任何可用夹具会话（${loaded.summary}）", loaded.sessions.isNotEmpty())
            val session = loaded.sessions.first()
            val stack =
                DeviceSessionStack.create(targetContext, reportDir, MockEmbeddingService(), "reverse", sqlite = false)
            installPromptVars(stack.vectorMemoryService)
            try {
                val config =
                    DriverConfig(ExtractionMode.ORACLE, pendingExtractionFile = File(reportDir, "pending-reverse.json"))
                val replay = MemoryEvalDriver(stack.pipeline, ownerId).replay(session, stack.driverDb, config)
                val evidence = collectEvidence(stack, replay, replayElapsedMs = 0)
                assertTrue(
                    "反向验证失败：Mock embedding + 内存向量库下证据断言竟然全部通过——断言没有在测真栈",
                    evidence.violations.isNotEmpty(),
                )
                evidence.violations.forEach { violation ->
                    Log.i(LOG_TAG, "反向验证捕获断言原文: $violation")
                    println("反向验证捕获断言原文: $violation")
                }
            } finally {
                stack.close()
            }
        }

    // ---- 运行器 ----

    /** 默认跳过的闸门 + 夹具读取探测（读不到会在这里明确失败并列出试过的读取方式） */
    private fun requireRealStackGate(): DeviceEvalResourceAccess {
        Assume.assumeTrue(
            "跳过：设备侧真 ONNX 评测只在 `-e $GATE_ARG true` 时运行" +
                "（CI 托管设备任务会执行全部 androidTest，不要让它每次推送都跑真栈）。" +
                "运行命令见 DeviceMemoryEvalReplayTest 类 KDoc。",
            gateArg(),
        )
        return DeviceEvalResourceAccess.resolve(instrumentation)
    }

    private fun gateArg(): Boolean = InstrumentationRegistry.getArguments().getString(GATE_ARG) == "true"

    private fun resolverOf(access: DeviceEvalResourceAccess): EvalResourceResolver =
        EvalResourceResolver { path -> access.openOrNull(path) }

    /** PromptBuilder 的两个 internal var：回放前注入本会话的向量服务与放行闸门，结束恢复原值 */
    private fun installPromptVars(vectorMemoryService: VectorMemoryService) {
        if (savedGate == null) {
            savedGate = PromptBuilder.memoryResetGate
            savedProvider = PromptBuilder.vectorMemoryServiceProvider
        }
        PromptBuilder.memoryResetGate = {} // 记忆链就绪（评测环境没有升级清零）
        PromptBuilder.vectorMemoryServiceProvider = { vectorMemoryService }
    }

    @After
    fun restorePromptVars() {
        savedGate?.let { PromptBuilder.memoryResetGate = it }
        savedProvider?.let { PromptBuilder.vectorMemoryServiceProvider = it }
        savedGate = null
        savedProvider = null
    }

    /** 一次设备侧回放运行（每会话独立真栈）+ 报告生成 + logcat 总表 */
    private suspend fun runDeviceReplay(
        access: DeviceEvalResourceAccess,
        mode: ExtractionMode,
        budgetOverride: Int?,
        recording: ExtractionRecording?,
        label: String,
        reportName: String,
        embedding: EmbeddingService,
        sqlite: Boolean,
        sanity: SemanticSanity? = null,
    ): DeviceRunOutput {
        val loaded = MemoryEvalFixtures.load(resolverOf(access))
        Assume.assumeTrue("没有任何可用夹具会话（${loaded.summary}）", loaded.sessions.isNotEmpty())
        val replays = mutableListOf<ReplayWithFixture>()
        val evidences = mutableListOf<SessionEvidence>()
        loaded.sessions.forEachIndexed { index, session ->
            val stack =
                DeviceSessionStack.create(targetContext, reportDir, embedding, "$label-$index", sqlite = sqlite)
            installPromptVars(stack.vectorMemoryService)
            try {
                val config =
                    DriverConfig(
                        mode,
                        contextTokenLimitOverride = budgetOverride,
                        recording = recording,
                        pendingExtractionFile = File(reportDir, "pending-$label-${session.id}.json"),
                    )
                val startedAt = System.nanoTime()
                val replay = MemoryEvalDriver(stack.pipeline, ownerId).replay(session, stack.driverDb, config)
                val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
                val evidence = collectEvidence(stack, replay, elapsedMs)
                replays += ReplayWithFixture(replay, session)
                evidences += evidence
                logSessionLine(evidence)
            } finally {
                stack.close()
            }
        }
        val input =
            EvalInput(
                mode = mode,
                replays = replays,
                fixtureFailures = loaded.loadFailures,
                recallChannel = DEVICE_RECALL_CHANNEL,
                runLabel = label,
                origin = DEVICE_ORIGIN,
            )
        val markdown =
            MemoryEvalReport.render(input) + deviceAppendix(input, evidences, sanity, label, fixtureVia = access.via)
        val reportFile = File(reportDir, reportName)
        reportFile.writeText(markdown)
        logSummary(input)
        println("设备侧报告已写入: ${reportFile.absolutePath}")
        Log.i(LOG_TAG, "设备侧报告已写入: ${reportFile.absolutePath}")
        return DeviceRunOutput(input, evidences, reportFile)
    }

    // ---- 证据 ----

    /** 一次设备侧运行（若干会话）的产物 */
    private class DeviceRunOutput(
        val input: EvalInput,
        val evidences: List<SessionEvidence>,
        val reportFile: File,
    )

    /** 一个会话回放的设备侧证据（含 violations；空 = 证据断言全过） */
    private class SessionEvidence(
        val sessionId: String,
        val sqliteProbeOk: Boolean?,
        val embeddingType: String,
        val available: Boolean,
        val replayElapsedMs: Long,
        val floorCount: Int,
        val messageVectorIds: Int,
        val summaryVectorIds: Int,
        val storeRowCount: Int,
        val expectedSummaryIds: Int,
        val validProbes: Int,
        val historyIntactDropped: Int,
        val probesWithVectorCandidates: Int,
        val vectorCandidatesSum: Int,
        val syncStats: DeviceSyncStats,
        val messageWrites: Int,
        val summaryWrites: Int,
        val suppressedWrites: Int,
        val searchCount: Int,
        val searchTotalMs: Long,
        val violations: List<String>,
    )

    /** 逐会话收集证据（probe / embedding / available / 向量条数 / 诊断 / 计数计时 / violations） */
    private suspend fun collectEvidence(
        stack: DeviceSessionStack,
        replay: SessionReplay,
        replayElapsedMs: Long,
    ): SessionEvidence {
        val indexedIds = stack.vectorMemoryService.indexedMessageIds()
        val messageVectorIds = indexedIds.count { !it.startsWith("structured_memory_") }
        val summaryVectorIds = indexedIds.count { it.startsWith("structured_memory_") }
        val storeRowCount = stack.countingStore.count()
        val expectedSummaryIds = replay.finalMemories.count { it.importance >= 7 }
        // 驱动器逐楼插入第 2..N 楼（第 1 楼开场白由 seed 的会话行承载，不入消息表——
        // 与生产"开场白不参与攒批"同一口径），所以入库消息数 = floorCount - 1
        val expectedMessageIds = replay.floorCount - 1

        val problems = mutableListOf<String>()
        if (stack.sqliteProbeOk == false) problems += "SqliteVecStore.probe()=false（vec0 扩展加载失败）"
        if (stack.embedding !is LocalEmbeddingService) {
            problems += "embedding 不是 LocalEmbeddingService：${stack.embedding.javaClass.simpleName}"
        }
        if (!stack.vectorMemoryService.available) problems += "VectorMemoryService.available=false"
        if (messageVectorIds != expectedMessageIds) {
            problems += "消息向量 $messageVectorIds 条 != 入库消息总数 $expectedMessageIds（向量同步没按生产时序排空）"
        }
        if (summaryVectorIds != expectedSummaryIds) {
            problems += "摘要向量 $summaryVectorIds 条 != importance≥7 的落库记忆 $expectedSummaryIds 条"
        }

        var historyIntactDropped = 0
        var probesWithVectorCandidates = 0
        var vectorCandidatesSum = 0
        val validProbes = replay.probes.filter { it.valid }
        validProbes.forEach { probe ->
            val diag = probe.recallDiagnostics
            when {
                diag == null -> problems += "探针楼 ${probe.probe.floor}：缺 RecallDiagnostics（驱动器未透传诊断）"
                diag.droppedReason == RecallDiagnostics.DROP_HISTORY_INTACT -> historyIntactDropped++
                else -> {
                    if (diag.lexicalOnly) problems += "探针楼 ${probe.probe.floor}：lexicalOnly=true（走了词法回退）"
                    if (!diag.vectorAvailable) problems += "探针楼 ${probe.probe.floor}：vectorAvailable=false"
                }
            }
            if (diag != null) {
                vectorCandidatesSum += diag.vectorCandidates
                if (diag.vectorCandidates > 0) probesWithVectorCandidates++
            }
        }
        if (validProbes.isNotEmpty() && vectorCandidatesSum <= 0 && historyIntactDropped < validProbes.size) {
            problems += "全部有效探针的向量候选合计为 0（向量召回对注入没有任何贡献）"
        }

        return SessionEvidence(
            sessionId = replay.sessionId,
            sqliteProbeOk = stack.sqliteProbeOk,
            embeddingType = stack.embedding.javaClass.simpleName,
            available = stack.vectorMemoryService.available,
            replayElapsedMs = replayElapsedMs,
            floorCount = replay.floorCount,
            messageVectorIds = messageVectorIds,
            summaryVectorIds = summaryVectorIds,
            storeRowCount = storeRowCount,
            expectedSummaryIds = expectedSummaryIds,
            validProbes = validProbes.size,
            historyIntactDropped = historyIntactDropped,
            probesWithVectorCandidates = probesWithVectorCandidates,
            vectorCandidatesSum = vectorCandidatesSum,
            syncStats = stack.syncStats,
            messageWrites = stack.countingStore.messageWriteCount,
            summaryWrites = stack.countingStore.summaryWriteCount,
            suppressedWrites = stack.countingStore.suppressedWriteCount,
            searchCount = stack.countingStore.searchCount,
            searchTotalMs = stack.countingStore.searchTotalMs,
            violations = problems,
        )
    }

    /** 证据断言：任一会话有 violations 即失败（带全部原文） */
    private fun assertEvidenceClean(output: DeviceRunOutput) {
        val allViolations = output.evidences.flatMap { evidence -> evidence.violations.map { "${evidence.sessionId}：$it" } }
        assertTrue(
            "设备侧证据断言失败（${allViolations.size} 条）：${allViolations.joinToString("；")}",
            allViolations.isEmpty(),
        )
    }

    // ---- embedding 语义健全性 ----

    /** 语义健全性检查的读数 */
    class SemanticSanity(
        val synonymScores: List<Double>,
        val unrelatedScores: List<Double>,
    ) {
        val synonymMean: Double get() = synonymScores.average()

        val unrelatedMean: Double get() = unrelatedScores.average()
    }

    /**
     * 「换说法的同义句」应明显高于「无关句」的余弦相似度（bge-small-zh 的基本语义能力）。
     * 数字整体写进报告附录；只断言均值同义 > 无关。
     */
    private suspend fun semanticSanity(embedding: EmbeddingService): SemanticSanity {
        val synonyms =
            listOf(
                "我从小就对花生过敏，吃了要进医院的" to "花生会引发我的过敏，千万别往我碗里放",
                "我叫沈砚，靠修钟表过活" to "我的名字是沈砚，是个修钟表的匠人",
                "上个月我搬到了城东的青石巷" to "如今我住在城东的青石巷",
                "打雷的夜晚我总是睡不着" to "雷雨夜里我根本无法入睡",
            )
        val unrelated =
            listOf(
                "我从小就对花生过敏，吃了要进医院的" to "城南当铺今早挂出了新招牌",
                "我叫沈砚，靠修钟表过活" to "码头的货船比昨天多了一倍",
                "上个月我搬到了城东的青石巷" to "茶馆的说书先生换了人",
                "打雷的夜晚我总是睡不着" to "集市上的鲤鱼今天很新鲜",
            )
        val synonymScores = synonyms.map { (a, b) -> cosine(embedding.embed(a), embedding.embed(b)) }
        val unrelatedScores = unrelated.map { (a, b) -> cosine(embedding.embed(a), embedding.embed(b)) }
        return SemanticSanity(synonymScores, unrelatedScores)
    }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0.0 else dot / denominator
    }

    // ---- 报告附录（设备侧证据 + JVM 对照）----

    /** JVM 侧参考数字（docs/MEMORY_EVAL.md 第五节：JVM / 回放夹具 / ORACLE·词法回退，夹具 a80cb5c，2026-11-27） */
    private class JvmReferenceRow(val metric: String, val budget2048: String, val budget1024: String)

    private val jvmReference: List<JvmReferenceRow> =
        listOf(
            JvmReferenceRow("有效探针", "91/144", "135/144"),
            JvmReferenceRow("抽取召回率", "480/480 = 100%", "100%"),
            JvmReferenceRow("注入命中率", "25/91 = 27.5%", "58/135 = 43.0%"),
            JvmReferenceRow("注入命中率·更新版本探针", "6/48 = 12.5%", "6/48 = 12.5%"),
            JvmReferenceRow("命中·前/中/后段", "7/11 · 12/32 · 6/48", "38/51 · 14/36 · 6/48"),
            JvmReferenceRow("过期注入率·行级", "336/929 = 36.2%", "349/1229 = 28.4%"),
            JvmReferenceRow("过期注入率·探针级（后段）", "48/48 = 100%", "48/48 = 100%"),
            JvmReferenceRow("重述负例·恰好一条", "0/148 = 0%", "0/148 = 0%"),
            JvmReferenceRow("重述负例·探针仍命中", "3/6", "23/33"),
            JvmReferenceRow("噪声率", "154/1083 = 14.2%", "186/1415 = 13.1%"),
            JvmReferenceRow("每有效探针楼的注入记忆 token", "255", "225"),
            JvmReferenceRow("抽取调用", "600", "600"),
        )

    private fun pctOf(value: Double?): String = if (value == null) "n/a" else "%.1f%%".format(value * 100)

    private fun ratioOf(metric: MetricValue): String =
        if (metric.denominator == 0) "n/a" else "${metric.numerator}/${metric.denominator} = ${pctOf(metric.value)}"

    /** 设备侧报告附录：证据 + 向量化耗时 + 每会话耗时 + JVM 对照 + 与夹具规模的关系 */
    private fun deviceAppendix(
        input: EvalInput,
        evidences: List<SessionEvidence>,
        sanity: SemanticSanity?,
        label: String,
        fixtureVia: String,
    ): String {
        val lines = mutableListOf<String>()
        val device = "设备侧（本报告）"
        val jvmColumn =
            if (input.replays.firstOrNull()?.replay?.contextTokenLimit == 1024) {
                JvmReferenceRow::budget1024
            } else {
                JvmReferenceRow::budget2048
            }
        val deviceMetricCells =
            listOf(
                "有效探针" to
                    "${MemoryEvalMetrics.validProbes(input)}/${MemoryEvalMetrics.totalProbes(input)}",
                "抽取召回率" to ratioOf(MemoryEvalMetrics.extractionRecall(input)),
                "注入命中率" to ratioOf(MemoryEvalMetrics.injectionHit(input)),
                "注入命中率·更新版本探针" to ratioOf(MemoryEvalMetrics.injectionHitOnUpdatedProbes(input)),
                "命中·前/中/后段" to classHitCell(input),
                "过期注入率·行级" to ratioOf(MemoryEvalMetrics.staleInjectionRowRate(input)),
                "过期注入率·探针级（后段）" to ratioOf(MemoryEvalMetrics.staleInjectionProbeRate(input)),
                "重述负例·恰好一条" to ratioOf(MemoryEvalMetrics.restatementDedup(input)),
                "重述负例·探针仍命中" to ratioOf(MemoryEvalMetrics.restatementHit(input)),
                "噪声率" to ratioOf(MemoryEvalMetrics.noiseRate(input)),
                "每有效探针楼的注入记忆 token" to
                    (MemoryEvalMetrics.avgInjectedTokensPerValidProbe(input)?.let { "%.0f".format(it) } ?: "n/a"),
                "抽取调用" to MemoryEvalMetrics.extractionCalls(input).toString(),
            )

        lines += ""
        lines += "## 设备侧证据（androidTest 附加节）"
        lines += ""
        lines += "- 运行标签：$label；owner=$ownerId（每运行独立，评测数据与用户真实数据零交集）"
        lines += "- 运行命令：见 DeviceMemoryEvalReplayTest 类 KDoc（`-e evalRealStack true`）"
        lines += "- 夹具读取方式：$fixtureVia"
        lines += "- 报告与临时文件：被测应用 cacheDir/memory-eval-device/（缓存区，非 filesDir 用户数据区；" +
            "instrumentation 在应用进程内跑，测试包私有目录应用 uid 不可写）"
        lines += ""
        lines += "### 真栈证据（逐会话）"
        lines += ""
        lines += "| 会话 | vec0 probe | embedding | available | 消息向量 | 摘要向量(期望) | 有效探针 | 向量候选>0 楼 | 历史未裁剪弃召回 | 向量候选合计 | 违规 |"
        lines += "|---|---|---|---|---|---|---|---|---|---|---|"
        evidences.forEach { evidence ->
            val cells =
                listOf(
                    evidence.sessionId,
                    "${evidence.sqliteProbeOk}",
                    evidence.embeddingType,
                    "${evidence.available}",
                    "${evidence.messageVectorIds}/${evidence.floorCount - 1}",
                    "${evidence.summaryVectorIds}(${evidence.expectedSummaryIds})",
                    "${evidence.validProbes}",
                    "${evidence.probesWithVectorCandidates}",
                    "${evidence.historyIntactDropped}",
                    "${evidence.vectorCandidatesSum}",
                    "${evidence.violations.size}",
                )
            lines += "| " + cells.joinToString(" | ") + " |"
        }
        lines += ""
        lines += "### 向量库写入与夹具规模的关系"
        lines += ""
        val messageTotal = evidences.sumOf { it.messageVectorIds }
        val summaryTotal = evidences.sumOf { it.summaryVectorIds }
        val insertedTotal = evidences.sumOf { it.floorCount - 1 }
        val summaryExpectedTotal = evidences.sumOf { it.expectedSummaryIds }
        lines += "- 消息向量 $messageTotal 条 = 入库消息总数 $insertedTotal" +
            "（${insertedTotal / evidences.size} 楼/会话 × ${evidences.size} 会话；第 2..N 楼逐楼入库，" +
            "第 1 楼开场白不入消息表；插入触发器 → outbox → recoverSource 逐条排空，断言逐会话相等）"
        lines += "- 摘要向量 $summaryTotal 条 = importance≥7 的落库记忆总数 $summaryExpectedTotal" +
            "（ORACLE 定档下只有 identity/core/goal 等高档类型 ≥7；importance<7 的记忆触发 DELETE 清理操作，" +
            "本来就不该有向量副本，断言逐会话相等）"
        val writes = evidences.sumOf { it.messageWrites + it.summaryWrites }
        val suppressed = evidences.sumOf { it.suppressedWrites }
        lines += "- 存储层成功写入 $writes 次（消息 ${evidences.sumOf { it.messageWrites }} + 摘要 ${evidences.sumOf { it.summaryWrites }}），" +
            "被闸门压制 $suppressed 次，存储层总行数 ${evidences.sumOf { it.storeRowCount }}；" +
            "探针楼自己的消息向量在装配前已落库（生产为异步竞态，评测为理想时序，偏差方向 = 乐观）"
        lines += ""
        lines += "### 向量化与检索耗时（合计）"
        lines += ""
        val messageSyncMs = evidences.sumOf { it.syncStats.messageSyncTotalMs }
        val summarySyncMs = evidences.sumOf { it.syncStats.summarySyncTotalMs }
        val searchCount = evidences.sumOf { it.searchCount }
        val searchMs = evidences.sumOf { it.searchTotalMs }
        lines += "- 消息向量排空 ${evidences.sumOf { it.syncStats.messageSyncCount }} 次 / $messageSyncMs ms（含 embedding + 提交 + flush）"
        lines += "- 摘要向量排空 ${evidences.sumOf { it.syncStats.summarySyncCount }} 次 / $summarySyncMs ms"
        lines += "- 向量检索 $searchCount 次 / $searchMs ms（含查询向量化；每次探针装配一次）"
        lines += ""
        lines += "### 每个会话的回放耗时"
        lines += ""
        lines += "| 会话 | 回放耗时 ms |"
        lines += "|---|---|"
        evidences.forEach { evidence -> lines += "| ${evidence.sessionId} | ${evidence.replayElapsedMs} |" }
        lines += ""
        if (sanity != null) {
            lines += "### embedding 语义健全性（LocalEmbeddingService / bge-small-zh-v1.5-q，512 维）"
            lines += ""
            lines += "- 同义对余弦：${sanity.synonymScores.joinToString("、") { "%.3f".format(it) }}（均值 ${"%.3f".format(sanity.synonymMean)}）"
            lines += "- 无关对余弦：${sanity.unrelatedScores.joinToString("、") { "%.3f".format(it) }}（均值 ${"%.3f".format(sanity.unrelatedMean)}）"
            lines += "- 断言：同义均值 > 无关均值（通过）"
            lines += ""
        }
        lines += "### 与 JVM 侧数字的对照（JVM 数字取自 docs/MEMORY_EVAL.md 第五节）"
        lines += ""
        lines += "| 指标 | $device | JVM / 词法回退（同预算） |"
        lines += "|---|---|---|"
        deviceMetricCells.forEach { (metric, deviceCell) ->
            val jvmRow = jvmReference.first { it.metric == metric }
            lines += "| $metric | $deviceCell | ${jvmColumn.get(jvmRow)} |"
        }
        lines += ""
        lines += "### 设备侧读数的已知失真（如实披露）"
        lines += ""
        lines += "- **摘要副本会以召回行注入（设备侧新观察，生产同款行为）**：importance≥7 的记忆在向量库里" +
            "有一份 summary 副本，混合召回会把它当召回行注入（提示词里是\"标题: 内容 [类型] (similarity: X%)\"），" +
            "注入清单里对应 structured_memory_<id>。评测按其向量正文匹配回事实，与结构化块的注入分开计数" +
            "（source=会话记忆·召回）。JVM 侧看不到这条路（没有向量）。"
        lines += "- 生产 MemoryExtractionService 的模板选择读**真实 tavern.db** 的 memory_extraction_prompt 设置" +
            "（构造期字段，评测无法注入测试库；只读单键）。前缀哨兵保证提示词是默认模板，否则测试明确失败。"
        lines += "- PromptBuilder 的词法路固定走真实容器库（LexicalMemoryService 的 db 取 AppContainer），" +
            "评测会话不在其中 → 词法路命中恒为空：设备侧「混合召回」实际测的是**向量路 + 结构化记忆选择**，" +
            "恰与 JVM 侧「只有词法路」互补。与用户真机的差异：真机词法路会有命中参与 RRF 融合。"
        lines += "- 探针楼自己的消息向量在装配前已同步（生产是异步竞态）：检索可能召回探针原话自身，" +
            "占用一个召回名额，对全部探针是同向的乐观偏差。"
        lines += "- 抽取时序仍是理想情况：探针楼装配时，之前所有批次的抽取与摘要同步都已完成（与 JVM 侧同口径）。"
        lines += ""
        return lines.joinToString("\n")
    }

    private fun classHitCell(input: EvalInput): String =
        MemoryEvalMetrics
            .hitByClass(input)
            .joinToString(" · ") { "${it.hits}/${it.valid}" }

    // ---- logcat 输出 ----

    private fun logSessionLine(evidence: SessionEvidence) {
        val line =
            "| ${evidence.sessionId} | 消息向量 ${evidence.messageVectorIds}/${evidence.floorCount - 1} " +
                "| 摘要向量 ${evidence.summaryVectorIds}(${evidence.expectedSummaryIds}) " +
                "| 有效探针 ${evidence.validProbes} | 向量候选 ${evidence.vectorCandidatesSum} " +
                "| 耗时 ${evidence.replayElapsedMs}ms | 违规 ${evidence.violations.size} |"
        Log.i(LOG_TAG, line)
        println(line)
    }

    private fun logSummary(input: EvalInput) {
        val lines =
            listOf(
                "===== 设备侧会话记忆评测（${MemoryEvalMetrics.sourceLabel(input)}）=====",
                "| 会话 | 有效/探针 | 抽取召回率 | 注入命中率 | 过期注入(行) | 噪声率 |",
                "|---|---|---|---|---|---|",
            )
        lines.forEach { line ->
            Log.i(LOG_TAG, line)
            println(line)
        }
        input.replays.forEach { pair ->
            val one =
                EvalInput(
                    mode = input.mode,
                    replays = listOf(pair),
                    recallChannel = input.recallChannel,
                    origin = input.origin,
                )
            val line =
                "| ${pair.replay.sessionId} | ${MemoryEvalMetrics.validProbes(one)}/${MemoryEvalMetrics.totalProbes(one)} " +
                    "| ${ratioOf(MemoryEvalMetrics.extractionRecall(one))} " +
                    "| ${ratioOf(MemoryEvalMetrics.injectionHit(one))} " +
                    "| ${ratioOf(MemoryEvalMetrics.staleInjectionRowRate(one))} " +
                    "| ${ratioOf(MemoryEvalMetrics.noiseRate(one))} |"
            Log.i(LOG_TAG, line)
            println(line)
        }
        val summary =
            "合计: 有效探针 ${MemoryEvalMetrics.validProbes(input)}/${MemoryEvalMetrics.totalProbes(input)} | " +
                "抽取召回率 ${ratioOf(MemoryEvalMetrics.extractionRecall(input))} | " +
                "注入命中率 ${ratioOf(MemoryEvalMetrics.injectionHit(input))} | " +
                "噪声率 ${ratioOf(MemoryEvalMetrics.noiseRate(input))} | " +
                "抽取调用 ${MemoryEvalMetrics.extractionCalls(input)} 次"
        Log.i(LOG_TAG, summary)
        println(summary)
        println(MemoryEvalReport.gateLine(input))
        Log.i(LOG_TAG, MemoryEvalReport.gateLine(input))
    }
}
