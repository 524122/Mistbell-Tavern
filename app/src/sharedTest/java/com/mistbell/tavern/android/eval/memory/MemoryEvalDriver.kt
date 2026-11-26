package com.mistbell.tavern.android.eval.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.api.model.truncateMemoryContent
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.repository.PendingExtractionStore
import com.mistbell.tavern.android.data.repository.PendingExtractionTurn
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.service.MemoryExtractionService
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 会话记忆回放编排（与环境无关的核心；JVM 用替身库驱动，设备侧传真库/真向量栈复用同一实现）。
 *
 * 评测只做三件"接变压器"的事，其余一律调用生产代码：
 * 1. 批次推进：把夹具楼层配成对话轮，喂给真实的 [PendingExtractionStore]（攒批规则与生产一致）；
 * 2. 抽取输出：ORACLE 用埋点事实合成（[OracleOutputs]）/ RECORDED 用真实模型录制，喂给
 *    真实的「解析 + 质量过滤 → 去重 → 落库 → 向量同步」（[MemoryEvalPipeline] 的实现必须包生产代码）；
 * 3. 探针装配：每个探针楼用真实的 [PromptBuilder.buildPromptTrace] 装配提示词，从
 *    [PromptBuilder.PromptTrace.recallDiagnostics] 的**注入清单**（id，裁剪与去重之后）取出
 *    本轮实际注入的记忆条目。
 */
interface MemoryEvalPipeline {
    /** 真实 buildDialogueText */
    fun buildDialogueText(turns: List<MemoryExtractionService.DialogueTurn>): String

    /** 真实 shouldExtractMemory 快速质量闸（过短的批次在真实链路里不会发起抽取） */
    fun shouldExtractMemory(dialogueText: String): Boolean

    /** 真实 buildMemoryExtractionPrompt */
    suspend fun buildMemoryExtractionPrompt(dialogueText: String): String

    /** 真实「原始响应 → 解析 + 质量过滤」 */
    suspend fun parseAndValidateCandidates(rawResponse: String): List<MemoryExtractionService.MemoryCandidate>

    /**
     * 真实去重 + 落库（saveCandidatesLocked）。实现负责按生产纪律先持会话锁
     * （SessionMutationLocks.withSessionLock），锁内调用生产实现。
     */
    suspend fun saveCandidatesLocked(
        candidates: List<MemoryExtractionService.MemoryCandidate>,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
    ): MemoryExtractionService.SaveResult

    /** 真实向量同步收尾（锁外） */
    suspend fun runVectorFollowUps(followUps: List<StructuredMemoryRepository.VectorFollowUp>)

    /** 回放结束后的记忆库读取（抽取召回率/重述负例用） */
    suspend fun memoriesInSession(
        ownerId: String,
        sessionId: String,
    ): List<StructuredMemory>

    /**
     * 注入清单 id → 注入条目；实现按 [source]（结构化记忆 / 召回消息）回库取正文。
     * 找不到返回 null（驱动器会给出带探针上下文的明确错误）。
     */
    suspend fun resolveInjectedRow(
        source: String,
        id: String,
    ): InjectedMemoryRow?

    /** 退化注入 (b)：删除指定记忆（走生产删除路径） */
    suspend fun deleteMemory(
        id: Long,
        ownerId: String,
        sessionId: String,
    )

    /** 退化注入 (c)：把指定记忆塞回记忆库（走生产创建路径） */
    suspend fun insertMemory(memory: StructuredMemory): Long
}

/** 抽取来源：ORACLE = 埋点事实合成的完美输出（召回上限）；RECORDED = 真实模型录制 */
enum class ExtractionMode(val label: String) {
    ORACLE("ORACLE"),
    RECORDED("RECORDED"),
}

/** 退化注入：替换某批抽取输出的回调签名（sessionId, 批次, 提示词）→ 原始输出（null = 用默认来源） */
typealias ExtractionOutputOverride =
    (sessionId: String, batch: MemoryEvalBatches.EvalBatch, prompt: String) -> String?

