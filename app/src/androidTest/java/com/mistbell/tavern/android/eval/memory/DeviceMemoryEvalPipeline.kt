package com.mistbell.tavern.android.eval.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.repository.SessionMutationLocks
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import com.mistbell.tavern.android.data.repository.buildVectorContent
import com.mistbell.tavern.android.service.MemoryExtractionService

/** 文件内短别名：与 JVM 侧 EvalRuntime 同一取舍，让 override 签名保持在可读宽度内 */
private typealias EvalDialogueTurn = MemoryExtractionService.DialogueTurn

private typealias Parsed = MemoryExtractionService.MemoryCandidate

/** 摘要副本向量在向量库里的 message_id 前缀（生产 VectorSyncSource.summaryMessageId 的同一式样） */
private const val SUMMARY_MESSAGE_ID_PREFIX = "structured_memory_"

/** 生产默认抽取提示词模板的开头（buildMemoryExtractionPrompt 的"未被用户自定义模板替换"哨兵） */
private const val DEFAULT_PROMPT_PREFIX = "你是对话记忆提取器"

/** 毫秒换算（纳秒计时的展示口径） */
private const val NANOS_PER_MILLISECOND = 1_000_000L

/**
 * 评测核心依赖面的设备侧实现：全部方法直通生产代码（与 JVM 侧 JvmMemoryEvalPipeline 同构），
 * 依赖一律显式传参——不通过反射改真实 AppContainer 的任何委托。
 *
 * 与 JVM 侧的两处差异：
 * 1. [buildMemoryExtractionPrompt] 有一个前缀哨兵：生产 `MemoryExtractionService.db` 固定取
 *    `TavernApplication.instance.container.database`（构造期字段，评测无法注入测试库），模板选择
 *    会读用户真实 tavern.db 的 `memory_extraction_prompt` 设置（只读、单键）。若用户自定义了抽取
 *    模板，设备侧回放的提示词就会与默认模板不同——哨兵在此明确失败，绝不带着被污染的提示词出数字。
 * 2. [runVectorFollowUps] 计次/计时进 [DeviceSyncStats]（设备侧证据：摘要向量排空的总量与耗时）。
 */
internal class DeviceMemoryEvalPipeline(
    private val service: MemoryExtractionService,
    private val repository: StructuredMemoryRepository,
    private val db: AppDatabase,
    private val syncStats: DeviceSyncStats,
) : MemoryEvalPipeline {
    override fun buildDialogueText(turns: List<EvalDialogueTurn>): String = service.buildDialogueText(turns)

    override fun shouldExtractMemory(dialogueText: String): Boolean = service.shouldExtractMemory(dialogueText)

    override suspend fun buildMemoryExtractionPrompt(dialogueText: String): String {
        val prompt = service.buildMemoryExtractionPrompt(dialogueText)
        check(prompt.startsWith(DEFAULT_PROMPT_PREFIX)) {
            "设备侧抽取提示词不是默认模板（可能读到用户自定义的 memory_extraction_prompt 设置，" +
                "评测数字会被污染）：开头=${prompt.take(40)}"
        }
        return prompt
    }

    override suspend fun parseAndValidateCandidates(rawResponse: String): List<Parsed> = service.parseAndValidateCandidates(rawResponse)

    override suspend fun saveCandidatesLocked(
        candidates: List<Parsed>,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
    ): MemoryExtractionService.SaveResult =
        // saveCandidatesLocked 的调用契约是"调用方已持会话锁"（与 ChatRepository.launchMemoryExtraction 一致）
        SessionMutationLocks.withSessionLock(sessionId) {
            service.saveCandidatesLocked(
                candidates = candidates,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageIds = messageIds,
            )
        }

    override suspend fun runVectorFollowUps(followUps: List<StructuredMemoryRepository.VectorFollowUp>) {
        val startedAt = System.nanoTime()
        repository.runVectorFollowUps(followUps)
        syncStats.recordSummary(followUps.size, (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
    }

    override suspend fun memoriesInSession(
        ownerId: String,
        sessionId: String,
    ): List<StructuredMemory> = repository.getMemoriesByOwnerAndSession(ownerId, sessionId)

    override suspend fun resolveInjectedRow(
        source: String,
        id: String,
    ): InjectedMemoryRow? =
        when {
            // 结构化记忆行：正文 + 类型一并带回（记忆评测按类型观察注入面）
            source == MemoryEvalDriver.SOURCE_STRUCTURED ->
                repository.getMemoryById(id.toLong())?.let { memory ->
                    InjectedMemoryRow(source, id, memory.content, memory.memoryType)
                }
            // 召回行 · 摘要副本（设备侧真栈特有）：importance≥7 的记忆同步一份 summary 进向量库，
            // 混合召回把它作为召回行注入（生产行为，生产注入清单同式）。向量 metadata.message_id
            // 是 structured_memory_<id>，正文是 buildVectorContent()（"标题: 内容 [类型]"），
            // 与提示词里渲染的召回行逐字同源
            id.startsWith(SUMMARY_MESSAGE_ID_PREFIX) ->
                repository.getMemoryById(id.removePrefix(SUMMARY_MESSAGE_ID_PREFIX).toLongOrNull() ?: -1L)
                    ?.let { memory ->
                        InjectedMemoryRow(source, id, memory.buildVectorContent(), memory.memoryType)
                    }
            // 召回行 · 历史消息：正文（注入投影为 150 字截断前的原文）
            else ->
                db.messageDao().getAnyById(id)?.let { message -> InjectedMemoryRow(source, id, message.content) }
        }

    override suspend fun deleteMemory(
        id: Long,
        ownerId: String,
        sessionId: String,
    ) {
        repository.deleteMemory(id, ownerId, sessionId)
    }

    override suspend fun insertMemory(memory: StructuredMemory): Long = repository.createMemory(memory)
}
