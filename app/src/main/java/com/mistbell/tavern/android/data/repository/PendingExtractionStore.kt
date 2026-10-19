package com.mistbell.tavern.android.data.repository

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 一轮待抽取对话（append 落库时入缓冲；被撤销/回退/重生覆盖的轮次会被剔除）。
 *
 * [messageSignatures] 与 [messageIds] 平行，是入队时各消息正文（content + thinking）的内容签名
 * （SHA-256）。锁内保存前逐轮核对"消息仍在 **且内容未被编辑/重生成篡改**"：任一轮失配 →
 * 整批候选作废（不信任被污染上下文产出的记忆），对仍有效轮次重新抽取。旧格式持久化数据
 * 该字段缺省为空，退化为"只查存在、不查内容"并记 warning。
 */
@Serializable
data class PendingExtractionTurn(
    val userMessage: String,
    val assistantMessage: String,
    val messageIds: List<String>,
    val messageSignatures: List<String> = emptyList(),
)

/** 攒满一批（或 flush 排空）后交给后台抽取的不可变快照 */
@Serializable
data class PendingExtractionBatch(
    val batchId: String = UUID.randomUUID().toString(),
    val ownerId: String,
    val characterId: String,
    val sessionId: String,
    val providerId: String,
    val turns: List<PendingExtractionTurn>,
)

/**
 * 待抽取对话的持久化缓冲。
 *
 * 长期记忆抽取按 [BATCH_TURNS] 轮攒批，不足一批的尾巴要等切会话/退出聊天页才 flush。此前缓冲
 * 只活在进程内存里：进程被系统杀死、应用崩溃时，没攒满的轮次连同它们的 `messageIds` 一起消失
 * ——原文还在消息库，但这一批永远不会再被抽取。这里把缓冲落到一个小 JSON 上，重启后接着攒。
 *
 * 为什么不是 Room 表：这是**临时**状态，不是业务数据。为它建表要动 schema（AppDatabase、
 * 新迁移、迁移测试基线），换来的只是一个可查询维度——不值。
 *
 * 为什么必须是单例：`ChatRepository` 每个聊天页一个实例。缓冲若各自持有，两个实例会各攒一半、
 * 谁也攒不满。故实例挂在 AppContainer 上，全进程共享，并以 [lock] 保证读改写原子。
 *
 * 为什么读写在调用线程同步完成：文件只有几 KB，调用点都在 IO 线程或低频事件（切会话、退出页面），
 * 相比"为省一次几毫秒的写而引入异步落盘的丢失窗口"更划算。
 *
 * 注解说明：落盘失败绝不能影响聊天主流程，读写一律兜住所有异常，最坏情况退化成仅内存缓冲。
 */
