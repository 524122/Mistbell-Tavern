package com.mistbell.tavern.android.eval.memory

import com.mistbell.tavern.android.data.repository.PendingExtractionStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 批次规划（纯函数，JVM 与设备共用）：回放与录制都按同一划分推进。
 *
 * 划分规则 = 应用的真实攒批规则：每 [PendingExtractionStore.BATCH_TURNS] 轮一批，
 * 尾巴不足一批的靠"退出聊天页时的 flush"收尾（生产 flushPendingMemoryExtractions 的语义）。
 * 回放驱动器同时驱动真实的 [PendingExtractionStore]，并断言其出批顺序与本规划一致——
 * 评测自己不另写一份攒批逻辑。
 */
object MemoryEvalBatches {
    /** 一个回放轮次：一对相邻的（用户楼, 助手楼）消息 */
    data class EvalTurn(
        val userFloor: Int,
        val userText: String,
        val assistantFloor: Int,
        val assistantText: String,
    ) {
        val floors: IntRange get() = userFloor..assistantFloor
    }

    /** 一个抽取批次（batchIndex 从 1 开始，与录制文件的键一致） */
    data class EvalBatch(
        val index: Int,
        val turns: List<EvalTurn>,
    ) {
        /** 该批覆盖的全部楼层（ORACLE 合成抽取输出按埋点楼层落批） */
        val floors: Set<Int> get() = turns.flatMapTo(HashSet()) { it.floors.toList() }

        val firstFloor: Int get() = turns.minOf { it.userFloor }
        val lastFloor: Int get() = turns.maxOf { it.assistantFloor }
    }

    /**
     * 楼层 → 轮次：1 楼是开场白（assistant），生产里不参与攒批（开场白在建会话时插入）；
     * 之后偶数楼 user、奇数楼 assistant，每个用户楼与它的下一楼（助手回复）构成一轮。
     * 末尾没有回复的用户楼（如 300 楼探针）不构成轮次——生产语义是"回复尚未落库、无法入队"。
     */
    fun planTurns(session: EvalSession): List<EvalTurn> {
        val turns = mutableListOf<EvalTurn>()
        var pendingUser: EvalMessage? = null
        for (msg in session.messages) {
            if (msg.floor == 1) continue // 开场白不参与攒批（与生产一致）
            when (msg.role) {
                "user" ->
                    if (pendingUser == null) {
                        pendingUser = msg
                    } else {
                        throw FixtureInvalidException(
                            "会话 ${session.id}：第 ${msg.floor} 楼是连续第二条用户消息（无法配对成轮次）",
                        )
                    }
                "assistant" -> {
                    val user = pendingUser
                    if (user == null) {
                        throw FixtureInvalidException(
                            "会话 ${session.id}：第 ${msg.floor} 楼助手消息没有配对的用户消息（除开场白外）",
                        )
                    }
                    turns += EvalTurn(user.floor, user.content, msg.floor, msg.content)
                    pendingUser = null
                }
            }
        }
        return turns
    }

    /** 轮次 → 批次：每 [PendingExtractionStore.BATCH_TURNS] 轮一批 */
    fun planBatches(session: EvalSession): List<EvalBatch> =
        planTurns(session)
            .chunked(PendingExtractionStore.BATCH_TURNS)
            .mapIndexed { index, chunk -> EvalBatch(index = index + 1, turns = chunk) }
}

/**
 * ORACLE 抽取输出合成：把埋点事实按其所在批次直接当作"完美的抽取输出"。
 *
 * 每个版本（原话 / 更新 / 重述）合成为一条 triplet，合成口径（写进报告"ORACLE 合成口径"）：
 * - rawText = 夹具**原话本身**（完美抽取 = 逐字记录，不做第三人称转写；它也是 enrichKeywords
 *   关键词的一部分）；
 * - subject/relation/object = 夹具结构化字段；tags = [subject, object]，aliases = [object]；
 * - importance = [TYPE_IMPORTANCE] 按 memoryType 定档（对照生产抽取提示词的重要度标尺），
 *   不做任何额外加成——加成由生产的 enhanceImportance 完成；ORACLE-flat 变体传同一常数；
 * - 更新与重述按它们**自己所在的批次**进入（晚说的话晚一批才被抽取）。
 */
object OracleOutputs {
    private val json = Json { encodeDefaults = true }

    /**
     * memoryType → importance（依据 = 生产抽取提示词的标尺档位中值，固定不变）：
     * 0.7–0.85 档（身份/核心/目标/关键承诺）取 0.8 / 0.75；0.5–0.7 档（稳定偏好/重要关系/
     * 人物设定/持续影响事件）取 0.6–0.65；0.35–0.5 档（背景/设定细节/一次性事件）取 0.5–0.55。
     * 全部 ≥ 0.5：低于 0.5 会在生产质量闸被过滤（那属于"不该抽出的内容"，不是 ORACLE 的本意）。
     * 档位数值本身就是标尺定义，不视作魔法数（MagicNumber 豁免）。
     */
    @Suppress("MagicNumber")
    val TYPE_IMPORTANCE: Map<String, Double> =
        mapOf(
            "identity" to 0.8,
            "core" to 0.8,
            "goal" to 0.75,
            "relationship" to 0.65,
            "character_info" to 0.6,
            "preference" to 0.6,
            "event" to 0.6,
            "item" to 0.55,
            "location" to 0.55,
            "emotion" to 0.5,
            "fact" to 0.5,
            "note" to 0.5,
        )

