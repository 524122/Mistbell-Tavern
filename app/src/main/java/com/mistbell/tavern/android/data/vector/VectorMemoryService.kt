package com.mistbell.tavern.android.data.vector

import android.util.Log
import com.mistbell.tavern.android.data.repository.RecallWindow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 向量记忆服务
 *
 * 移植自后端 VectorMemoryService.java
 *
 * 负责：
 * 1. 存储消息到向量数据库（带删除意图闸门，见 [storeMessage]）
 * 2. 语义检索相关记忆（作用域由 [RecallWindow] 决定：owner + 当前会话；
 *    缺会话边界时拒绝召回，见 [searchRelevantMemories]）
 * 3. 删除与启动对账（[reconcileWithDatabase]）
 */
class VectorMemoryService(
    private val vectorStore: VectorStore,
    private val embeddingService: EmbeddingService,
    private val recallValidator: (suspend (List<VectorStore.SearchResult>) -> List<VectorStore.SearchResult>)? = null,
) {
    companion object {
        private const val TAG = "VectorMemoryService"
    }

    /**
     * 向量能力可用性开关
     *
     * 由构造时注入的 embedding 服务决定：
     * - 真实 API embedding 服务（OpenAIEmbeddingService）→ true
     * - 本地语义向量（LocalEmbeddingService，F3 ONNX）→ true
     * - 占位服务（Mock 等）→ false
     *
     * available=false 时调用方应跳过向量写入/检索，改走词法回退（LexicalMemoryService）。
     */
    val available: Boolean =
        embeddingService is OpenAIEmbeddingService || embeddingService is LocalEmbeddingService

    /** 本进程是否已清理过旧指纹向量：只在首次写入时执行一次，避免每次落库都扫全表 */
    private val stalePurged = AtomicBoolean(false)

    /**
     * 预计算 embedding（慢操作，**锁外调用**）。
     *
     * 与 [commitPrepared] 配对：慢计算在调用方的会话锁外完成，"最终版本核验 + 原子提交"
     * 在会话锁内完成——两者之间的窗口里 Room 可能发生变化，所以提交前必须在会话锁内
     * 重新核验源版本（见 StructuredMemoryRepository 的向量收尾）。
     */
    suspend fun prepareEmbedding(content: String): FloatArray = embeddingService.embed(content)

    /**
     * 提交预计算好的向量（**快操作**：调用方必须已处于自己的临界区内**）。
     *
     * 不做 embedding，只做删除意图闸门 + 原子落地（含同来源身份旧副本替换，见
     * InMemoryVectorStore 的 replacePrevious 语义）。设计给"锁外向量收尾"使用：
     * 调用方在 SessionMutationLocks 会话锁内完成源版本核验后立即调用本方法。
     *
     * **锁顺序纪律**：调用方锁（SessionMutationLocks 会话锁）→ store 内部锁。禁止反向——
     * store 的 add/delete 路径不会回调会话锁，顺序恒定，无死锁。
     *
     * @return 提交成功返回向量 id；被删除意图压制（或 content 为空）时返回 null
     */
    @Suppress("LongParameterList")
    suspend fun commitPrepared(
        embedding: FloatArray,
        content: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        contentType: String,
        replacePrevious: Boolean = false,
        sourceRevision: String? = null,
    ): String? {
        val writeToken =
            if (content.isBlank()) {
                null
            } else {
                vectorStore.beginMessageWrite(messageId, replacePrevious)
            } ?: return null
        return storeEmbedding(
            embedding,
            content,
            ownerId,
            characterId,
            sessionId,
            messageId,
            contentType,
            writeToken,
            sourceRevision,
        )
    }

    /**
     * 存储消息到向量数据库
     *
     * ## 删除竞态
     *
     * embedding 是慢操作（本地推理或 API 往返）。它跑完时源消息完全可能已经被删（LLM 失败回滚、
     * 撤销、回退、重抽、清空/删除会话），而删除路径当时并没有看见这条还不存在的向量——
     * 于是它成了"删除之后才落地"的孤儿，跨会话召回还会把它捞回来。
     *
     * 删除方因此会登记删除意图（[deleteByMessageId] / [deleteSessionVectors] /
     * [deleteCharacterVectors] / [clearIndex]）；这里在**写入落地前**问一次闸门，
     * 被压制的写入返回 null 而不是假装成功。
     *
     * 唯一例外是"替换"：[replacePrevious] = true 用于"同一条消息内容变了"的写入
     * （重抽、继续生成、编辑）。它先解除删除意图再写——否则替换型写入会被先前那次
     * 删除-重写操作留下的标记永久压住。
     *
     * ## 提交时源版本校验（[commitCheck]）
     *
     * embedding 是慢计算：它跑完时源数据的版本可能已经变了（源被删、被更新的版本覆盖）。
     * [commitCheck] 在 **embedding 完成后、向量原子提交（addIfAllowed）前**再核对一次源版本，
     * 返回 false 则放弃本次写入（返回 null）——防止"慢旧写入复活已删数据/覆盖新版本的
     * 新向量"。注意 [commitCheck] 失败时删除意图已被 beginMessageWrite 解除，但由于没有
     * 实际写入，不会产生复活；后续删除方会重新登记意图。
     *
     * @return 写入成功返回向量 id；被删除意图压制、提交校验失败（或 content 为空）时返回 null
     */
    @Suppress("LongParameterList")
    suspend fun storeMessage(
        content: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        contentType: String,
        replacePrevious: Boolean = false,
        // 提交时校验：慢计算（embedding）完成后、向量原子提交前核对源数据版本。
        // 用于锁外向量收尾（记忆摘要同步）防旧版本复活/覆盖新版本。
        commitCheck: (suspend () -> Boolean)? = null,
        sourceRevision: String? = null,
    ): String? {
        val writeToken =
            if (content.isBlank()) {
                null
            } else {
                vectorStore.beginMessageWrite(messageId, replacePrevious)
            } ?: return null

        // 1. 生成向量（慢操作）
        val embedding = embeddingService.embed(content)

        // 2. 提交时校验：embedding 期间源数据版本可能已变（删除/更新），这是放弃旧写入的
        //    最后机会——必须在 addIfAllowed 之前，否则旧向量会落地复活或覆盖新版本。
        val commitAllowed = commitCheck?.invoke() ?: true
        if (!commitAllowed) {
            Log.d(TAG, "Commit check failed for $messageId; dropping vector")
        }

        // 3. 写入前问一次闸门：embedding 期间删除意图可能刚刚登记（这正是竞态发生的位置）。
        //    替换写入顺手解除标记，否则会被先前那次删除永久压住。
        return if (commitAllowed) {
            storeEmbedding(
                embedding,
                content,
                ownerId,
                characterId,
                sessionId,
                messageId,
                contentType,
                writeToken,
                sourceRevision,
            )
        } else {
            null
        }
    }

    /**
     * 构建元数据并落库，返回向量 id。
     *
     * 参数就是一条向量要落库的全部身份字段：收进对象只会把同一份字段在调用方再拼一遍。
     */
    @Suppress("LongParameterList")
    private suspend fun storeEmbedding(
        embedding: FloatArray,
        content: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        contentType: String,
        writeToken: VectorStore.MessageWriteToken,
        sourceRevision: String?,
    ): String? {
        val metadata =
            buildMap<String, Any> {
                put("owner_id", ownerId)
                put("character_id", characterId)
                put("session_id", sessionId)
                put("message_id", messageId)
                put("content_type", contentType)
                put("content", content)
                put("created_at", System.currentTimeMillis().toString())
                put("embedding_fingerprint", embeddingService.fingerprint)
                put("dimension", embedding.size.toString())
                if (!sourceRevision.isNullOrBlank()) put("source_revision", sourceRevision)
            }

        val vectorId = generateVectorId()
        if (!vectorStore.addIfAllowed(vectorId, embedding, metadata, messageId, writeToken)) {
            Log.d(TAG, "Message $messageId was deleted while embedding; dropping vector")
            return null
        }
        Log.d(TAG, "Stored message vector: $messageId -> $vectorId")

        // 本进程首次写入时顺带清一次旧指纹向量（换 embedding 模型后的遗留）。
        // 放在写入路径而不是 DI 装配：写入必在 IO 线程，不给启动路径引入磁盘 IO。
        if (stalePurged.compareAndSet(false, true)) {
            purgeStaleEmbeddings()
        }
        return vectorId
    }

    /**
     * 搜索相关记忆
     *
     * 作用域来自 [window]，本方法只做投影：owner 硬隔离 + 当前 embedding 指纹（只搜同一向量空间，
     * 换模型后旧指纹向量自动退出召回，跨模型余弦相似度没有意义）。
     *
     * 会话内长期记忆：**窗口没有会话边界（[RecallWindow.vectorSessionValues] 为 null 或空）时
     * 直接拒绝召回**（返回空结果），绝不退化成 owner 级——那正是"已删/别的会话内容被捞进
     * 本提示词"的历史缺陷。也不再按角色过滤：群聊里 NPC 与主角色的向量同属这一次对话，
     * 会话边界已覆盖角色隔离。
     *
     * @return 命中结果 + 本次是否按会话收窄（观察用；false 表示缺会话边界、本次已拒绝召回）
     */
    suspend fun searchRelevantMemories(
        query: String,
        window: RecallWindow,
        topK: Int = 5,
    ): SearchOutcome {
        // 防御性收口（不信任调用方装配的窗口）：
        // - ownerId / currentSessionId 为空 → 拒绝召回；
        // - 窗口携带多会话集合（未来误用）→ 收窄为当前会话并记 warning。
        val windowIdentityMissing = window.ownerId.isBlank() || window.currentSessionId.isBlank()
        if (windowIdentityMissing) {
            Log.w(TAG, "Recall window missing owner/session; refusing vector recall")
        }
        val sessionValues =
            window.vectorSessionValues?.let { values ->
                if (values.any { it != window.currentSessionId }) {
                    Log.w(TAG, "Recall window spans multiple sessions; narrowing to current session")
                    setOf(window.currentSessionId)
                } else {
                    values
                }
            }
        if (sessionValues.isNullOrEmpty()) {
            // 缺失会话边界：宁可召回空气，也不跨会话——三条通道同口径（词法/结构化同此原则）
            Log.w(TAG, "Recall window has no session boundary; refusing vector recall")
        }
        if (windowIdentityMissing || sessionValues.isNullOrEmpty()) {
            return SearchOutcome(results = emptyList(), sessionScoped = false)
        }

        return try {
            // 1. 查询向量化
            val queryEmbedding = embeddingService.embed(query)

            // 2. 构建过滤器：owner + 指纹 + 会话集合（sessionValues 必非空，见上面早退）
            val filters =
                mutableMapOf<String, Any>(
                    "owner_id" to window.ownerId,
                    "embedding_fingerprint" to embeddingService.fingerprint,
                    "session_id" to sessionValues,
                )

            // 3. 向量检索（含 CachedVectorStore 命中）后仍按 Room 当前源版本收口
            val indexedResults = vectorStore.search(queryEmbedding, topK, filters)
            val results = recallValidator?.invoke(indexedResults) ?: indexedResults

            Log.d(
                TAG,
                "Search found ${results.size} relevant memories for queryLength=${query.length} " +
                    "(scoped=true, sessions=${sessionValues.size})",
            )

            SearchOutcome(results = results, sessionScoped = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search relevant memories: ${e.message}", e)
            SearchOutcome(results = emptyList(), sessionScoped = true)
        }
    }

    /**
     * 维护用：索引中**当前 embedding 指纹下**已存在的 message_id 集合。
     *
     * 重建索引时据此跳过已索引的消息——重复 embedding 既费额度又会产生重复向量。
     * 必须带入当前指纹：换过模型之后旧指纹的向量不算"已索引"，否则重建会全部跳过、
     * 而检索按新指纹过滤后是空的。
     */
    suspend fun indexedMessageIds(): Set<String> = vectorStore.indexedMessageIds(embeddingService.fingerprint)

    /**
     * 维护动作（[VectorIndexMaintenance]）需要的基础设施访问口。
     *
     * 保持 `internal`：热路径的调用方不该绕过 storeMessage/searchRelevantMemories 直接碰存储
     * （那会漏掉指纹、闸门、向量空间等约束），只有同模块的维护代码需要。
     */
    internal fun indexStore(): VectorStore = vectorStore

    internal fun embeddingDimension(): Int = embeddingService.getDimension()

    internal fun embeddingFingerprint(): String = embeddingService.fingerprint

    /**
     * 生成向量ID
     */
    private fun generateVectorId(): String {
        return "vec_${UUID.randomUUID().toString().replace("-", "")}"
    }

    /**
     * 一次语义检索的结果。
     *
     * @param sessionScoped 本次是否按会话收窄。false 只表示**窗口缺会话边界、本次已拒绝召回**
     *   （结果必为空）——跨会话/owner 级的退化路径已整体移除，绝不静默放宽召回范围。
     */
    data class SearchOutcome(
        val results: List<VectorStore.SearchResult>,
        val sessionScoped: Boolean,
    )
}
