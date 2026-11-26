package com.mistbell.tavern.android.eval.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * 会话记忆评测夹具的数据模型与**严格**加载（与环境无关：JVM 与设备侧共用）。
 *
 * 校验失败一律抛 [FixtureInvalidException] 并指明会话/字段——夹具是评测的地基，
 * 静默容错会让指标失去可解释性。目录与文件由夹具拼装脚本产出，评测侧只读。
 *
 * 语义（与拼装脚本约定一致）：
 * - 楼层从 1 连续编号；1 楼是开场白（assistant，不参与抽取攒批，与生产一致）；
 * - 一条事实的原版本在 `[statementFloor, updateFloor)` 有效，更新版本从 `updateFloor` 起有效；
 *   重述不产生新版本（同一事实的另一种说法，用于检验去重：既不把重述记成两条，也不把它
 *   误判成更新而失效）；
 * - 探针是用户在该楼说的话（该楼消息正文与探针文本一致），`targetVersion` 指明应被想起的
 *   版本，`absentFactIds` 指明不应被注入的旧版本。
 */
@Serializable
data class EvalManifest(
    val schemaVersion: Int,
    val sessionCount: Int,
    val sessions: List<EvalManifestEntry> = emptyList(),
)

@Serializable
data class EvalManifestEntry(
    val id: String,
    val floors: Int,
    val facts: Int,
    val updatedFacts: Int = 0,
    val restatedFacts: Int = 0,
    val probes: Int,
    val probesOnUpdated: Int = 0,
    val totalChars: Int = 0,
)

@Serializable
data class EvalSession(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val contextTokenLimit: Int,
    val messages: List<EvalMessage>,
    val facts: List<EvalFact>,
    val probes: List<EvalProbe>,
)

@Serializable
data class EvalMessage(
    val floor: Int,
    val role: String,
    val content: String,
)

@Serializable
data class EvalUpdate(
    val statement: String,
    val probe: String,
)

@Serializable
data class EvalFact(
    val id: String,
    val memoryType: String,
    val subject: String,
    val relation: String,
    val `object`: String,
    val speaker: String,
    val statement: String,
    val statementFloor: Int,
    val restatement: String? = null,
    val restatementFloor: Int? = null,
    val update: EvalUpdate? = null,
    val updateFloor: Int? = null,
) {
    val hasUpdate: Boolean get() = update != null

    /** 该事实参与历史窗口判定的全部楼层（statement / restatement / update 所在楼） */
    val allFloors: List<Int>
        get() =
            buildList {
                add(statementFloor)
                restatementFloor?.let(::add)
                updateFloor?.let(::add)
            }

    /** 该事实的"标准答案"陈述：目标版本为 updated 时用 update.statement */
    fun statementOfVersion(version: String): String =
        if (version == "updated" && update != null) {
            update.statement
        } else {
            statement
        }
}

@Serializable
data class EvalProbe(
    val floor: Int,
    val targetFactId: String,
    val targetVersion: String,
    val absentFactIds: List<String> = emptyList(),
    val text: String,
)

/** 夹具 schemaVersion 与应用钳制的最小上下文预算 */
private const val SCHEMA_VERSION = 1
private const val MIN_EVAL_CONTEXT_TOKENS = 1024

/** 夹具校验失败：message 必须能直接定位到哪个会话的哪个字段出了什么问题 */
class FixtureInvalidException(message: String) : IllegalArgumentException(message)

/** 单个会话加载失败（其余会话仍可运行）；原因会进报告的"夹具问题"清单 */
class SessionLoadException(
    val sessionId: String,
    reason: String,
) : IllegalStateException("会话 $sessionId 加载失败：$reason")

/** 探针三分类（与拼装布局对齐）：前段问无更新事实的原话；中段问"稍后被更新"事实的更新前原话；后段问更新后的新版本 */
enum class ProbeClass(val label: String) {
    ORIGINAL_STABLE("前段·无更新事实原话"),
    ORIGINAL_SUPERSEDED("中段·更新前原话"),
    UPDATED("后段·更新后新版本"),
}

fun EvalSession.probeClass(probe: EvalProbe): ProbeClass =
    when {
        !requireFact(probe.targetFactId).hasUpdate -> ProbeClass.ORIGINAL_STABLE
        probe.targetVersion == "updated" -> ProbeClass.UPDATED
        else -> ProbeClass.ORIGINAL_SUPERSEDED
    }

fun EvalSession.requireFact(factId: String): EvalFact =
    facts.firstOrNull { it.id == factId }
        ?: throw FixtureInvalidException("会话 $id：探针指向不存在的事实 $factId")

/** 探针的会话内一致性校验（加载时统一做；按消息/事实/探针三段拆分） */
private fun EvalSession.validate() {
    validateHeader()
    validateMessages()
    validateFacts()
    validateProbes()
}