    /** 标尺里没有的类型（未来新增）按"稳定事实"档处理 */
    const val DEFAULT_IMPORTANCE = 0.6

    fun importanceOf(memoryType: String): Double = TYPE_IMPORTANCE[memoryType.lowercase()] ?: DEFAULT_IMPORTANCE

    fun rawResponse(
        session: EvalSession,
        batch: MemoryEvalBatches.EvalBatch,
        importanceOverride: Double? = null,
    ): String {
        val entries = mutableListOf<OracleEntry>()
        session.facts.forEach { fact ->
            if (fact.statementFloor in batch.floors) {
                entries += OracleEntry(fact.subject, fact.relation, fact.`object`, fact.memoryType, fact.statement)
            }
            if (fact.restatement != null && fact.restatementFloor in batch.floors) {
                entries += OracleEntry(fact.subject, fact.relation, fact.`object`, fact.memoryType, fact.restatement)
            }
            if (fact.update != null && fact.updateFloor in batch.floors) {
                entries +=
                    OracleEntry(
                        subject = fact.subject,
                        relation = fact.relation,
                        obj = fact.`object`,
                        memoryType = fact.memoryType,
                        rawText = fact.update.statement,
                    )
            }
        }
        val triplets =
            entries.map { entry ->
                tripletJson(entry, importanceOverride ?: importanceOf(entry.memoryType))
            }
        return json.encodeToString(
            kotlinx.serialization.json.JsonObject(
                mapOf("triplets" to kotlinx.serialization.json.JsonArray(triplets)),
            ),
        )
    }

    /** ORACLE 合成的一条 triplet 输入（夹具结构化字段 + 该版本的陈述） */
    private data class OracleEntry(
        val subject: String,
        val relation: String,
        val obj: String,
        val memoryType: String,
        val rawText: String,
    )

    private fun tripletJson(
        entry: OracleEntry,
        importance: Double,
    ): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("subject", kotlinx.serialization.json.JsonPrimitive(entry.subject))
            put("relation", kotlinx.serialization.json.JsonPrimitive(entry.relation))
            put("object", kotlinx.serialization.json.JsonPrimitive(entry.obj))
            put("memoryType", kotlinx.serialization.json.JsonPrimitive(entry.memoryType))
            put("importance", kotlinx.serialization.json.JsonPrimitive(importance))
            put(
                "tags",
                kotlinx.serialization.json.JsonArray(
                    listOf(entry.subject, entry.obj)
                        .filter { it.isNotBlank() }
                        .map { kotlinx.serialization.json.JsonPrimitive(it) },
                ),
            )
            put(
                "aliases",
                kotlinx.serialization.json.JsonArray(
                    listOf(entry.obj).filter { it.isNotBlank() }.map { kotlinx.serialization.json.JsonPrimitive(it) },
                ),
            )
            put("rawText", kotlinx.serialization.json.JsonPrimitive(entry.rawText))
        }
}

/** 录制文件的抽取输出条目：提示词摘要 + 原始响应 */
@Serializable
data class RecordedBatch(
    val promptDigest: String,
    val rawResponse: String,
)

/**
 * 真实模型抽取输出的录制（`extraction-recording.json`）：
 * `{"schemaVersion":1,"model":"…","batches":{"<sessionId>":{"<batchIndex>":{promptDigest, rawResponse}}}}`。
 * 密钥绝不进入本文件。回放时校验 promptDigest 与生产提示词一致——抽取提示词改了而录制没重录，
 * 回放会明确失败而不是用错位的输出装样子。
 */
@Serializable
data class ExtractionRecording(
    val schemaVersion: Int = 1,
    val model: String = "",
    val batches: Map<String, Map<String, RecordedBatch>> = emptyMap(),
) {
    fun batch(
        sessionId: String,
        batchIndex: Int,
    ): RecordedBatch? = batches[sessionId]?.get(batchIndex.toString())

    fun hasSession(sessionId: String): Boolean = !batches[sessionId].isNullOrEmpty()
}

object ExtractionRecordingCodec {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val compactJson: Json = Json { ignoreUnknownKeys = false }

    fun decode(content: String): ExtractionRecording = compactJson.decodeFromString<ExtractionRecording>(content)

    fun encode(recording: ExtractionRecording): String = json.encodeToString(recording)
}

/** 提示词摘要：SHA-256 hex（完整 64 位，防止截断碰撞）。密钥无关——只吃提示词文本。 */
fun promptDigest(prompt: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(prompt.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
