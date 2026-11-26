package com.mistbell.tavern.android.eval.memory

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.withCallOverrides
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.service.MemoryExtractionService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实模型抽取输出录制（发版探针的证据来源之一；只在配置了环境变量时运行）。
 *
 * - 只在 `MISTBELL_EVAL_API_KEY` 存在时运行，否则 Assume 跳过；
 * - 提示词用真实的 [MemoryExtractionService.buildMemoryExtractionPrompt] 与 [buildDialogueText]，
 *   网络调用走真实的 [LlmClient.chat]，采样参数与生产抽取完全一致（温度 0.2 / 输出预算 4096 /
 *   关 thinking，与 MemoryExtractionService.extractMemoryCandidatesWithLLM 同款覆盖）；
 * - 输出写 `app/src/sharedTest/resources/eval/memory/extraction-recording.json`，支持断点续录
 *   （提示词摘要一致的已有批次跳过）；
 * - **密钥只从环境变量读，绝不写入任何文件或日志**（录制文件只有 model + 摘要 + 原始响应）。
 */
class MemoryExtractionRecorderTest {
    private companion object {
        /** 与生产 MemoryExtractionService NO_REASONING_SUFFIX 逐字一致（空正文重试用；源为 private，故镜像） */
        const val NO_REASONING_SUFFIX =
            "\n\n注意：直接输出上述 JSON，不要输出任何思考、分析或解释过程。"

        /** 抽取采样覆盖：与生产 extractMemoryCandidatesWithLLM 的 withCallOverrides 完全一致 */
        const val EXTRACTION_TEMPERATURE = 0.2
        const val EXTRACTION_MAX_TOKENS = 4096
    }

    /** 环境变量解析结果（密钥只在内存，不落日志） */
    private data class RecorderEnv(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val sessionFilter: Set<String>?,
        val concurrency: Int,
        val recordingFile: File,
    )

    private data class RecordJob(
        val sessionId: String,
        val batch: MemoryEvalBatches.EvalBatch,
    )

    @Test
    fun `录制真实模型抽取输出`() =
        runBlocking {
            val env = resolveEnvOrAssumeSkip()
            val extractionService = setupExtractionService()
            val loaded = MemoryEvalFixtures.load(JvmEvalResources.resolver())
            val sessions = sessionsToRecord(loaded, env)
            val recording = loadExistingRecording(env.recordingFile)
            val jobs = pendingJobs(sessions, recording)
            println(
                "抽取录制：${jobsTotal(sessions)} 批（已录 ${jobsTotal(sessions) - jobs.size}，本次录制 ${jobs.size}），" +
                    "并发=${env.concurrency}，model=${env.model}，输出=${env.recordingFile}",
            )
            val merged = recordAll(env, extractionService, jobs, recording)
            writeRecording(env.recordingFile, merged, jobs.size)
        }

    /** 环境变量齐备才继续；缺 MISTBELL_EVAL_API_KEY 时 Assume 跳过（密钥不进异常消息之外的位置） */
    private fun resolveEnvOrAssumeSkip(): RecorderEnv {
        val apiKey = System.getenv("MISTBELL_EVAL_API_KEY")
        Assume.assumeTrue(
            "未设置 MISTBELL_EVAL_API_KEY，跳过抽取录制。录制方法：export MISTBELL_EVAL_API_KEY=… " +
                "MISTBELL_EVAL_BASE_URL=… MISTBELL_EVAL_MODEL=…（可选 MISTBELL_EVAL_SESSIONS=s01,s02、" +
                "MISTBELL_EVAL_CONCURRENCY=4）后运行本测试。",
            !apiKey.isNullOrBlank(),
        )
        val baseUrl = requireNotNull(System.getenv("MISTBELL_EVAL_BASE_URL")) { "缺少 MISTBELL_EVAL_BASE_URL" }
        val model = requireNotNull(System.getenv("MISTBELL_EVAL_MODEL")) { "缺少 MISTBELL_EVAL_MODEL" }
        val sessionFilter =
            System.getenv("MISTBELL_EVAL_SESSIONS")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
        val concurrency = (System.getenv("MISTBELL_EVAL_CONCURRENCY")?.toIntOrNull() ?: 4).coerceAtLeast(1)
        return RecorderEnv(apiKey, baseUrl, model, sessionFilter, concurrency, recordingFile())
    }

    /** 提示词依赖生产代码的取库路径（自定义抽取提示词设置）；装一个空设置的替身库即得到默认模板 */
    private fun setupExtractionService(): MemoryExtractionService {
        val fakeDb = FakeTavernDb()
        val vectorMemoryService: VectorMemoryService = EvalRuntime.installSession(fakeDb)
        val database = fakeDb.db()
        val repository =
            StructuredMemoryRepository(
                context = EvalContext(),
                resetGate = {},
                db = database,
                vectorMemoryService = vectorMemoryService,
            )
        return MemoryExtractionService(EvalContext(), repository)
    }

    private fun sessionsToRecord(
        loaded: LoadedFixtures,
        env: RecorderEnv,
    ): List<EvalSession> {
        Assume.assumeTrue("夹具不可用（${loaded.summary}）", loaded.sessions.isNotEmpty())
        val sessions =
            env.sessionFilter?.let { filter -> loaded.sessions.filter { it.id in filter } } ?: loaded.sessions
        Assume.assumeTrue(
            "MISTBELL_EVAL_SESSIONS=${env.sessionFilter} 与夹具无交集（可用：${loaded.sessions.map { it.id }}）",
            sessions.isNotEmpty(),
        )
        return sessions
    }