/** 一次回放的配置（含三类人为退化的注入口，见反向验证） */
data class DriverConfig(
    val mode: ExtractionMode,
    /**
     * 仅评测用的上下文预算覆盖；null = 按夹具的 contextTokenLimit。
     * 驱动器对夹具预算零写死——多少预算完全由这里或夹具决定。
     */
    val contextTokenLimitOverride: Int? = null,
    /** RECORDED 模式的录制（缺失 = 明确失败，不退回 ORACLE） */
    val recording: ExtractionRecording? = null,
    /**
     * ORACLE 合成抽取输出的重要度：null = 按 [OracleOutputs.TYPE_IMPORTANCE]（memoryType 定档，
     * 见报告"ORACLE 合成口径"）；非 null = 全部事实同一重要度（ORACLE-flat 对照变体）。
     */
    val oracleImportanceOverride: Double? = null,
    /**
     * 退化注入：替换某批的抽取输出（返回非 null 时用返回值替换 ORACLE/RECORDED 输出）。
     * 返回 {"triplets":[]} 即"清空抽取输出"。
     */
    val extractionOutputOverride: ExtractionOutputOverride? = null,
    /** 退化注入：每次保存后删掉当前记忆库的一半（按 id 隔一删一） */
    val pruneHalfMemoriesAfterSave: Boolean = false,
    /** 退化注入：探针装配前把被更新事实的旧版本塞回记忆库 */
    val resurrectStaleVersionAtProbes: Boolean = false,
    /** PendingExtractionStore 的落盘文件（生产攒批缓冲要在文件上工作） */
    val pendingExtractionFile: File,
) {
    /** 退化注入是否启用（报告需标注） */
    val hasDegradations: Boolean
        get() =
            extractionOutputOverride != null ||
                pruneHalfMemoriesAfterSave ||
                resurrectStaleVersionAtProbes
}

/** 一行注入的记忆条目（id 来自 PromptTrace 注入清单；content/memoryType 按条目回库取回） */
data class InjectedMemoryRow(
    val source: String,
    val id: String,
    val content: String,
    /** 结构化记忆的类型；召回消息为 null */
    val memoryType: String? = null,
)

/** 一个探针楼的装配结果 */
data class ProbeRecord(
    val sessionId: String,
    val probe: EvalProbe,
    val probeClass: ProbeClass,
    /** 有效探针 = 目标事实的三个楼层都不在最终历史窗口里 */
    val valid: Boolean,
    val invalidReason: String?,
    /** 探针楼处历史窗口实际覆盖的最早楼层（历史窗口 = 尾部连续窗口） */
    val earliestCoveredFloor: Int?,
    val historyTruncated: Boolean,
    val injectedRows: List<InjectedMemoryRow>,
    val structuredCount: Int,
    val recallCount: Int,
    /** 本轮注入记忆段的 token 估算（应用自己的 estimateTokens 口径） */
    val injectedMemoryTokens: Int,
    /** 本轮生效的上下文预算 */
    val contextTokenLimit: Int,
    /** 结构化块里实际注入的记忆类型（观察各楼注入面） */
    val structuredTypes: List<String> = emptyList(),
)

/** 一个会话的回放结果 */
data class SessionReplay(
    val sessionId: String,
    val title: String,
    val floorCount: Int,
    /** 平均每条消息字数（帮助读者判断窗口能装多少楼） */
    val avgMessageChars: Int,
    val contextTokenLimit: Int,
    /** 抽取调用次数（= 通过快速质量闸并实际走了解析管线的批次数） */
    val extractionCalls: Int,
    /** 被快速质量闸跳过的批次数（生产行为：这些批次不会发起抽取） */
    val gateSkippedBatches: Int,
    val probes: List<ProbeRecord>,
    /** 回放结束后的记忆库快照（抽取召回率 / 重述负例的分子来源） */
    val finalMemories: List<StructuredMemory>,
    val degradations: List<String>,
)

/** 一次探针楼的历史窗口判定结果 */
private data class HistoryWindow(
    val valid: Boolean,
    val invalidReason: String?,
    val earliestCoveredFloor: Int?,
    val historyTruncated: Boolean,
)