private fun EvalSession.validateHeader() {
    fun fail(field: String): Nothing = throw FixtureInvalidException("会话 $id：$field")

    if (schemaVersion != 1) fail("schemaVersion 必须为 1，实际 $schemaVersion")
    if (id.isBlank()) fail("id 不能为空")
    if (contextTokenLimit < MIN_EVAL_CONTEXT_TOKENS) {
        fail("contextTokenLimit=$contextTokenLimit 低于应用钳制的最小值 $MIN_EVAL_CONTEXT_TOKENS")
    }
    if (messages.isEmpty()) fail("messages 不能为空")
    if (facts.isEmpty()) fail("facts 不能为空")
    if (probes.isEmpty()) fail("probes 不能为空")
}

private fun EvalSession.validateMessages() {
    fun fail(field: String): Nothing = throw FixtureInvalidException("会话 $id：$field")

    val floors = messages.map { it.floor }
    if (floors != (1..messages.size).toList()) {
        fail("楼层必须从 1 开始连续编号，实际首末=${floors.firstOrNull()}/${floors.lastOrNull()}（共 ${floors.size} 楼）")
    }
    messages.forEach { msg ->
        if (msg.role !in setOf("user", "assistant")) fail("第 ${msg.floor} 楼 role 非法：${msg.role}")
        if (msg.content.isBlank()) fail("第 ${msg.floor} 楼正文为空")
        if (msg.content.contains("<think>")) fail("第 ${msg.floor} 楼正文含 <think> 块（历史段会剥掉它，楼层映射会失真）")
    }
    if (messages.first().role != "assistant") fail("第 1 楼必须是 assistant 开场白")
    // 注：楼层 ↔ 窗口映射按"正文出现"近似（同文重复行会被算进窗口）；夹具里存在少量重复
    // 寒暄行（如"嗯，心里有数了。"），按楼判定会误伤，故不做重复内容的硬校验。
}

private fun EvalSession.validateFacts() {
    fun fail(field: String): Nothing = throw FixtureInvalidException("会话 $id：$field")

    val factIds = HashSet<String>()
    facts.forEach { fact ->
        if (fact.id.isBlank()) fail("存在 id 为空的事实")
        if (!factIds.add(fact.id)) fail("事实 id 重复：${fact.id}")
        if (fact.statement.isBlank()) fail("事实 ${fact.id} 的 statement 为空")
        if (fact.subject.isBlank() || fact.relation.isBlank() || fact.`object`.isBlank()) {
            fail("事实 ${fact.id} 的 subject/relation/object 不能为空")
        }
        if (fact.speaker !in setOf("user", "character")) fail("事实 ${fact.id} 的 speaker 非法：${fact.speaker}")
        if (fact.statementFloor !in 1..messages.size) {
            fail("事实 ${fact.id} 的 statementFloor=${fact.statementFloor} 超出楼层范围 1..${messages.size}")
        }
        validateFactVersionFields(fact, ::fail)
    }
}

private fun EvalSession.validateFactVersionFields(
    fact: EvalFact,
    fail: (String) -> Nothing,
) {
    val hasRestatement = !fact.restatement.isNullOrBlank()
    val hasRestatementFloor = fact.restatementFloor != null
    if (hasRestatement != hasRestatementFloor) {
        fail("事实 ${fact.id} 的 restatement 与 restatementFloor 必须成对出现")
    }
    if (fact.restatementFloor != null && fact.restatementFloor <= fact.statementFloor) {
        fail("事实 ${fact.id} 的 restatementFloor=${fact.restatementFloor} 必须晚于 statementFloor=${fact.statementFloor}")
    }
    val hasUpdate = fact.update != null
    val hasUpdateFloor = fact.updateFloor != null
    if (hasUpdate != hasUpdateFloor) {
        fail("事实 ${fact.id} 的 update 与 updateFloor 必须成对出现")
    }
    if (fact.updateFloor != null && fact.updateFloor > messages.size) {
        fail("事实 ${fact.id} 的 updateFloor=${fact.updateFloor} 超出楼层范围")
    }
    if (fact.updateFloor != null && fact.updateFloor <= fact.statementFloor) {
        fail("事实 ${fact.id} 的 updateFloor=${fact.updateFloor} 必须晚于 statementFloor=${fact.statementFloor}")
    }
    if (fact.restatementFloor != null && fact.updateFloor != null && fact.restatementFloor >= fact.updateFloor) {
        fail("事实 ${fact.id} 的 restatementFloor=${fact.restatementFloor} 必须早于 updateFloor=${fact.updateFloor}")
    }
}