    private fun jobsTotal(sessions: List<EvalSession>): Int = batchesOf(sessions).size

    private fun batchesOf(sessions: List<EvalSession>): List<MemoryEvalBatches.EvalBatch> =
        sessions.flatMap { MemoryEvalBatches.planBatches(it) }

    private fun loadExistingRecording(file: File): ExtractionRecording =
        if (!file.isFile) {
            ExtractionRecording()
        } else {
            runCatching { ExtractionRecordingCodec.decode(file.readText()) }
                .getOrElse {
                    println("已有录制文件损坏（${it.message}），重新录制全部批次")
                    ExtractionRecording()
                }
        }

    /** 断点续录：只录提示词摘要不在已有录制里的批次 */
    private fun pendingJobs(
        sessions: List<EvalSession>,
        recording: ExtractionRecording,
    ): List<RecordJob> =
        sessions
            .flatMap { session ->
                MemoryEvalBatches.planBatches(session).map { batch -> RecordJob(session.id, batch) }
            }
            .filter { (sessionId, batch) -> recording.batch(sessionId, batch.index) == null }

    /** 并发录制全部待录批次；任何失败都不写入（重跑可断点续录） */
    private suspend fun recordAll(
        env: RecorderEnv,
        extractionService: MemoryExtractionService,
        jobs: List<RecordJob>,
        existing: ExtractionRecording,
    ): ExtractionRecording {
        val recorded = existing.batches.mapValues { it.value.toMutableMap() }.toMutableMap()
        val failures = AtomicInteger(0)
        val config =
            LlmConfig(
                baseUrl = env.baseUrl,
                apiKey = env.apiKey,
                model = env.model,
                type = "openai",
                streamingEnabled = false,
            ).withCallOverrides(
                temperature = EXTRACTION_TEMPERATURE,
                maxTokens = EXTRACTION_MAX_TOKENS,
                disableThinking = true,
            )
        val semaphore = Semaphore(env.concurrency)
        val session = RecordSession(env, extractionService, config, recorded, failures)
        coroutineScope {
            jobs
                .map { job ->
                    async {
                        semaphore.withPermit {
                            session.record(job)
                        }
                    }
                }.awaitAll()
        }
        assertTrue("有 ${failures.get()} 个批次录制失败，不写录制文件（重跑可断点续录）", failures.get() == 0)
        return ExtractionRecording(
            schemaVersion = 1,
            model = env.model,
            batches = recorded.mapValues { it.value.toMap() },
        )
    }

    /** 一次录制运行的可变会话状态（并发录制共用；recorded 的写入在锁内） */
    private class RecordSession(
        val env: RecorderEnv,
        val extractionService: MemoryExtractionService,
        val config: LlmConfig,
        val recorded: MutableMap<String, MutableMap<String, RecordedBatch>>,
        val failures: AtomicInteger,
    ) {
        /** 单批录制：真实提示词 → 真实 LLM 调用（空正文按生产语义重试一次）→ 摘要 + 原始响应入表 */
        suspend fun record(job: RecordJob) {
            val prompt = buildPrompt(job)
            val response = chatWithBlankRetry(prompt, job) ?: return
            synchronized(recorded) {
                recorded
                    .getOrPut(job.sessionId) { mutableMapOf() }
                    .put(job.batch.index.toString(), RecordedBatch(promptDigest(prompt), response))
            }
        }

        private suspend fun buildPrompt(job: RecordJob): String {
            val turns =
                job.batch.turns.map {
                    MemoryExtractionService.DialogueTurn(
                        userMessage = it.userText,
                        assistantMessage = it.assistantText,
                        messageIds =
                            listOf(
                                "rec-${job.sessionId}-${it.userFloor}",
                                "rec-${job.sessionId}-${it.assistantFloor}",
                            ),
                    )
                }
            return extractionService.buildMemoryExtractionPrompt(extractionService.buildDialogueText(turns))
        }

        private suspend fun chatWithBlankRetry(
            prompt: String,
            job: RecordJob,
        ): String? =
            try {
                val first = LlmClient.chat(config, listOf(ChatMessage(role = "user", content = prompt)))
                if (first.isNotBlank()) first else retryAfterBlank(prompt)
            } catch (e: Exception) {
                failures.incrementAndGet()
                println("录制失败：会话 ${job.sessionId} 第 ${job.batch.index} 批（${e::class.java.simpleName}）")
                null
            }

        /** 与生产 requestExtraction 的空正文重试一致：提示词后追加重试压制语 */
        private suspend fun retryAfterBlank(prompt: String): String =
            LlmClient.chat(config, listOf(ChatMessage(role = "user", content = prompt + NO_REASONING_SUFFIX)))
    }

    /** 录制文件位置：sharedTest 资源目录（可用 MISTBELL_EVAL_RECORDING 覆盖） */
    private fun recordingFile(): File {
        System.getenv("MISTBELL_EVAL_RECORDING")?.let { return File(it) }
        return File(MemoryEvalRepo.root(), "app/src/sharedTest/resources/${MemoryEvalFixtures.RECORDING_PATH}")
    }

    /** 原子写：临时文件 + rename（与 PendingExtractionStore 同一纪律）；没有新录批次就不写 */
    private fun writeRecording(
        file: File,
        recording: ExtractionRecording,
        newBatches: Int,
    ) {
        if (newBatches == 0) {
            println("所有批次已录制（断点续录：无新增）。")
            return
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(ExtractionRecordingCodec.encode(recording))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        println("录制完成：${file.absolutePath}（${recording.batches.values.sumOf { it.size }} 批）")
    }
}