@Suppress("TooGenericExceptionCaught")
class PendingExtractionStore(
    private val storageFile: File,
    private val batchTurns: Int = BATCH_TURNS,
    private val loadGate: suspend () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 保护 [buffers] 与磁盘状态：所有公开方法都在锁内完成"读—改—写" */
    private val lock = Any()
    private var loaded = false
    private val buffers = mutableMapOf<String, Buffer>()
    private val inflight = mutableMapOf<String, PendingExtractionBatch>()

    /**
     * 加载闸门：公开方法是同步的（synchronized 内不能挂起），故在各方法进锁前
     * 用 runBlocking 等闸门完成。默认闸门为空操作，runBlocking({}) 几乎零开销；
     * 生产闸门等的是升级清零任务——它只删文件、不回调本 store，不存在重入死锁。
     * 调用点都在 IO 线程或低频事件上，短暂阻塞远好过"读到清零前的旧缓冲"。
     */
    private fun awaitGate() = kotlinx.coroutines.runBlocking { loadGate() }

    private class Buffer(
        val ownerId: String,
        val characterId: String,
        var providerId: String,
        val turns: MutableList<PendingExtractionTurn> = mutableListOf(),
    )

    /**
     * 一轮入缓冲；攒满一批则移除该会话并返回快照，否则返回 null（继续等下一轮）。
     * providerId 每次入队刷新为最新值——用户中途改了模型，应该用新模型抽。
     */
    fun enqueue(
        ownerId: String,
        characterId: String,
        sessionId: String,
        providerId: String,
        turn: PendingExtractionTurn,
    ): PendingExtractionBatch? {
        awaitGate()
        return synchronized(lock) {
            ensureLoaded()
            val buffer = buffers.getOrPut(sessionId) { Buffer(ownerId, characterId, providerId) }
            buffer.providerId = providerId
            buffer.turns.add(turn)

            val batch =
                if (buffer.turns.size >= batchTurns) {
                    buffers.remove(sessionId)
                    PendingExtractionBatch(
                        batchId = UUID.randomUUID().toString(),
                        ownerId = buffer.ownerId,
                        characterId = buffer.characterId,
                        sessionId = sessionId,
                        providerId = providerId,
                        turns = buffer.turns.toList(),
                    ).also { inflight[it.batchId] = it }
                } else {
                    null
                }

            persist()
            batch
        }
    }

    /** 排空全部会话的缓冲（不足一批的尾巴也一并取出） */
    fun drainAll(): List<PendingExtractionBatch> {
        awaitGate()
        return synchronized(lock) {
            ensureLoaded()
            val batches =
                buffers
                    .map { (sessionId, buffer) ->
                        PendingExtractionBatch(
                            batchId = UUID.randomUUID().toString(),
                            ownerId = buffer.ownerId,
                            characterId = buffer.characterId,
                            sessionId = sessionId,
                            providerId = buffer.providerId,
                            turns = buffer.turns.toList(),
                        ).also { inflight[it.batchId] = it }
                    }.filter { it.turns.isNotEmpty() }
            buffers.clear()
            persist()
            batches
        }
    }

    /** 抽取成功后确认删除；未确认的 inflight 批次会在下次启动时恢复。 */
    fun ack(batchId: String) {
        awaitGate()
        synchronized(lock) {
            ensureLoaded()
            if (inflight.remove(batchId) != null) persist()
        }
    }

    /** 抽取失败后退回 pending，保留原顺序等待下次重试。 */
    fun nack(batchId: String) {
        awaitGate()
        synchronized(lock) {
            ensureLoaded()
            val batch = inflight.remove(batchId) ?: return
            val buffer =
                buffers.getOrPut(batch.sessionId) {
                    Buffer(batch.ownerId, batch.characterId, batch.providerId)
                }
            buffer.providerId = batch.providerId
            buffer.turns.addAll(0, batch.turns)
            persist()
        }
    }

    /** 丢弃某会话的缓冲（会话被删/清空，或长期记忆被关掉）。两次删除分别执行再合并结果——
     *  短路写法（`a != null || b.removeIf(...)`）会在 pending 存在时漏删 inflight。 */
    fun discard(sessionId: String) {
        awaitGate()
        synchronized(lock) {
            ensureLoaded()
            val removedPending = buffers.remove(sessionId) != null
            val removedInflight = inflight.values.removeIf { it.sessionId == sessionId }
            if (removedPending || removedInflight) persist()
        }
    }

    /** 消息被撤销/回退/重生覆盖后修剪：凡引用了已不存在消息的轮次整轮剔除 */
    fun prune(
        sessionId: String,
        remainingMessageIds: Set<String>,
    ) {
        awaitGate()
        synchronized(lock) {
            ensureLoaded()
            val buffer = buffers[sessionId] ?: return
            buffer.turns.removeAll { turn -> turn.messageIds.any { it !in remainingMessageIds } }
            if (buffer.turns.isEmpty()) buffers.remove(sessionId)
            persist()
        }
    }

    /** 仅供测试与诊断：当前缓冲的会话数 */
    fun bufferedSessionCount(): Int {
        awaitGate()
        return synchronized(lock) {
            ensureLoaded()
            buffers.size
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!storageFile.exists()) return
        try {
            val state = json.decodeFromString<PersistedState>(storageFile.readText())
            state.sessions.forEach { session ->
                buffers[session.sessionId] =
                    Buffer(
                        ownerId = session.ownerId,
                        characterId = session.characterId,
                        providerId = session.providerId,
                        turns = session.turns.toMutableList(),
                    )
            }
            // 进程在抽取中被杀时，inflight 批次尚未 ack。恢复到 pending，下一次 flush/攒批会重试。
            state.inflight.forEach { batch ->
                val buffer =
                    buffers.getOrPut(batch.sessionId) {
                        Buffer(batch.ownerId, batch.characterId, batch.providerId)
                    }
                buffer.providerId = batch.providerId
                buffer.turns.addAll(batch.turns)
            }
            Log.d(TAG, "Loaded ${buffers.size} pending extraction buffer(s) from disk")
        } catch (e: Exception) {
            // 坏文件不该让功能不可用：删掉重来即可（原文仍在消息库，最多少抽一批）
            Log.e(TAG, "Failed to load pending extraction buffer: ${e.message}", e)
            storageFile.delete()
        }
    }

    private fun persist() {
        try {
            val state =
                PersistedState(
                    sessions =
                        buffers.map { (sessionId, buffer) ->
                            PersistedSession(
                                sessionId = sessionId,
                                ownerId = buffer.ownerId,
                                characterId = buffer.characterId,
                                providerId = buffer.providerId,
                                turns = buffer.turns.toList(),
                            )
                        },
                    inflight = inflight.values.toList(),
                )
            val content = json.encodeToString(state)
            val temp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            temp.writeText(content)
            // 原子替换：进程若在写入中途被杀，也不会留下半截 JSON 把整批缓冲毁掉
            if (!temp.renameTo(storageFile)) {
                // 某些文件系统（如 Windows 上的测试环境）不允许 rename 覆盖已存在文件
                storageFile.writeText(content)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist pending extraction buffer: ${e.message}", e)
        }
    }

    @Serializable
    private data class PersistedState(
        val sessions: List<PersistedSession> = emptyList(),
        val inflight: List<PendingExtractionBatch> = emptyList(),
    )

    @Serializable
    private data class PersistedSession(
        val sessionId: String,
        val ownerId: String,
        val characterId: String,
        val providerId: String,
        val turns: List<PendingExtractionTurn>,
    )

    companion object {
        private const val TAG = "PendingExtractionStore"

        /** 缓冲文件名（挂在 filesDir 下） */
        const val FILE_NAME = "pending_extraction.json"

        /**
         * 满几轮合并发起一次 LLM 抽取：抽取的输出/思考成本约占每轮总成本 80%+，
         * 批量化把每次调用的固定输入/思考开销摊到 1/N。
         */
        const val BATCH_TURNS = 3
    }
}