private fun EvalSession.validateProbes() {
    fun fail(field: String): Nothing = throw FixtureInvalidException("会话 $id：$field")

    val probeFloors = HashSet<Int>()
    probes.forEach { probe ->
        if (probe.floor !in 1..messages.size) fail("探针楼层 ${probe.floor} 超出范围")
        if (!probeFloors.add(probe.floor)) fail("探针楼层重复：${probe.floor}")
        val msg = messages.first { it.floor == probe.floor }
        if (msg.role != "user") fail("探针在 ${probe.floor} 楼，但该楼 role 是 ${msg.role}（探针必须是用户发言）")
        if (msg.content != probe.text) {
            fail("探针 ${probe.floor} 楼的 text 与该楼消息正文不一致（评测以探针原话为当前用户消息）")
        }
        val fact = facts.firstOrNull { it.id == probe.targetFactId } ?: fail("探针指向不存在的事实 ${probe.targetFactId}")
        if (probe.targetVersion !in setOf("original", "updated")) {
            fail("探针 targetVersion 非法：${probe.targetVersion}")
        }
        if (probe.targetVersion == "updated" && !fact.hasUpdate) {
            fail("探针 targetVersion=updated 但事实 ${fact.id} 没有更新")
        }
        probe.absentFactIds.forEach { absentId ->
            facts.firstOrNull { it.id == absentId } ?: fail("探针 absentFactIds 指向不存在的事实 $absentId")
        }
    }
}

/** 探针的目标版本在该楼是否有效：原版本 `[statementFloor, updateFloor)`；更新版本自 `updateFloor` 起 */
fun EvalFact.versionValidAt(
    version: String,
    floor: Int,
): Boolean =
    when {
        version == "updated" -> hasUpdate && updateFloor != null && updateFloor <= floor
        else ->
            statementFloor <= floor &&
                (updateFloor == null || floor < updateFloor)
    }

/**
 * 评测资源的极小读取入口：路径形如 `eval/memory/manifest.json`（相对测试资源根）。
 * JVM 实现走类加载器（src/sharedTest/resources 同时挂在 test 与 androidTest 的资源路径上）；
 * 设备侧后续任务实现为读 androidTest APK 的 assets。核心代码不得直接触类加载器。
 */
fun interface EvalResourceResolver {
    fun open(path: String): InputStream?
}

data class LoadedFixtures(
    val sessions: List<EvalSession>,
    val loadFailures: List<SessionLoadException>,
    val summary: String,
)

object MemoryEvalFixtures {
    const val RESOURCE_ROOT = "eval/memory"
    const val MANIFEST_PATH = "$RESOURCE_ROOT/manifest.json"
    const val RECORDING_PATH = "$RESOURCE_ROOT/extraction-recording.json"

    /**
     * 严格 JSON：未知键报错（夹具字段拼错必须立刻暴露）、缺字段由 kotlinx.serialization
     * 的必填校验报错。
     */
    val strictJson: Json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

    // 逐会话容错：单个会话文件的任何解析/IO 异常都按"该会话失败"收集，不打断其余会话
    @Suppress("TooGenericExceptionCaught")
    fun load(resolver: EvalResourceResolver): LoadedFixtures {
        val manifest =
            resolver.open(MANIFEST_PATH)?.use { stream -> stream.readBytes().decodeToString() }
                ?: return LoadedFixtures(emptyList(), emptyList(), "manifest 缺失：$MANIFEST_PATH")
        val parsed = strictJson.decodeFromString<EvalManifest>(manifest)
        require(parsed.schemaVersion == SCHEMA_VERSION) {
            "manifest schemaVersion 必须为 $SCHEMA_VERSION，实际 ${parsed.schemaVersion}"
        }
        val sessions = mutableListOf<EvalSession>()
        val failures = mutableListOf<SessionLoadException>()
        parsed.sessions
            .filter { !it.id.startsWith("_") } // 下划线前缀为格式样例（如 _sample），不参与评测
            .forEach { entry ->
                try {
                    sessions += loadSession(resolver, entry)
                } catch (e: SessionLoadException) {
                    failures += e
                } catch (e: Exception) {
                    // 逐会话容错：单个会话文件的任何问题都收集为失败清单，不打断其余会话的加载
                    failures += SessionLoadException(entry.id, e.message ?: e::class.java.simpleName)
                }
            }
        val summary = "manifest 声明 ${parsed.sessions.size} 个条目；参与评测的会话 ${sessions.size} 个（样例与失败者除外）"
        return LoadedFixtures(sessions, failures, summary)
    }