class MemoryEvalDriver(
    private val pipeline: MemoryEvalPipeline,
    private val ownerId: String = DEFAULT_OWNER,
) {
    /** 一次回放过程中的计数状态（探针记录、抽取调用数、跳过批数、出批数） */
    private class ReplayCounters {
        val probeRecords = mutableListOf<ProbeRecord>()
        var extractionCalls = 0
        var gateSkipped = 0
        var emittedBatchCount = 0
    }

    /** 回放一个会话（每次回放独立建库建会话；[db] 由调用方按环境提供） */
    suspend fun replay(
        session: EvalSession,
        db: AppDatabase,
        config: DriverConfig,
    ): SessionReplay {
        // 评测要求攒批从零开始：清掉上一次运行/上一会话遗留的缓冲文件（生产里进程生命周期天然保证）
        config.pendingExtractionFile.delete()
        val characterId = "eval-char"
        val budget = effectiveBudget(session, config)
        seed(db, session, characterId, budget)

        // 生产攒批：真实的 PendingExtractionStore（文件缓冲 + 满 3 轮出批）
        val store = PendingExtractionStore(config.pendingExtractionFile, loadGate = {})
        val scope =
            TurnScope(
                session = session,
                db = db,
                config = config,
                store = store,
                plan = MemoryEvalBatches.planBatches(session),
                counters = ReplayCounters(),
            )
        scope.runTurns()
        scope.flushTailBatches()
        check(scope.counters.emittedBatchCount == scope.plan.size) {
            "会话 ${session.id}：真实攒批出批数(${scope.counters.emittedBatchCount})与规划(${scope.plan.size})不一致"
        }

        return SessionReplay(
            sessionId = session.id,
            title = session.title,
            floorCount = session.messages.size,
            avgMessageChars = session.messages.map { it.content.length }.average().toInt(),
            contextTokenLimit = budget,
            extractionCalls = scope.counters.extractionCalls,
            gateSkippedBatches = scope.counters.gateSkipped,
            probes = scope.counters.probeRecords,
            finalMemories = pipeline.memoriesInSession(ownerId, session.id),
            degradations = degradationsOf(config),
        )
    }

    private fun effectiveBudget(
        session: EvalSession,
        config: DriverConfig,
    ): Int = config.contextTokenLimitOverride ?: session.contextTokenLimit

    private fun degradationsOf(config: DriverConfig): List<String> =
        buildList {
            if (config.extractionOutputOverride != null) add("抽取输出被清空/替换（extractionOutputOverride）")
            if (config.pruneHalfMemoriesAfterSave) add("每次保存后删除一半记忆（pruneHalfMemoriesAfterSave）")
            if (config.resurrectStaleVersionAtProbes) add("探针前塞回被更新事实的旧版本（resurrectStaleVersionAtProbes）")
        }

    /**
     * 一次回放的阶段化上下文：楼层推进 / 批次抽取 / 探针装配共用同一会话状态，拆类只会把
     * 同一份上下文在调用方传来传去（与 PromptBuilder.AssemblyCtx 同一取舍；
     * TooManyFunctions 豁免）。
     */
    @Suppress("TooManyFunctions")
    private inner class TurnScope(
        val session: EvalSession,
        val db: AppDatabase,
        val config: DriverConfig,
        val store: PendingExtractionStore,
        val plan: List<MemoryEvalBatches.EvalBatch>,
        val counters: ReplayCounters,
    ) {
        val characterId = "eval-char"
        val budget = effectiveBudget(session, config)

        /** 楼层推进：逐楼插入消息 → 满 3 轮经真实攒批出批即抽取 → 探针楼装配提示词 */
        suspend fun runTurns() {
            var pendingUserFloor: Int? = null
            for (floor in 2..session.messages.size) {
                val msg = session.messages.first { it.floor == floor }
                insertMessage(db, session.id, ownerId, characterId, msg)
                pendingUserFloor = advanceTurn(floor, pendingUserFloor)
                assembleProbeAtFloor(floor, msg)
            }
        }

        /** 用户楼记住待配对楼层；助手楼与它配成轮次入真实攒批。返回新的 pendingUserFloor */
        private suspend fun advanceTurn(
            floor: Int,
            pendingUserFloor: Int?,
        ): Int? =
            when (session.messages.first { it.floor == floor }.role) {
                "user" -> floor
                "assistant" -> {
                    val userFloor =
                        requireNotNull(pendingUserFloor) {
                            "会话 ${session.id}：第 $floor 楼助手消息没有配对的用户消息（规划器与驱动器不一致）"
                        }
                    enqueueTurn(userFloor, floor)
                    null
                }
                else -> pendingUserFloor
            }

        /** 探针楼（若有）：在该楼消息已落库、回复未到的时点装配提示词（与生产时序一致） */
        private suspend fun assembleProbeAtFloor(
            floor: Int,
            msg: EvalMessage,
        ) {
            val probe = session.probes.firstOrNull { it.floor == floor } ?: return
            check(msg.role == "user") { "探针楼 $floor 不是用户消息（会话 ${session.id}）" }
            counters.probeRecords += assembleProbe(probe, messageId(session.id, floor))
        }

        /** 尾巴 flush：不足一批的轮次靠"退出聊天页"兜底抽取（生产 flushPendingMemoryExtractions 语义） */
        suspend fun flushTailBatches() {
            store.drainAll().forEach { batch ->
                counters.emittedBatchCount++
                runAndCount(batch.batchId, batch)
            }
        }

        /** 一轮（用户楼, 助手楼）入真实攒批；攒满一批则立刻抽取并计数 */
        private suspend fun enqueueTurn(
            userFloor: Int,
            assistantFloor: Int,
        ) {
            val userMsg = session.messages.first { it.floor == userFloor }
            val assistantMsg = session.messages.first { it.floor == assistantFloor }
            val batch =
                store.enqueue(
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = session.id,
                    providerId = "",
                    turn =
                        PendingExtractionTurn(
                            userMessage = userMsg.content,
                            assistantMessage = assistantMsg.content,
                            messageIds =
                                listOf(
                                    messageId(session.id, userFloor),
                                    messageId(session.id, assistantFloor),
                                ),
                            // 入队快照：与生产 messageContentSignature 同式（SHA-256 of content+0x00+thinking）
                            messageSignatures =
                                listOf(
                                    contentSignature(userMsg.content, null),
                                    contentSignature(assistantMsg.content, null),
                                ),
                        ),
                )
            if (batch != null) {
                counters.emittedBatchCount++
                runAndCount(batch.batchId, batch)
            }
        }

        private suspend fun runAndCount(
            storeBatchId: String,
            batch: com.mistbell.tavern.android.data.repository.PendingExtractionBatch,
        ) {
            val skipped = runBatch(storeBatchId, batch, counters.emittedBatchCount)
            if (skipped) counters.gateSkipped++ else counters.extractionCalls++
        }

        /** 一个批次：真实攒批出批 → 真实提示词 → 模式化抽取输出 → 真实解析/质量过滤/去重/落库/向量同步。返回是否被快速质量闸跳过 */
        private suspend fun runBatch(
            storeBatchId: String,
            batch: com.mistbell.tavern.android.data.repository.PendingExtractionBatch,
            emissionIndex: Int,
        ): Boolean {
            val planned =
                checkNotNull(plan.getOrNull(emissionIndex - 1)) {
                    "会话 ${session.id}：真实攒批出了第 $emissionIndex 批，规划只有 ${plan.size} 批（攒批规则漂移）"
                }
            check(batch.turns.size == planned.turns.size) {
                "会话 ${session.id} 第 $emissionIndex 批：真实攒批轮数(${batch.turns.size})与规划(${planned.turns.size})不一致"
            }
            val dialogueTurns =
                batch.turns.map {
                    MemoryExtractionService.DialogueTurn(
                        userMessage = it.userMessage,
                        assistantMessage = it.assistantMessage,
                        messageIds = it.messageIds,
                    )
                }
            val dialogueText = pipeline.buildDialogueText(dialogueTurns)
            if (!pipeline.shouldExtractMemory(dialogueText)) {
                // 生产行为：快速质量闸不过的批次不会发起抽取（ack 丢弃，extractValidatedCandidates 的语义）
                store.ack(storeBatchId)
                return true
            }
            val prompt = pipeline.buildMemoryExtractionPrompt(dialogueText)
            val raw = resolveRawOutput(planned, prompt)
            val effectiveRaw = config.extractionOutputOverride?.invoke(session.id, planned, prompt) ?: raw
            val candidates = pipeline.parseAndValidateCandidates(effectiveRaw)
            if (candidates.isNotEmpty()) {
                extractAndSave(batch, candidates)
            }
            store.ack(storeBatchId)
            // 生产行为：空候选也是一次真实调用（ChatRepository.extractBatchCandidates 的 ack 语义）
            return false
        }

        /** 真实去重 + 落库 + 向量收尾 + 退化 (b)（调用方负责 ack） */
        private suspend fun extractAndSave(
            batch: com.mistbell.tavern.android.data.repository.PendingExtractionBatch,
            candidates: List<MemoryExtractionService.MemoryCandidate>,
        ) {
            val result =
                pipeline.saveCandidatesLocked(
                    candidates = candidates,
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = session.id,
                    messageIds = batch.turns.flatMap { it.messageIds }.distinct(),
                )
            pipeline.runVectorFollowUps(result.vectorFollowUps)
            pruneHalfIfRequested()
        }

        /** 模式化的抽取输出：ORACLE 合成 / RECORDED 录制（校验提示词摘要一致），退化注入可整体替换 */
        private fun resolveRawOutput(
            planned: MemoryEvalBatches.EvalBatch,
            prompt: String,
        ): String =
            when (config.mode) {
                ExtractionMode.ORACLE -> OracleOutputs.rawResponse(session, planned, config.oracleImportanceOverride)
                ExtractionMode.RECORDED -> recordedOutput(planned, prompt)
            }

        /** RECORDED：按（会话, 批次）取录制输出；缺失/摘要不一致都明确失败，不悄悄退回 */
        private fun recordedOutput(
            planned: MemoryEvalBatches.EvalBatch,
            prompt: String,
        ): String {
            val recording = requireNotNull(config.recording) { "RECORDED 模式必须提供录制文件内容" }
            val recorded =
                recording.batch(session.id, planned.index)
                    ?: throw IllegalStateException(
                        "录制文件缺少会话 ${session.id} 第 ${planned.index} 批" +
                            "（覆盖楼层 ${planned.floors.minOrNull()}..${planned.floors.maxOrNull()}）；" +
                            "请用 MemoryExtractionRecorderTest 重录，不退回 ORACLE",
                    )
            check(recorded.promptDigest == promptDigest(prompt)) {
                val replayDigest = promptDigest(prompt)
                val recordedShort = recorded.promptDigest.take(DIGEST_DISPLAY_LEN)
                val replayShort = replayDigest.take(DIGEST_DISPLAY_LEN)
                "录制与回放的抽取提示词不一致（会话 ${session.id} 第 ${planned.index} 批）：提示词在录制后被改过，请重录。" +
                    "录制摘要=$recordedShort… 回放摘要=$replayShort…"
            }
            return recorded.rawResponse
        }

        /** 退化 (b)：每次保存后删掉当前记忆库的一半（按 id 隔一删一，走生产删除路径） */
        private suspend fun pruneHalfIfRequested() {
            if (!config.pruneHalfMemoriesAfterSave) return
            pipeline
                .memoriesInSession(ownerId, session.id)
                .filterIndexed { index, _ -> index % 2 == 1 }
                .forEach { pipeline.deleteMemory(it.id, ownerId, session.id) }
        }

        /** 探针楼：真实 PromptBuilder 装配 + 注入清单取条目 + 交叉校验 + 历史窗口判定 */
        private suspend fun assembleProbe(
            probe: EvalProbe,
            probeMessageId: String,
        ): ProbeRecord {
            val fact = session.requireFact(probe.targetFactId)
            resurrectStaleIfRequested(probe, fact)
            val trace = buildTrace(probe, probeMessageId)
            checkFinalMessage(probe, trace)
            val rows = collectInjectedRows(probe, trace)
            val promptText = trace.segments.joinToString("\n") { it.message.content }
            checkRowsVisibleInPrompt(probe, promptText, rows)
            val memorySegments = trace.segments.filter { it.source == SOURCE_STRUCTURED || it.source == SOURCE_RECALL }
            val injectedMemoryTokens = memorySegments.sumOf { PromptBuilder.estimateTokensOf(it.message.content) }
            val window = historyWindowOf(probe, trace, fact)
            return ProbeRecord(
                sessionId = session.id,
                probe = probe,
                probeClass = session.probeClass(probe),
                valid = window.valid,
                invalidReason = window.invalidReason,
                earliestCoveredFloor = window.earliestCoveredFloor,
                historyTruncated = window.historyTruncated,
                injectedRows = rows,
                structuredCount = rows.count { it.source == SOURCE_STRUCTURED },
                recallCount = rows.count { it.source == SOURCE_RECALL },
                structuredTypes = structuredTypesOf(rows),
                injectedMemoryTokens = injectedMemoryTokens,
                contextTokenLimit = budget,
            )
        }

        private fun structuredTypesOf(rows: List<InjectedMemoryRow>): List<String> =
            rows.filter { it.source == SOURCE_STRUCTURED }.mapNotNull { it.memoryType }

        /** 退化 (c)：把被更新事实的旧版本塞回记忆库（走生产创建路径；高重要度保证必被选中） */
        private suspend fun resurrectStaleIfRequested(
            probe: EvalProbe,
            fact: EvalFact,
        ) {
            val appliesHere =
                config.resurrectStaleVersionAtProbes && fact.hasUpdate && (fact.updateFloor ?: 0) <= probe.floor
            if (!appliesHere) return
            pipeline.insertMemory(
                StructuredMemory(
                    id = 0,
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = session.id,
                    title = fact.statement.take(RESURRECTED_TITLE_MAX_CHARS),
                    content = fact.statement,
                    memoryType = fact.memoryType,
                    importance = 9,
                    tags = listOf(fact.subject, fact.`object`),
                    keywords = listOf(fact.subject, fact.relation, fact.`object`),
                    structuredData = null,
                    createdAt = Instant.now().toString(),
                    updatedAt = Instant.now().toString(),
                    lastAccessedAt = Instant.now().toString(),
                    accessCount = 0,
                    relatedMessageIds = emptyList(),
                    sourceType = "eval_degradation",
                ),
            )
        }

        private suspend fun buildTrace(
            probe: EvalProbe,
            probeMessageId: String,
        ): PromptBuilder.PromptTrace =
            PromptBuilder.buildPromptTrace(
                db,
                PromptBuilder.PromptRequest(
                    ownerId = ownerId,
                    characterId = characterId,
                    sessionId = session.id,
                    userMessage = probe.text,
                    currentMessageId = probeMessageId,
                    // 预算由会话行（夹具/覆盖值）决定，与生产解析口径一致
                    contextTokenLimitOverride = null,
                ),
            )

        /** 装配管线没漂移：最终用户消息必须是探针原话 */
        private fun checkFinalMessage(
            probe: EvalProbe,
            trace: PromptBuilder.PromptTrace,
        ) {
            val finalSegment = trace.segments.lastOrNull()
            check(finalSegment != null && finalSegment.source == "当前消息" && finalSegment.message.content == probe.text) {
                "会话 ${session.id} 探针楼 ${probe.floor}：装配的最终用户消息不是探针原话（source=${finalSegment?.source}）"
            }
        }

        /** 装配器注入清单与注入段必须一致（清单是评测的唯一口径，不一致即真 bug） */
        private fun checkListMatchesSegments(
            probe: EvalProbe,
            ids: List<String>,
            segments: List<PromptBuilder.Segment>,
            label: String,
        ) {
            check(ids.isNotEmpty() == segments.isNotEmpty()) {
                "会话 ${session.id} 探针楼 ${probe.floor}：${label}注入清单(${ids.size}条)与注入段(${segments.size}段)不一致"
            }
        }

        /** 按 PromptTrace 注入清单回库取条目（结构化记忆 + 召回消息） */
        private suspend fun collectInjectedRows(
            probe: EvalProbe,
            trace: PromptBuilder.PromptTrace,
        ): List<InjectedMemoryRow> {
            val structuredIds = trace.recallDiagnostics?.injectedStructuredMemoryIds.orEmpty()
            val recallIds = trace.recallDiagnostics?.injectedRecallMessageIds.orEmpty()
            checkListMatchesSegments(
                probe,
                structuredIds,
                trace.segments.filter { it.source == SOURCE_STRUCTURED },
                "结构化",
            )
            checkListMatchesSegments(probe, recallIds, trace.segments.filter { it.source == SOURCE_RECALL }, "召回")
            val structuredRows = structuredIds.map { id -> resolveRow(probe, SOURCE_STRUCTURED, id) }
            val recallRows = recallIds.map { id -> resolveRow(probe, SOURCE_RECALL, id) }
            return structuredRows + recallRows
        }

        private suspend fun resolveRow(
            probe: EvalProbe,
            source: String,
            id: String,
        ): InjectedMemoryRow =
            requireNotNull(pipeline.resolveInjectedRow(source, id)) {
                "会话 ${session.id} 探针楼 ${probe.floor}：注入清单里的条目（$source#$id）不在库（id 应来自同一装配）"
            }

        /** 交叉校验（口径断言）：清单里每个条目的内容确实出现在最终装配出的提示词文本里 */
        private fun checkRowsVisibleInPrompt(
            probe: EvalProbe,
            promptText: String,
            rows: List<InjectedMemoryRow>,
        ) {
            rows.forEach { row ->
                val projection =
                    if (row.source == SOURCE_STRUCTURED) {
                        row.content.truncateMemoryContent()
                    } else {
                        row.content.take(RECALL_INJECT_MAX_CHARS)
                    }
                check(promptText.contains(projection)) {
                    "会话 ${session.id} 探针楼 ${probe.floor}：注入条目 ${row.id} 的正文没有出现在提示词文本里" +
                        "（注入清单与装配文本脱节）：${row.content.take(ROW_SAMPLE_CHARS)}"
                }
            }
        }

        /**
         * 历史窗口：[PromptBuilder.selectHistoryWithinBudget] 从最新往回**连续**取，所以历史段永远是
         * 会话消息的一段连续后缀——窗口最早楼层 = 探针楼之前第 N 条消息的楼层（N = 历史段数）。
         * 按条数算而不是按正文比对：夹具的闲聊轮会循环复用，同一句话前后都出现，
         * 「正文出现在历史段里」会把窗口最早楼层误判到很早。目标事实三楼层都不在窗口内才算有效探针。
         */
        private fun historyWindowOf(
            probe: EvalProbe,
            trace: PromptBuilder.PromptTrace,
            fact: EvalFact,
        ): HistoryWindow {
            val historySegments = trace.segments.filter { it.source.startsWith("历史") }
            val probeIndex = session.messages.indexOfFirst { it.floor == probe.floor }
            check(probeIndex >= 0) { "会话 ${session.id}：探针楼 ${probe.floor} 不在消息列表里" }
            val oldestCoveredIndex = probeIndex - historySegments.size
            // 连续后缀的口径断言：最早一段历史段的正文必须就是它对应的那条会话消息
            historySegments.firstOrNull()?.let { oldest ->
                val expected = session.messages.getOrNull(oldestCoveredIndex)
                check(expected != null && oldest.message.content == expected.content) {
                    "会话 ${session.id} 探针楼 ${probe.floor}：历史段不是会话消息的连续后缀" +
                        "（最早历史段与第 ${expected?.floor} 楼正文不一致），窗口判定口径失效"
                }
            }
            val earliestCoveredFloor = session.messages.getOrNull(oldestCoveredIndex)?.floor
            // 只看探针之前**已经发生**的楼层：中段探针问的是「稍后才被更新」的事实，
            // 它的更新楼必然晚于探针，不能因为落在窗口起点之后就当成「还在窗口里」
            val factFloorsInWindow =
                if (earliestCoveredFloor == null) {
                    emptyList()
                } else {
                    fact.allFloors.filter { it >= earliestCoveredFloor && it < probe.floor }
                }
            return HistoryWindow(
                valid = factFloorsInWindow.isEmpty(),
                invalidReason =
                    if (factFloorsInWindow.isEmpty()) {
                        null
                    } else {
                        "目标事实 ${fact.id} 的楼层 $factFloorsInWindow 仍在历史窗口内（窗口最早覆盖楼层=$earliestCoveredFloor）"
                    },
                earliestCoveredFloor = earliestCoveredFloor,
                historyTruncated = trace.truncationReasons.any { it.contains(HISTORY_TRUNCATED_MARKER) },
            )
        }
    }

    private suspend fun seed(
        db: AppDatabase,
        session: EvalSession,
        characterId: String,
        budget: Int,
    ) {
        run {
            db.characterDao().upsert(
                CharacterEntity(
                    id = characterId,
                    name = "eval-char",
                    role = "assistant",
                    description = "",
                    personality = "",
                    scenario = "",
                    firstMes = "",
                    mesExample = "",
                    color = "",
                    avatarData = "",
                    worldBookId = "",
                    dataJson = "",
                ),
            )
            val now = BASE_TIME.toString()
            db.sessionDao().upsert(
                SessionEntity(
                    id = session.id,
                    ownerId = ownerId,
                    characterId = characterId,
                    title = session.title,
                    createdAt = now,
                    updatedAt = now,
                    messageCount = 0,
                    providerId = "",
                    modelId = "",
                    worldBookId = "",
                    summaryJson = "",
                    // 记忆开启；worldBookId 留空 + 无全局书（settings 不设键）→ 无世界书干扰
                    enableLongTermMemory = true,
                    contextTokenLimit = budget,
                ),
            )
        }
    }

    private suspend fun insertMessage(
        db: AppDatabase,
        sessionId: String,
        ownerId: String,
        characterId: String,
        msg: EvalMessage,
    ) {
        run {
            db.messageDao().upsert(
                MessageEntity(
                    id = messageId(sessionId, msg.floor),
                    sessionId = sessionId,
                    ownerId = ownerId,
                    characterId = characterId,
                    role = msg.role,
                    content = msg.content,
                    thinking = null,
                    createdAt = BASE_TIME.plusSeconds(msg.floor.toLong()).toString(),
                    memoryIdsJson = "",
                    swipesJson = "",
                    swipeIndex = 0,
                    thinkingSwipesJson = "",
                ),
            )
        }
    }

    companion object {
        const val DEFAULT_OWNER = "eval-user"

        /** 与生产 PromptBuilder.appendMemoryBlocks 的来源标签逐字一致 */
        const val SOURCE_STRUCTURED = "会话记忆·结构化"
        const val SOURCE_RECALL = "会话记忆·召回"

        /** 生产 PromptBuilder.buildTrace 的历史裁剪标记（truncationReasons 首项） */
        const val HISTORY_TRUNCATED_MARKER = "历史消息因上下文预算被裁剪"

        /** 生产 LexicalMemoryService.formatHistory 的注入截断长度 */
        const val RECALL_INJECT_MAX_CHARS = 150

        val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")

        fun messageId(
            sessionId: String,
            floor: Int,
        ): String = "$sessionId-m$floor"

        /** 摘要/样例等展示用截断长度（错误信息与标题里只露片段，避免错误信息膨胀） */
        const val DIGEST_DISPLAY_LEN = 12
        const val RESURRECTED_TITLE_MAX_CHARS = 50
        const val ROW_SAMPLE_CHARS = 40

        /** 与生产 ChatRepository.messageContentSignature 同式：SHA-256(content + 0x00 + thinking) */
        fun contentSignature(
            content: String,
            thinking: String?,
        ): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest((content + "\u0000" + (thinking ?: "")).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