    /** 装载并校验单个会话（会话文件缺失 / 校验失败 / 楼层数与 manifest 不符都以 FixtureInvalidException 报出） */
    private fun loadSession(
        resolver: EvalResourceResolver,
        entry: EvalManifestEntry,
    ): EvalSession {
        val path = "$RESOURCE_ROOT/sessions/${entry.id}.json"
        val content =
            resolver.open(path)?.use { stream -> stream.readBytes().decodeToString() }
                ?: throw FixtureInvalidException("会话文件缺失：$path")
        val session = strictJson.decodeFromString<EvalSession>(content)
        session.validate()
        require(session.messages.size == entry.floors) {
            "会话 ${entry.id} 楼层数(${session.messages.size})与 manifest(${entry.floors})不一致"
        }
        return session
    }
}

/**
 * 内联最小会话（夹具完全不可用时打通驱动器用；两三条事实、两个探针，
 * 形状与脚本产出一致——单元断言与指标计算不区分两者）。
 * 楼层号/档位/楼层数都是内联夹具的域数据（statements 表与探针楼层一一对应），
 * 不视作魔法数（MagicNumber 豁免）。
 */
@Suppress("MagicNumber")
object InlineMinimalSessions {
    /** 事实、重述、更新与探针的原话都按其楼层真实出现在对话里（与脚本夹具同构） */
    private val STATEMENTS: Map<Int, String> =
        mapOf(
            2 to "我叫沈砚，是个修钟表的。",
            4 to "我从小对花生过敏，千万别往我碗里放。",
            6 to "我上个月搬到了城东的青石巷。",
            30 to "花生我是碰不得的，吃了要进医院的。",
            40 to "青石巷住腻了，我搬去城南的杨柳巷了。",
        )

    private const val PROBE1_FLOOR = 20
    private const val PROBE2_FLOOR = 46
    private const val PROBE1_TEXT = "您还记得我姓什么、干哪一行吗？"
    private const val PROBE2_TEXT = "我如今住的地方是哪条巷子，你还想得起来吗？"

    fun build(): List<EvalSession> {
        val session =
            EvalSession(
                schemaVersion = 1,
                id = "inline01",
                title = "内联最小会话",
                contextTokenLimit = 1024,
                messages = inlineMessages(),
                facts = inlineFacts(),
                probes = inlineProbes(),
            )
        return listOf(session)
    }

    private fun inlineMessages(): List<EvalMessage> {
        val probeTexts = mapOf(PROBE1_FLOOR to PROBE1_TEXT, PROBE2_FLOOR to PROBE2_TEXT)
        return buildList {
            add(EvalMessage(1, "assistant", "（开场白）今晚的摊子刚支起来，炉火正旺。"))
            for (floor in 2..60) {
                add(EvalMessage(floor, floorRole(floor), contentAt(floor, probeTexts)))
            }
        }
    }

    private fun floorRole(floor: Int): String = if (floor % 2 == 0) "user" else "assistant"

    private fun contentAt(
        floor: Int,
        probeTexts: Map<Int, String>,
    ): String =
        when {
            STATEMENTS.containsKey(floor) -> STATEMENTS.getValue(floor)
            probeTexts.containsKey(floor) -> probeTexts.getValue(floor)
            floor % 2 == 0 -> "今晚摊子上生意怎么样？"
            else -> "托您的福，还算热闹，您坐这桌正合适。"
        }

    private fun inlineFacts(): List<EvalFact> =
        listOf(
            EvalFact(
                id = "f01",
                memoryType = "identity",
                subject = "user",
                relation = "name",
                `object` = "沈砚",
                speaker = "user",
                statement = STATEMENTS.getValue(2),
                statementFloor = 2,
            ),
            EvalFact(
                id = "f02",
                memoryType = "preference",
                subject = "user",
                relation = "dislikes",
                `object` = "花生",
                speaker = "user",
                statement = STATEMENTS.getValue(4),
                statementFloor = 4,
                restatement = STATEMENTS.getValue(30),
                restatementFloor = 30,
            ),
            EvalFact(
                id = "f03",
                memoryType = "location",
                subject = "user",
                relation = "lives_in",
                `object` = "青石巷",
                speaker = "user",
                statement = STATEMENTS.getValue(6),
                statementFloor = 6,
                update = EvalUpdate(statement = STATEMENTS.getValue(40), probe = PROBE2_TEXT),
                updateFloor = 40,
            ),
        )

    private fun inlineProbes(): List<EvalProbe> =
        listOf(
            EvalProbe(
                floor = PROBE1_FLOOR,
                targetFactId = "f01",
                targetVersion = "original",
                absentFactIds = emptyList(),
                text = PROBE1_TEXT,
            ),
            EvalProbe(
                floor = PROBE2_FLOOR,
                targetFactId = "f03",
                targetVersion = "updated",
                absentFactIds = listOf("f03"),
                text = PROBE2_TEXT,
            ),
        )
}
