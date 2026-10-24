package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.model.GroupChatContext
import com.mistbell.tavern.android.data.api.model.MemoryType
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.api.model.truncateMemoryContent
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.data.regex.CardRegexEngine
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.data.repository.LexicalMemoryService
import com.mistbell.tavern.android.data.repository.MemoryScope
import com.mistbell.tavern.android.data.repository.RecallRanker
import com.mistbell.tavern.android.data.repository.RecallWindow
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.extension.ExtensionRegistry
import com.mistbell.tavern.android.extension.PromptHookContext
import com.mistbell.tavern.android.util.MacroContext
import com.mistbell.tavern.android.util.MacroEngine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

object PromptBuilder {
    /** World Info 的会话内运行状态：sticky/cooldown/delay 只影响激活决策，不改变 Room 主数据。 */
    @Suppress("TooManyFunctions") // 匹配/门控/竞争/预算按职责拆小函数，优于挤进少数大函数
    internal object WorldInfoRuntime {
        private const val MAX_RECURSION_DEPTH = 3

        // 次关键词四态逻辑（对齐酒馆 world_info_logic）
        const val LOGIC_AND_ANY = 0
        const val LOGIC_NOT_ALL = 1
        const val LOGIC_NOT_ANY = 2
        const val LOGIC_AND_ALL = 3

        internal data class EntryState(var lastTurn: Int = -1, var stickyUntil: Int = -1)

        private data class SessionState(var turn: Int = 0, val entries: MutableMap<String, EntryState> = mutableMapOf())

        private data class Candidate(
            val entry: WorldBookEntryEntity,
            val key: String,
            val sticky: Boolean,
            /** 命中关键词数（主+次），仅 useGroupScoring 分组选拔时使用 */
            val score: Int,
        )

        /** 一次激活判定的输入（收拢为参数对象，避免 activate 参数表膨胀） */
        data class ScanInput(
            val historyMessages: List<String>,
            val userMessage: String,
            val globalScanDepth: Int,
            val mctx: MacroContext,
            /** constant 条目消耗后的剩余预算额度（Int.MAX_VALUE = 不限） */
            val budgetTokens: Int = Int.MAX_VALUE,
            /** 递归扫描开关（对齐酒馆 world_info_recursive，默认关）：已激活内容是否参与后续轮匹配 */
            val recursive: Boolean = false,
            /** 最小激活条目数（酒馆 world_info_min_activations）：>0 且未满足时逐轮加深扫描（skew++） */
            val minActivations: Int = 0,
            /** 加深扫描的深度上限（酒馆 min_activations_depth_max）：0=不限（到聊天长度为止） */
            val minActivationsDepthMax: Int = 0,
            /** 会话级持久化的 timed effects 快照（酒馆 chat_metadata.timedWorldInfo）；null/空=无状态 */
            val persistedStateJson: String? = null,
            /** 当前会话参与角色名/标签（characterFilter 匹配面）；空集=不过滤 */
            val participantNames: Set<String> = emptySet(),
            val participantTags: Set<String> = emptySet(),
            /** 分组按匹配分选拔的全局开关（酒馆 world_info_use_group_scoring，默认关） */
            val useGroupScoring: Boolean = false,
        )

        /** 激活结果：通过判定（含预算）的条目 + 因预算被裁的条目 + 待持久化的状态快照 */
        data class ActivationResult(
            val activated: Set<String>,
            val droppedByBudget: List<WorldBookEntryEntity>,
            val stateSnapshotJson: String,
        )

        /** timed effects 的持久化形态（写 sessions.wi_state_json；对齐酒馆 timedWorldInfo 语义） */
        @kotlinx.serialization.Serializable
        internal data class WiEntryStateSnapshot(
            val lastTurn: Int = -1,
            val stickyUntil: Int = -1,
        )

        @kotlinx.serialization.Serializable
        internal data class WiSessionSnapshot(
            val turn: Int = 0,
            val entries: Map<String, WiEntryStateSnapshot> = emptyMap(),
        )

        internal fun encodeSnapshot(
            turn: Int,
            entries: Map<String, EntryState>,
        ): String =
            kotlinx.serialization.json.Json.encodeToString(
                WiSessionSnapshot(
                    turn,
                    entries.mapValues { WiEntryStateSnapshot(it.value.lastTurn, it.value.stickyUntil) },
                ),
            )

        internal fun decodeSnapshot(json: String): Pair<Int, Map<String, WiEntryStateSnapshot>> =
            try {
                val snap = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<WiSessionSnapshot>(json)
                snap.turn to snap.entries
            } catch (_: Exception) {
                0 to emptyMap()
            }

        /** WI 预算记账器：charge 返回 false = 该条目因预算被裁（溢出即停并记入 dropped） */
        private class BudgetTracker(
            var remaining: Int,
        ) {
            var overflowed = false
                private set
            val dropped = mutableListOf<WorldBookEntryEntity>()

            fun charge(entry: WorldBookEntryEntity): Boolean {
                // ignoreBudget（对齐酒馆）：不占额度、溢出也不被裁
                if (entry.ignoreBudget || remaining == Int.MAX_VALUE) return true
                val cost = estimateTokens(entry.content)
                val fits = !overflowed && cost <= remaining
                if (!fits) {
                    overflowed = true
                    dropped += entry
                } else {
                    remaining -= cost
                }
                return fits
            }
        }

        private val sessions = mutableMapOf<String, SessionState>()

        /**
         * Activates World Info for one prompt.  Matching is recursive: content activated in
         * an earlier pass becomes scan material for the next pass, bounded to three passes.
         * Secondary keys follow SillyTavern's four selectiveLogic modes (gated by `selective`).
         * Entries sharing a non-empty group compete SillyTavern-style: sticky winners stay,
         * groupOverride wins by highest order, otherwise weighted random by groupWeight.
         *
         * 扫描窗口对齐酒馆：每条目 `scanDepth ?: globalScanDepth`（从最新消息往上数 N 条，
         * 当前用户消息占第 1 条）；关键词先过宏渲染再匹配（对齐 substituteParams）。
         * 预算按 sticky 优先、order 降序消费（酒馆处理序），溢出即停且不再递归。
         */
        @Synchronized
        fun activate(
            sessionId: String,
            entries: List<WorldBookEntryEntity>,
            scan: ScanInput,
        ): ActivationResult {
            val state =
                sessions.getOrPut(sessionId) {
                    // 会话首次进入内存：从持久化快照恢复（对齐酒馆 timedWorldInfo 跨重启存续）
                    scan.persistedStateJson?.takeIf { it.isNotBlank() }?.let { json ->
                        val (turn, savedEntries) = decodeSnapshot(json)
                        SessionState(
                            turn = turn,
                            entries =
                                savedEntries
                                    .mapValues { EntryState(it.value.lastTurn, it.value.stickyUntil) }
                                    .toMutableMap(),
                        )
                    } ?: SessionState()
                }
            state.turn += 1
            val run = ScanRun(state, BudgetTracker(scan.budgetTokens))
            // 递归开关关闭时只扫一轮（酒馆默认 world_info_recursive=false）
            val maxRounds = if (scan.recursive) MAX_RECURSION_DEPTH else 1
            // min-activations 加深扫描（酒馆 skew）：轮次耗尽仍未凑够激活数时，全局深度逐轮 +1 续扫
            val scanCeiling =
                if (scan.minActivationsDepthMax > 0) scan.minActivationsDepthMax else scan.historyMessages.size + 1
            var passIndex = 0
            var skew = 0

            fun minUnmet() = scan.minActivations > 0 && run.activated.size < scan.minActivations

            fun canDeepen() = minUnmet() && scan.globalScanDepth + skew < scanCeiling

            // 轮次推进：递归轮数内 / min-activations 未满足且可加深 时继续；空轮且无加深需求即停
            do {
                val deepen = passIndex >= maxRounds
                if (deepen) skew++
                val roundScan = if (skew > 0) scan.copy(globalScanDepth = scan.globalScanDepth + skew) else scan
                val newCount = run.pass(entries, roundScan, passIndex)
                passIndex++
            } while (
                !run.budget.overflowed && // 预算溢出：与酒馆一致，不再递归/加深
                (passIndex < maxRounds || canDeepen()) &&
                (newCount > 0 || canDeepen())
            )
            return ActivationResult(run.activated, run.budget.dropped, encodeSnapshot(state.turn, state.entries))
        }

        /** 一轮扫描的可变状态与执行体（从 activate 拆出，控制圈复杂度） */
        private class ScanRun(
            val state: SessionState,
            val budget: BudgetTracker,
        ) {
            val activated = linkedSetOf<String>()
            val selectedGroups = mutableSetOf<String>()
            val activatedContents = mutableListOf<String>()

            /** 执行一轮扫描，返回本轮新激活条数 */
            fun pass(
                entries: List<WorldBookEntryEntity>,
                scan: ScanInput,
                passIndex: Int,
            ): Int {
                val candidates =
                    entries
                        .asSequence()
                        .filter { !it.disable && !it.constant }
                        .filter { characterFilterAllows(it, scan) }
                        // 递归层级门控（对齐酒馆）：delayUntilRecursion 层起才允许；preventRecursion 仅首轮
                        .filter { passIndex >= it.delayUntilRecursion && (!it.preventRecursion || passIndex == 0) }
                        .filter { entry ->
                            val key = "${entry.bookId}:${entry.id}"
                            key !in activated && (entry.groupName.isBlank() || entry.groupName !in selectedGroups)
                        }.mapNotNull { entry ->
                            evaluateCandidate(entry, state, scan, activatedContents)
                        }.toList()
                val winners = if (candidates.isEmpty()) emptyList() else selectWinners(candidates, scan.useGroupScoring)
                val newlyActivated = mutableListOf<String>()
                winners.forEach { candidate ->
                    if (tryActivateWinner(candidate, state, budget)) {
                        activated += candidate.key
                        newlyActivated += candidate.key
                        candidate.entry.groupName.takeIf { it.isNotBlank() }?.let(selectedGroups::add)
                    }
                }
                // excludeRecursion 条目的内容不进递归缓冲（对齐酒馆）
                activatedContents +=
                    entries.filter { "${it.bookId}:${it.id}" in newlyActivated && !it.excludeRecursion }
                        .map { it.content }
                return newlyActivated.size
            }
        }

        /** 单个胜者：概率掷骰 + 预算记账 + 状态更新；返回 true=本轮新激活 */
        private fun tryActivateWinner(
            candidate: Candidate,
            state: SessionState,
            budget: BudgetTracker,
        ): Boolean {
            val entry = candidate.entry
            val passed =
                (entry.probability >= 1.0 || kotlin.random.Random.nextDouble() < entry.probability) &&
                    budget.charge(entry)
            if (passed) {
                val entryState = state.entries.getOrPut(candidate.key) { EntryState() }
                entryState.lastTurn = state.turn
                // sticky 数值语义（对齐酒馆）：仅在非粘性激活（关键词命中）时武装/刷新计时；
                // 粘性期间的续命激活不刷新，N 轮后自然过期，过期后 cooldown 接力（lastTurn 已连续更新）
                if (!candidate.sticky && entry.sticky > 0) {
                    entryState.stickyUntil = state.turn + entry.sticky
                }
            }
            return passed
        }

        /** 单条目评估：命中（含四态次键门控/sticky/delay/cooldown）则产出候选，否则 null */
        private fun evaluateCandidate(
            entry: WorldBookEntryEntity,
            state: SessionState,
            scan: ScanInput,
            activatedContents: List<String>,
        ): Candidate? {
            val key = "${entry.bookId}:${entry.id}"
            val entryState = state.entries.getOrPut(key) { EntryState() }
            val corpus = scanWindow(entry, scan, activatedContents)
            val primaryHits = matchingKeywords(parseKeywords(entry.keysJson), corpus, entry, scan.mctx)
            val secondaryKeys = parseKeywords(entry.secondaryKeysJson)
            // 仅 selective=true 且次键非空时启用四态门控（对齐酒馆 hasSecondaryKeywords）
            val gateOn = entry.selective && secondaryKeys.isNotEmpty()
            val secondaryHits = if (gateOn) matchingKeywords(secondaryKeys, corpus, entry, scan.mctx) else 0
            val stickyMatched = entry.sticky > 0 && entryState.stickyUntil >= state.turn
            val keyMatched = stickyMatched || (primaryHits > 0 && secondaryGatePassed(entry, secondaryKeys, secondaryHits))
            if (!keyMatched || !timingGatePassed(entry, entryState, state.turn)) return null
            return Candidate(entry = entry, key = key, sticky = stickyMatched, score = primaryHits + secondaryHits)
        }

        /** 次键四态门控（对齐酒馆）：selective=false 或次键为空 → 不启用门控；命中数由调用方算好传入 */
        private fun secondaryGatePassed(
            entry: WorldBookEntryEntity,
            secondaryKeys: List<String>,
            secondaryHits: Int,
        ): Boolean {
            if (secondaryKeys.isEmpty() || !entry.selective) return true
            return when (entry.selectiveLogic) {
                LOGIC_NOT_ALL -> secondaryHits < secondaryKeys.size
                LOGIC_NOT_ANY -> secondaryHits == 0
                LOGIC_AND_ALL -> secondaryHits == secondaryKeys.size
                else -> secondaryHits > 0 // AND_ANY（缺省）
            }
        }

        /** 角色过滤（对齐酒馆 characterFilter）：名单/标签任一命中即"匹配"；exclude=true 反转为黑名单 */
        private fun characterFilterAllows(
            entry: WorldBookEntryEntity,
            scan: ScanInput,
        ): Boolean {
            val names = parseKeywords(entry.characterFilterNamesJson)
            val tags = parseKeywords(entry.characterFilterTagsJson)
            if (names.isEmpty() && tags.isEmpty()) return true
            val matched =
                names.any { f -> scan.participantNames.any { it.equals(f, ignoreCase = true) } } ||
                    tags.any { f -> scan.participantTags.any { it.equals(f, ignoreCase = true) } }
            return if (entry.characterFilterExclude) !matched else matched
        }

        /** delay（等待轮数）与 cooldown（冷却轮数）门控；delay=0 当前轮即可激活 */
        private fun timingGatePassed(
            entry: WorldBookEntryEntity,
            entryState: EntryState,
            turn: Int,
        ): Boolean {
            val delayed = turn > entry.delay.coerceAtLeast(0)
            val cooledDown =
                entry.cooldown <= 0 || entryState.lastTurn < 0 || turn - entryState.lastTurn > entry.cooldown
            return delayed && cooledDown
        }

        /**
         * 分组竞争（对齐酒馆）：同组 sticky 候选强制胜出（全部保留）→ groupOverride 取最高 order →
         * useGroupScoring（条目三态 ?? 全局）保留最高分（平分都留）→ groupWeight 加权随机。
         * 组外条目原样保留。返回序按预算消费序：sticky 在前、order 降序（高 order 优先保住）。
         */
        private fun selectWinners(
            candidates: List<Candidate>,
            globalGroupScoring: Boolean,
        ): List<Candidate> =
            candidates
                .groupBy { it.entry.groupName }
                .values
                .flatMap { group ->
                    if (group.first().entry.groupName.isBlank()) return@flatMap group
                    val stickyWinners = group.filter { it.sticky }
                    when {
                        stickyWinners.isNotEmpty() -> stickyWinners
                        else -> {
                            val overrides = group.filter { it.entry.groupOverride }
                            when {
                                overrides.isNotEmpty() ->
                                    listOf(overrides.maxWithOrNull(compareBy<Candidate> { it.entry.order })!!)
                                // 酒馆 useGroupScoring：保留最高分（平分都留）
                                group.first().entry.useGroupScoring ?: globalGroupScoring -> {
                                    val maxScore = group.maxOf { it.score }
                                    group.filter { it.score == maxScore }
                                }
                                else -> listOf(weightedRandomPick(group))
                            }
                        }
                    }
                }
                .sortedWith(compareByDescending<Candidate> { it.sticky }.thenByDescending { it.entry.order })

        /** 加权随机（对齐酒馆）：roll ∈ [0, 总权重)，按条目顺序首个累计权重越过 roll 者胜 */
        private fun weightedRandomPick(group: List<Candidate>): Candidate {
            val totalWeight = group.sumOf { it.entry.groupWeight.coerceAtLeast(0) }
            if (totalWeight <= 0) return group.first()
            var roll = kotlin.random.Random.nextDouble() * totalWeight
            return group.firstOrNull { c ->
                roll -= c.entry.groupWeight.coerceAtLeast(0)
                roll < 0
            } ?: group.last()
        }

        /**
         * 单条目扫描窗口（对齐酒馆 buffer.get）：最近 (depth-1) 条历史 + 当前用户消息 +
         * 条目级 matchXxx 扩展（人设/角色描述/场景）+ 递归缓冲（本轮已激活条目的内容）。
         * depth ≤ 0 返回空（该条目永不通过扫描激活）。
         */
        internal fun scanWindow(
            entry: WorldBookEntryEntity,
            scan: ScanInput,
            activatedContents: List<String>,
        ): String {
            val depth = (entry.scanDepth ?: scan.globalScanDepth).coerceAtMost(MAX_SCAN_WINDOW)
            if (depth <= 0) return ""
            return buildString {
                scan.historyMessages.takeLast(depth - 1).forEach { appendLine(it) }
                appendLine(scan.userMessage)
                // 扫描面扩展（对齐酒馆 matchXxx）：并入该条目勾选的全局上下文块
                if (entry.matchPersonaDescription && scan.mctx.persona.isNotBlank()) {
                    appendLine(scan.mctx.persona)
                }
                if (entry.matchCharacterDescription && scan.mctx.description.isNotBlank()) {
                    appendLine(scan.mctx.description)
                }
                if (entry.matchScenario && scan.mctx.scenario.isNotBlank()) {
                    appendLine(scan.mctx.scenario)
                }
                if (activatedContents.isNotEmpty()) {
                    activatedContents.forEach { appendLine(it) }
                }
            }
        }

        /** 酒馆 MAX_SCAN_DEPTH=1000；应用内扫描窗口上限（防御异常大值拖慢匹配） */
        private const val MAX_SCAN_WINDOW = 1000

        private fun parseKeywords(keysJson: String): List<String> =
            runCatching {
                if (keysJson.isBlank()) emptyList() else Json.decodeFromString<List<String>>(keysJson)
            }.getOrDefault(emptyList())

        private fun matchingKeywords(
            keys: List<String>,
            text: String,
            entry: WorldBookEntryEntity,
            mctx: MacroContext,
        ): Int = keys.count { matchesKey(MacroEngine.render(it, mctx), text, entry) }

        /**
         * 单关键词匹配（对齐酒馆 matchKeys）：
         * ① `/pattern/flags` 正则——flags 原样映射（无默认忽略大小写），且不受大小写/全字开关影响；
         * ② `*` 通配（应用内扩展）——大小写随条目 caseSensitive；
         * ③ matchWholeWords 且单词 key——非词边界 `(?:^|\W)key(?:$|\W)`；多词 key 退化为普通包含；
         * ④ 普通包含。大小写敏感缺省（null）为关。
         */
        internal fun matchesKey(
            rawKey: String,
            text: String,
            entry: WorldBookEntryEntity,
        ): Boolean {
            val key = rawKey.trim()
            if (key.isBlank()) return false
            val caseSensitive = entry.caseSensitive ?: false
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val regex = parseRegexKey(key)
            return when {
                regex != null -> runCatching { regex.containsMatchIn(text) }.getOrDefault(false)
                key.contains("*") -> {
                    val pattern = key.split("*").joinToString(".*") { Regex.escape(it) }
                    runCatching { Regex("(?s).*$pattern.*", options).matches(text) }.getOrDefault(false)
                }
                entry.matchWholeWords == true && !key.contains(Regex("\\s")) ->
                    runCatching {
                        Regex("(?:^|\\W)" + Regex.escape(key) + "(?:$|\\W)", options).containsMatchIn(text)
                    }.getOrDefault(false)
                else -> text.contains(key, ignoreCase = !caseSensitive)
            }
        }

        /**
         * `/pattern/flags` 解析（对齐酒馆 parseRegexFromString）：pattern 内含未转义 `/` 不视为正则；
         * flags 仅映射 i/m/s（g/u/y 对匹配语义无影响或 Kotlin 无对应项）。
         */
        private fun parseRegexKey(key: String): Regex? {
            val match = Regex("^/([\\w\\W]+?)/([gimsuy]*)$").find(key)
            val rawPattern = match?.groups?.get(1)?.value
            val flags = match?.groups?.get(2)?.value
            // pattern 内含未转义的 `/` 说明不是正则字面量（与酒馆同判定）
            if (rawPattern == null || flags == null || Regex("(^|[^\\\\])/").containsMatchIn(rawPattern)) {
                return null
            }
            val pattern = rawPattern.replace("\\/", "/")
            val options =
                buildSet {
                    if ('i' in flags) add(RegexOption.IGNORE_CASE)
                    if ('m' in flags) add(RegexOption.MULTILINE)
                    if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
                }
            return runCatching { Regex(pattern, options) }.getOrNull()
        }
    }
    // ---- 群聊模式常量（classic 模式不进入任何 group 分支，提示词一个字符都不变——硬约束）----

    // 群聊行为规范块的内容与默认模板已移入 ChatSettingsResolver（可配置 + 单一默认值来源）：
    // 模板必须包含前缀格式（『角色名:』半角冒号）、不替用户/他人发言、一次只发言一位，
    // 以及历史前缀格式说明（历史每行已带『名字:』前缀，模型照此格式续写即可提高命中率）

    // 目标角色推动语模板（targetSpeakerId 非空时追加一行）
    private const val GROUP_CHAT_TARGET_TEMPLATE = "（接下来请由 %s 回应）"

    // ---- 提示词模板段的来源标签（「查看提示词」抽屉逐段归因用）----
    private const val SOURCE_MAIN_PROMPT = "主提示词"
    private const val SOURCE_PERSONA = "用户人设"
    private const val SOURCE_POST_HISTORY = "历史后指令"
    private const val SOURCE_GROUP_RULES = "群聊规范"

    // 历史归属兜底：speakerNames 查不到的 character_id 取前 4 位，仍为空则用通用名
    private const val GROUP_SPEAKER_NAME_FALLBACK_LENGTH = 4
    private const val GROUP_SPEAKER_FALLBACK_NAME = "角色"

    // 记忆重复注入判定的最小长度：短于该值的正文不做"内容包含"比对，避免误伤不同事实
    private const val MIN_DUPLICATE_LENGTH = 8
    private const val MEMORY_CANDIDATE_LIMIT = 100

    // 混合召回：向量路候选取 topK 的倍数（融合时词法路还会补一批，名次重排后截断回 topK）
    private const val HYBRID_VECTOR_CANDIDATE_MULTIPLIER = 3

    /**
     * 记忆链就绪闸门（可注入，object 无构造注入点，测试可整体替换）：
     * 默认走 AppContainer 生产闸门——升级清零未完成时抛 [MemoryResetPendingException]。
     * 召回入口（buildMemoryContext/recallCandidates）只许以"空结果"降级，绝不把异常
     * 抛进对话主流程，也绝不放行旧数据扩大召回。
     */
    internal var memoryResetGate: suspend () -> Unit = {
        TavernApplication.instance.container.sessionMemoryResetGate()
    }

    internal var vectorMemoryServiceProvider: () -> VectorMemoryService = {
        TavernApplication.instance.container.vectorMemoryService
    }

    /**
     * 召回路径的闸门等待：未通过返回 false（调用方按空结果降级）。
     * 不召回优于召错——清零期间读到的记忆是待删除的旧数据，泄进提示词就是串线。
     */
    private suspend fun awaitGateForRecall(): Boolean =
        try {
            memoryResetGate()
            true
        } catch (e: Exception) {
            android.util.Log.w("PromptBuilder", "记忆链闸门未通过，召回降级为空：${e.message}")
            false
        }

    // 记忆块 token 预算：占上下文长度的比例 + 上下限。
    // 注入条数（最多 10 条）挡不住长正文——10 条各上千字足以顶掉整段历史，
    // 只有 token 预算才是硬约束（上下文极小时不至于一点记忆都放不下，极大时也不放任膨胀）。
    private const val MEMORY_BLOCK_BUDGET_RATIO = 0.25
    private const val MEMORY_BLOCK_MIN_TOKENS = 120
    private const val MEMORY_BLOCK_MAX_TOKENS = 1200

    /** 未归入任何分组的记忆统一收在这段标题下 */
    private const val FACTS_HEADER = "其他事实："

    // 记忆块的分组与注入顺序：归类后的事实比平铺一列更容易被模型分辨和引用
    private val MEMORY_GROUPS: Map<String, Set<String>> =
        linkedMapOf(
            "用户信息" to setOf("character_info", "identity", "preference"),
            "关系" to setOf("relationship"),
            "重要事件" to setOf("event", "core", "goal"),
            "情绪与边界" to setOf("emotion"),
            "相关物品" to setOf("item"),
            "相关地点" to setOf("location"),
        )
    private val THINKING_BLOCK_REGEX = Regex("(?s)<think>[\\s\\S]*?</think>")

    // ---- 提示词溯源（聊天页「查看提示词」用） ----

    /**
     * 提示词中的一段及其来源标签。
     *
     * 装配过程内部一律用 [Segment] 累积（而不是裸 [ChatMessage]），末尾再统一转成
     * [ChatMessage] 列表——保证「预览看到的」与「真实发出去的」是**同一次装配的产物**，
     * 不存在两份会各自漂移的实现。
     */
    data class Segment(
        val message: ChatMessage,
        /** 来源标签，如「角色卡」「世界书·角色定义后」「长期记忆」 */
        val source: String,
        /** World Info 激活原因；普通提示词段为空。 */
        val activationReason: String? = null,
        /** 实际插入锚点或 @D 位置。 */
        val insertionPosition: String? = null,
        /** 该段被裁剪/降级的原因；未裁剪时为空。 */
        val truncationReason: String? = null,
        /** 从统一 ChatMessage 转换到提供商协议后的结果摘要。 */
        val protocolConversionResult: String? = null,
    )

    /** 一次装配的完整结果：分段（含来源）与总量估算 */
    data class PromptTrace(
        val segments: List<Segment>,
        val totalEstimatedTokens: Int,
        val truncationReasons: List<String> = emptyList(),
        val protocolConversionResults: List<String> = emptyList(),
        /** 本轮长期记忆召回的诊断（来源/条数/耗时/缓存命中；与本次装配同源） */
        val recallDiagnostics: RecallDiagnostics? = null,
    ) {
        /** 单段估算 token（预览页展示用；与 PromptDiag 同一估算口径） */
        fun tokensOf(segment: Segment): Int = estimateTokensOf(segment.message.content)
    }

    private const val SOURCE_CHARACTER = "角色卡"

    /** token 估算的无状态版本（供 [Segment] 外部调用，避免暴露整个 object） */
    internal fun estimateTokensOf(text: String): Int = estimateTokens(text)

    /** 追加一段（带来源标签） */
    private fun MutableList<Segment>.addSegment(
        role: String,
        content: String,
        source: String,
    ) {
        val isWorldInfo = source.startsWith("世界书")
        add(
            Segment(
                message = ChatMessage(role = role, content = content),
                source = source,
                activationReason =
                    when {
                        source.contains("激活") -> "关键词/次关键词命中且通过概率"
                        isWorldInfo -> "constant 常驻"
                        else -> null
                    },
                insertionPosition = source.removePrefix("世界书·").takeIf { isWorldInfo },
            ),
        )
    }

    // ---- 提示词模板段（纯函数，internal 仅为单元测试开放）----
    // 三者的共同契约：**空值不产生消息**（不注入空段），保证四项全空时 classic 提示词与改动前逐字节一致

    /** 全局主提示词 → 段；未设置返回 null。内容过宏渲染（支持 {{char}}/{{user}}/{{persona}} 等） */
    internal fun mainPromptSegment(
        raw: String?,
        mctx: MacroContext,
    ): Segment? =
        raw?.takeIf { it.isNotBlank() }?.let {
            Segment(ChatMessage(role = "system", content = MacroEngine.render(it, mctx)), SOURCE_MAIN_PROMPT)
        }

    /** 用户人设（`{{persona}}` 内容）→ 段；未设置返回 null */
    internal fun personaSegment(
        raw: String?,
        mctx: MacroContext,
    ): Segment? =
        raw?.takeIf { it.isNotBlank() }?.let {
            Segment(ChatMessage(role = "system", content = MacroEngine.render(it, mctx)), SOURCE_PERSONA)
        }

    /**
     * 角色卡 `post_history_instructions` → 段；字段缺失/空白/`data_json` 损坏均返回 null。
     *
     * @param charDataJson 角色实体的 `data_json`（内含 CharacterData）
     * @param fallbackChar 解析失败时用于宏渲染的 {{char}} 兜底名（取角色实体名，避免宏未展开）
     */
    internal fun postHistorySegment(
        charDataJson: String,
        fallbackChar: String,
        mctx: MacroContext,
    ): Segment? {
        if (charDataJson.isBlank()) return null
        val instructions =
            try {
                kotlinx.serialization.json.Json
                    .decodeFromString<com.mistbell.tavern.android.data.api.model.CharacterData>(charDataJson)
                    .postHistoryInstructions
            } catch (_: Exception) {
                // data_json 损坏：与角色卡内 systemPrompt 的解析一样静默丢弃，绝不让装配失败
                return null
            }
        if (instructions.isBlank()) return null
        // fallbackChar 仅在 mctx.char 为空时兜底，保证 {{char}} 不会渲染成空串
        val ctx = if (mctx.char.isBlank()) mctx.copy(char = fallbackChar) else mctx
        return Segment(ChatMessage(role = "system", content = MacroEngine.render(instructions, ctx)), SOURCE_POST_HISTORY)
    }

    // token 估算的字符/token 比率：ASCII 按 4；CJK 按 **1.5**（实测标定，见下）。
    //
    // 标定依据（设备实测对拍，非推断）：同一份提示词 估算 16,663 vs API 实发 13,222 token，
    // 反解得 CJK 真实比率 ≈ 1.505 字符/token。三档对比：
    //   1.15 → 高估 +26%（历史被白裁，配置 1M 也只能装进 2.4K 历史）
    //   1.5  → 偏差 +0.3%（采用）
    //   1.6  → 低估  −5%
    // 注意：此前的"1.6 → 1.15 校准"基于错误归因（把"固定内容永不裁剪导致的实发超配置"
    // 当成"估算偏低"），实测证明 1.6 只差 5%，1.15 反而高估 26%
    private const val ASCII_CHARS_PER_TOKEN = 4.0
    private const val CJK_CHARS_PER_TOKEN = 1.5

    /** WI 预算百分比除数（world_info_budget_percent 是 0-100 百分数） */
    private const val PERCENT_BASE = 100

    /**
     * 一次提示词装配的全部入参（打包治长参数表；调用方只构造本对象）。
     */
    data class PromptRequest(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val userMessage: String,
        // 装配历史时过滤掉这条（刚落库的当前用户消息），避免同一条在 prompt 中重复出现
        val currentMessageId: String? = null,
        // 重新生成场景：截断该消息及其之后的全部历史（按查询返回的时间序），
        // 保证正要被替换的旧 assistant 回复不进入上下文
        val excludeFromMessageId: String? = null,
        // 群聊上下文（默认 null = classic 模式，行为与改动前完全一致）
        val groupContext: GroupChatContext? = null,
        // continueGroupChat 场景的系统级推动语（由 ChatRepository 传入，本类不做特判）；
        // 非 null 时不再追加最终用户消息，改为注入这条 system 消息
        val groupNudge: String? = null,
        // API 配置文档提供的本地 prompt 输入预算；会话显式覆盖仍优先
        val contextTokenLimitOverride: Int? = null,
    )

    suspend fun buildPrompt(
        db: AppDatabase,
        request: PromptRequest,
    ): List<ChatMessage> = buildPromptTrace(db, request).segments.map { it.message }

    /**
     * 装配并返回带来源标注的提示词（聊天页「查看提示词」入口）。
     *
     * 与 [buildPrompt] **共用同一条装配路径**：本函数是唯一实现，buildPrompt 只是取其
     * `segments.map { it.message }`，所以预览内容与真实请求逐字节一致。
     *
     * 实现形态：阶段化装配——[AssemblyCtx] 持有一次装配的全部共享状态，阶段函数按序执行。
     * **阶段顺序即语义**：拆分只做剪切提取，不重排任何段的产生时机，输出与拆分前逐字节一致。
     */
    suspend fun buildPromptTrace(
        db: AppDatabase,
        request: PromptRequest,
    ): PromptTrace {
        val ctx = AssemblyCtx(db, request)
        ctx.loadCore()
        ctx.appendCharacterCard()
        ctx.appendPersona()
        ctx.appendGroupRules()
        ctx.resolveWorldInfoSettings()
        ctx.loadWorldBookEntries()
        ctx.runWorldInfoActivation()
        ctx.appendFrontMatter()
        ctx.buildMemory()
        ctx.appendHistory()
        ctx.appendPostHistoryBlocks()
        ctx.buildDepthBlocks()
        ctx.appendMemoryBlocks()
        ctx.appendActivatedWorldInfo()
        ctx.appendFinalMessage()
        ctx.insertDepthBlocksIfNeeded()
        ctx.runInterceptors()
        return ctx.buildTrace()
    }

    /**
     * 一次装配的共享状态与阶段函数集合。
     *
     * 中间产物（WI 计划/预算/激活结果/记忆/历史窗口等）一律经本类属性在阶段间传递；
     * 嵌套在 object 内：阶段函数直接使用外层全部既有 helper（worldInfoBlock 等），零搬迁。
     */
    @Suppress("TooManyFunctions") // 阶段化装配：按职责拆小函数优于挤进单体函数
    private class AssemblyCtx(
        val db: AppDatabase,
        val request: PromptRequest,
    ) {
        val messages = mutableListOf<Segment>()

        // ---- loadCore 产物 ----
        var session: SessionEntity? = null
        var contextTokenLimit = 0
        var longTermMemoryEnabled = false
        var participantCharacters: List<CharacterEntity> = emptyList()
        var character: CharacterEntity? = null
        var userName = ""
        var userPersona = ""
        var mainPrompt = ""
        lateinit var settingsDao: SettingsDao
        lateinit var mctx: MacroContext

        // ---- 世界书设置/条目/激活产物 ----
        var worldInfoGlobalBookIds: List<String> = emptyList()
        var worldInfoPersonaBookId = ""
        var worldInfoCharacterStrategy = 0
        var worldInfoScanDepth = 2
        var worldInfoBudgetTokens = Int.MAX_VALUE
        var worldInfoInHistory = true
        var worldInfoRecursive = false
        var worldInfoMinActivations = 0
        var worldInfoMinActivationsDepthMax = 0
        var worldInfoIncludeNames = true
        var worldInfoUseGroupScoring = false
        var worldBookEntries: List<WorldBookEntryEntity> = emptyList()
        lateinit var constantBudget: WiConstantBudget
        var activatedEntryIds: Set<String> = emptySet()
        var wiBudgetDrops: List<String> = emptyList()
        lateinit var wbPlan: WorldBookPlacement.Plan

        // ---- 记忆/历史/尾部产物 ----
        var memoryContext = MemoryContext()
        var history: List<MessageEntity> = emptyList()
        var recentMessages: List<MessageEntity> = emptyList()
        var historyTruncated = false
        var cardRegexScripts: List<CardRegexEngine.CardRegexScript> = emptyList()
        val depthBlocks = mutableListOf<WiDepthBlock>()
        var authorNote = ""
        var authorNoteDepth = 0
        var authorNoteInHistory = false

        /** 会话/参与者/全局设置/宏上下文：一切装配决策的数据基础 */
        suspend fun loadCore() {
            session = db.sessionDao().get(request.sessionId, request.ownerId, request.characterId)
            // 三态覆盖语义（SETTINGS.md 分层原则）：会话显式值 > 全局当前默认 > 内置兜底，
            // 统一经 ChatSettingsResolver 解析——不再在此硬编码 4096 兜底
            settingsDao = db.settingsDao()
            contextTokenLimit =
                ChatSettingsResolver.contextTokenLimit(
                    session,
                    request.contextTokenLimitOverride
                        ?: ChatSettingsResolver.globalDefaultContextTokens(
                            settingsDao.getValue("default_context_tokens"),
                        ),
                )
            // 全局默认记忆开关已删除：会话显式值优先，未设置即为关
            longTermMemoryEnabled =
                ChatSettingsResolver.longTermMemoryEnabled(session, false)
            val participantCharacterIds = session?.participantCharacterIds() ?: listOf(request.characterId)
            val participantById =
                db.characterDao()
                    .getByIds(participantCharacterIds.distinct())
                    .associateBy { it.id }
            participantCharacters =
                participantCharacterIds
                    .mapNotNull { participantById[it] }
                    .ifEmpty { participantById[request.characterId]?.let { listOf(it) } ?: emptyList() }
            character = participantCharacters.firstOrNull() ?: participantById[request.characterId]
            cardRegexScripts = CardRegexEngine.parse(character?.toDomain()?.data?.extensions)
            // F2.1 宏引擎上下文（契约 B）：用户名与人设统一取全局设置（键常量收敛在 ChatSettingsResolver，
            // 禁止手写字面量）；用户名缺省 "User"，人设缺省空串（不注入该段）
            userName = ChatSettingsResolver.userName(settingsDao.getValue(ChatSettingsResolver.KEY_USER_NAME))
            userPersona = ChatSettingsResolver.userPersona(settingsDao.getValue(ChatSettingsResolver.KEY_USER_PERSONA))
            mainPrompt = ChatSettingsResolver.mainPrompt(settingsDao.getValue(ChatSettingsResolver.KEY_MAIN_PROMPT))
            mctx =
                MacroContext(
                    char = character?.name ?: "",
                    user = userName,
                    description = character?.description ?: "",
                    personality = character?.personality ?: "",
                    scenario = character?.scenario ?: "",
                    persona = userPersona,
                )
        }

        /** 角色卡 system 文本（含多角色参与者块与卡内 systemPrompt） */
        fun appendCharacterCard() {
            val char = character ?: return
            val systemParts = mutableListOf<String>()
            if (participantCharacters.size > 1) {
                systemParts.add(
                    "This is a multi-character chat. Primary speaker: ${char.name}. " +
                        "Other selected characters may participate when appropriate: " +
                        participantCharacters.drop(1).joinToString(", ") { it.name } + ".",
                )
            }
            participantCharacters.forEachIndexed { index, participant ->
                val roleLabel = if (index == 0) "Primary character" else "Participant character"
                val characterParts = mutableListOf<String>()
                characterParts.add("$roleLabel: ${participant.name}")
                // 参与组装的角色文本先过宏引擎渲染（{{char}}/{{user}} 等）
                if (participant.description.isNotBlank()) {
                    characterParts.add(MacroEngine.render(participant.description, mctx))
                }
                if (participant.personality.isNotBlank()) {
                    characterParts.add(
                        "Personality: ${MacroEngine.render(participant.personality, mctx)}",
                    )
                }
                if (participant.scenario.isNotBlank()) {
                    characterParts.add("Scenario: ${MacroEngine.render(participant.scenario, mctx)}")
                }
                if (participant.dataJson.isNotBlank()) {
                    try {
                        val charData = parseCharacterData(participant.dataJson)
                        if (charData != null && charData.systemPrompt.isNotBlank()) {
                            characterParts.add(1, MacroEngine.render(charData.systemPrompt, mctx))
                        }
                    } catch (_: Exception) {
                    }
                }
                systemParts.add(characterParts.joinToString("\n"))
            }
            if (systemParts.isNotEmpty()) {
                messages.addSegment("system", systemParts.joinToString("\n\n"), SOURCE_CHARACTER)
            }
        }

        /** 用户人设段（`{{persona}}` 宏内容） */
        fun appendPersona() {
            // 紧跟角色卡之后，让模型先知己方（AI）再知彼方（用户）。
            // 全局稳定内容 → 落在历史之前，随前缀缓存命中
            personaSegment(userPersona, mctx)?.let { messages.add(it) }
        }

        /** 群聊规范块（classic 模式 groupContext 为 null，不进入） */
        suspend fun appendGroupRules() {
            // 紧跟角色 system 文本追加行为规范块 + 目标角色推动；
            // 规范模板可配置（settings：group_chat_rules_prompt），留空回落内置默认；{user} 占位符按用户名替换
            val groupContext = request.groupContext ?: return
            // 顺序敏感：先宏渲染，再替换 {user} 占位符——{user} 是 {{user}} 的子串，
            // 反过来的话自定义模板里的 {{user}} 会被啃坏（详见 applyUserNamePlaceholder 注释）
            val rules =
                ChatSettingsResolver.applyUserNamePlaceholder(
                    MacroEngine.render(
                        ChatSettingsResolver.groupChatRules(
                            settingsDao.getValue(ChatSettingsResolver.KEY_GROUP_CHAT_RULES),
                        ),
                        mctx,
                    ),
                    mctx.user,
                )
            val targetName = groupContext.targetSpeakerId?.let { groupContext.speakerNames[it] }
            val groupSystem =
                if (targetName != null) {
                    "$rules\n" + GROUP_CHAT_TARGET_TEMPLATE.format(targetName)
                } else {
                    rules
                }
            messages.addSegment("system", groupSystem, SOURCE_GROUP_RULES)
        }

        /** 世界书全局设置：四源开关/扫描深度/预算/落点模式/递归/分组按分等 */
        suspend fun resolveWorldInfoSettings() {
            worldInfoGlobalBookIds =
                ChatSettingsResolver.worldInfoGlobalBookIds(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_GLOBAL_BOOK_IDS),
                )
            worldInfoPersonaBookId =
                ChatSettingsResolver.worldInfoPersonaBookId(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_PERSONA_BOOK_ID),
                )
            worldInfoCharacterStrategy =
                ChatSettingsResolver.worldInfoCharacterStrategy(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_CHARACTER_STRATEGY),
                )
            // 扫描深度（默认 2，对齐酒馆 world_info_depth）与 token 预算（默认 25% 上下文，
            // 对齐酒馆 world_info_budget；0=关闭）。预算先于激活判定完成 constant 条目分配，
            // 剩余额度传入 activate——溢出即停递归（酒馆同款）
            worldInfoScanDepth =
                ChatSettingsResolver.worldInfoScanDepth(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_SCAN_DEPTH),
                )
            worldInfoBudgetTokens =
                ChatSettingsResolver
                    .worldInfoBudgetPercent(
                        settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_PERCENT),
                    ).let { if (it <= 0) Int.MAX_VALUE else contextTokenLimit * it / PERCENT_BASE }
                    // 绝对上限（酒馆 world_info_budget_cap）：>0 时在百分比结果上取 min
                    .let { tokens ->
                        val cap =
                            ChatSettingsResolver.worldInfoBudgetCap(
                                settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_BUDGET_CAP),
                            )
                        if (cap > 0 && tokens > cap) cap else tokens
                    }
            // @D 落点模式：默认插进历史内部（酒馆一致）；=0 时后移到历史之后（省 token：前缀缓存命中更长）
            worldInfoInHistory =
                ChatSettingsResolver.worldInfoInHistory(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_IN_HISTORY),
                )
            // 递归扫描开关：默认关（对齐酒馆 world_info_recursive）
            worldInfoRecursive =
                ChatSettingsResolver.worldInfoRecursive(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_RECURSIVE),
                )
            worldInfoMinActivations =
                ChatSettingsResolver.worldInfoMinActivations(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_MIN_ACTIVATIONS),
                )
            worldInfoMinActivationsDepthMax =
                ChatSettingsResolver.worldInfoMinActivationsDepthMax(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_MIN_ACTIVATIONS_DEPTH_MAX),
                )
            // 扫描文本带「名字: 」前缀（酒馆 world_info_include_names 默认开）：
            // assistant 按 character_id 查说话方名，user 用 {{user}} 值——名字本身也可被关键词命中
            worldInfoIncludeNames =
                ChatSettingsResolver.worldInfoIncludeNames(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_INCLUDE_NAMES),
                )
            worldInfoUseGroupScoring =
                ChatSettingsResolver.worldInfoUseGroupScoring(
                    settingsDao.getValue(ChatSettingsResolver.KEY_WORLD_INFO_USE_GROUP_SCORING),
                )
        }

        /** 世界书条目：四源合并（v34，对齐酒馆四源 + 插入策略）+ constant 预算分配 */
        suspend fun loadWorldBookEntries() {
            // 世界书（v19 起 insertPosition/depth 真正参与装配）。解析须在 LTM 之前完成：
            // afterPrompt 条目要落在"角色定义/群聊规范之后、LTM 之前"的位置。
            // 多来源合并：会话书≈chat lore、persona 绑定书、角色书≈character lore、
            // 全局多选≈global lore；排序策略见 orderLoreEntries
            val worldBookSources =
                buildList {
                    session?.worldBookId?.takeIf { it.isNotBlank() }?.let { add(it to LoreSource.CHAT) }
                    worldInfoPersonaBookId.takeIf { it.isNotBlank() }?.let { add(it to LoreSource.PERSONA) }
                    character?.worldBookId?.takeIf { it.isNotBlank() }?.let { add(it to LoreSource.CHARACTER) }
                    worldInfoGlobalBookIds.forEach { add(it to LoreSource.GLOBAL) }
                }.distinctBy { it.first }
            worldBookEntries =
                orderLoreEntries(
                    worldBookSources
                        .flatMap { (bookId, source) ->
                            db.worldBookDao().getEntriesList(bookId).map { it to source }
                        }.distinctBy { it.first.bookId to it.first.id },
                    worldInfoCharacterStrategy,
                )
            constantBudget = allocateConstantBudget(worldBookEntries, worldInfoBudgetTokens)
        }

        /** 关键词激活判定 + timed effects 持久化 + 预算裁剪清单 + 位置规划 */
        suspend fun runWorldInfoActivation() {
            val scanHistory =
                db.messageDao().getBySession(request.sessionId, request.ownerId).first().map { msg ->
                    if (!worldInfoIncludeNames) {
                        msg.content
                    } else {
                        val speaker =
                            if (msg.role == "assistant") {
                                request.groupContext?.speakerNames?.get(msg.characterId)
                                    ?: character?.name.orEmpty()
                            } else {
                                mctx.user
                            }
                        if (speaker.isBlank()) msg.content else "$speaker: ${msg.content}"
                    }
                }
            // 关键词激活判定（与位置规划正交）：非常驻、启用、概率掷骰通过、命中扫描窗口关键词；
            // probability=1（缺省）跳过掷骰快速路径，constant 常驻条目不参与本判定（恒注入）
            val activation =
                WorldInfoRuntime.activate(
                    request.sessionId,
                    worldBookEntries,
                    WorldInfoRuntime.ScanInput(
                        historyMessages = scanHistory,
                        userMessage = request.userMessage,
                        globalScanDepth = worldInfoScanDepth,
                        mctx = mctx,
                        budgetTokens = constantBudget.remainingTokens,
                        recursive = worldInfoRecursive,
                        minActivations = worldInfoMinActivations,
                        minActivationsDepthMax = worldInfoMinActivationsDepthMax,
                        persistedStateJson = session?.wiStateJson,
                        participantNames = participantCharacters.map { it.name }.toSet(),
                        useGroupScoring = worldInfoUseGroupScoring,
                        participantTags =
                            participantCharacters
                                .flatMap { participant ->
                                    parseCharacterData(participant.dataJson)?.tags ?: emptyList()
                                }.toSet(),
                    ),
                )
            activatedEntryIds = activation.activated
            // timed effects 持久化（对齐酒馆 timedWorldInfo）：状态变化才写库，避免每轮空写
            if (activation.stateSnapshotJson != session?.wiStateJson.orEmpty()) {
                db.sessionDao().updateWiState(
                    request.sessionId,
                    request.ownerId,
                    request.characterId,
                    activation.stateSnapshotJson,
                )
            }
            // WI 预算裁剪清单（「查看提示词」展示）：constant 溢出 + 激活溢出
            wiBudgetDrops =
                (constantBudget.dropped + activation.droppedByBudget).map {
                    "世界书条目「${it.comment.ifBlank { it.id }}」因 WI token 预算被裁剪"
                }
            wbPlan = WorldBookPlacement.plan(worldBookEntries)
        }

        /** 历史之前的全部内容：WI 常驻锚点块、全局主提示词、示例消息及 EM 锚点 */
        fun appendFrontMatter() {
            // 缓存优化：只注入 constant 条目到历史之前，activated 条目稍后注入到历史之后
            worldInfoBlock(
                wbPlan.afterPrompt,
                activatedEntryIds,
                mctx,
                selection = WiSelection.CONSTANT,
                allowedKeys = constantBudget.allowed,
            )?.let {
                messages.addSegment("system", it, "世界书·角色定义后")
            }
            worldInfoBlock(
                wbPlan.beforePrompt,
                activatedEntryIds,
                mctx,
                selection = WiSelection.CONSTANT,
                allowedKeys = constantBudget.allowed,
            )?.let {
                // add(0)：beforePrompt 条目压到整条提示词最前（角色定义之前）
                messages.add(0, Segment(ChatMessage(role = "system", content = it), "世界书·角色定义前"))
            }

            // 全局主提示词（settings：main_prompt）：所有会话共用的基础系统提示。
            // 同样走 add(0) 且**在本处最后执行** → 落在 index 0，得到酒馆顺序
            // [主提示词][世界书·角色定义前][角色卡]…。全局稳定内容 → 落在历史之前，随前缀缓存命中
            mainPromptSegment(mainPrompt, mctx)?.let { messages.add(0, it) }

            // 示例消息（mes_example）：角色定义后的示例对话块；EM 锚点（示例消息前/后）分列其两侧。
            // 群聊取主角色示例；示例内容同样过宏渲染
            // 缓存优化：只注入 constant 条目到历史之前
            val mesExample = character?.mesExample?.trim().orEmpty()
            worldInfoBlock(
                wbPlan.emBefore,
                activatedEntryIds,
                mctx,
                selection = WiSelection.CONSTANT,
                allowedKeys = constantBudget.allowed,
            )?.let {
                messages.addSegment("system", it, "世界书·示例消息前")
            }
            if (mesExample.isNotEmpty()) {
                messages.addSegment("system", "Example dialogue:\n${MacroEngine.render(mesExample, mctx)}", "示例对话")
            }
            worldInfoBlock(
                wbPlan.emAfter,
                activatedEntryIds,
                mctx,
                selection = WiSelection.CONSTANT,
                allowedKeys = constantBudget.allowed,
            )?.let {
                messages.addSegment("system", it, "世界书·示例消息后")
            }
        }

        /** 长期记忆：**先构建、暂不注入**（注入点在历史之后，见 appendMemoryBlocks） */
        suspend fun buildMemory() {
            // 注入点在历史之后、最终用户消息之前（见 @D 块之后）：召回集合由当前用户消息决定，
            // 逐轮都变；若排在历史之前会打断「角色卡 + 历史」这段逐字节稳定的前缀，
            // 使前缀缓存每轮全量未命中——这是"比酒馆更消耗"的结构性原因之一。
            //
            // 预算仍按**含记忆块**的口径计算（appendHistory 传 messages + memoryContext.allMessages()），
            // 保证历史窗口与改动前完全一致，不会因记忆块后移而撑爆上下文
            memoryContext =
                if (longTermMemoryEnabled) {
                    buildMemoryContext(
                        db = db,
                        query =
                            MemoryRecallQuery(
                                request.ownerId,
                                request.characterId,
                                request.sessionId,
                                request.userMessage,
                            ),
                        contextTokenLimit = contextTokenLimit,
                    )
                } else {
                    MemoryContext()
                }
        }

        /** 历史窗口：会话级全量读取 → 排除/过滤 → 预算窗口 → 群聊说话方前缀标注 */
        suspend fun appendHistory() {
            // 历史源会话级读取（跨代理契约 2）：会话是消息的完整归属单元，character_id 仅作
            // 说话方元数据（群聊），历史一律按 (session_id, owner_id) 全量取——群聊 NPC 消息
            // 与主角色消息同窗进入上下文，说话方由 annotateGroupHistory 加前缀区分
            var historySource: List<MessageEntity> =
                db.messageDao().getBySession(request.sessionId, request.ownerId).first()
            if (request.excludeFromMessageId != null) {
                val idx = historySource.indexOfFirst { it.id == request.excludeFromMessageId }
                if (idx >= 0) {
                    historySource = historySource.subList(0, idx)
                }
            }
            recentMessages =
                historySource
                    // 过滤掉刚落库的当前用户消息，避免同一条消息在 prompt 中重复出现
                    .filter { request.currentMessageId == null || it.id != request.currentMessageId }
            history =
                selectHistoryWithinBudget(
                    recentMessages = recentMessages,
                    // 含待注入记忆块的口径：记忆虽然后移到历史之后，但它同样占用上下文预算，
                    // 历史窗口必须据此扣减（与改动前一致，历史选取结果不因本次重排变化）
                    currentMessages = messages.map { it.message } + memoryContext.allMessages(),
                    currentUserMessage = request.userMessage,
                    contextTokenLimit = contextTokenLimit,
                )
            historyTruncated = recentMessages.size > history.size
            val promptHistory = historyForPrompt()
            val groupContext = request.groupContext
            if (groupContext != null) {
                // 群聊模式：历史消息加说话方前缀（AI 消息按 character_id 查名字，用户消息用 {{user}} 值）
                messages.addAll(
                    annotateGroupHistory(
                        history = promptHistory,
                        speakerNames = groupContext.speakerNames,
                        userName = mctx.user,
                    ).map { Segment(it, "历史·群聊") },
                )
            } else {
                promptHistory.forEachIndexed { index, msg: MessageEntity ->
                    // 历史消息不做宏二次渲染（生成时已解析）；思考型模型的历史推理不进上下文（展示层不动）
                    messages.add(
                        Segment(
                            ChatMessage(role = msg.role, content = msg.content),
                            "历史第 ${index + 1}/${history.size} 条",
                            truncationReason = if (historyTruncated) "上下文预算裁剪后保留的最近窗口" else null,
                        ),
                    )
                }
            }
        }

        private fun historyForPrompt(): List<MessageEntity> {
            val promptDepths = IntArray(history.size)
            var nextDepth = 1
            for (index in history.indices.reversed()) {
                promptDepths[index] = nextDepth
                if (history[index].role == "user" || history[index].role == "assistant") nextDepth++
            }
            return history.mapIndexed { index, message ->
                message.copy(
                    content =
                        CardRegexEngine.applyToPrompt(
                            content = stripThinkingBlocks(message.content),
                            role = message.role,
                            scripts = cardRegexScripts,
                            depth = promptDepths[index],
                        ),
                )
            }
        }

        /** 历史之后的第一批收尾块：post_history 指令 + 作者注释（尾部模式）与 AN 常驻锚点 */
        fun appendPostHistoryBlocks() {
            // 角色卡的历史后指令（post_history_instructions）：酒馆里它插在**历史之后**，
            // 作为"最后一句指令"约束生成（走在附加指令之前——附加指令是会话级实时覆盖，仍居末位）。
            // 该字段此前可编辑可存库但从不注入（死字段），本处接通。
            // 取主角色（participantCharacters.first()）的那一份：这是一条全局收尾指令，群聊不做逐角色叠加。
            // 代价说明：它落在历史之后属易变尾部，这部分 token 不命中前缀缓存（同 @D，语义优先）
            val primaryDataJson = participantCharacters.firstOrNull()?.dataJson.orEmpty()
            postHistorySegment(primaryDataJson, character?.name.orEmpty(), mctx)?.let { messages.add(it) }

            // 会话附加指令（author_note）+ AN 锚点：depth>0 时合并为一条深度块插进历史（对齐酒馆
            // AN 的 IN_CHAT 深度插入：AN 顶/底条目并入 AN 文本）；depth=0（默认）保持尾部注入现状
            authorNote = session?.authorNote?.trim().orEmpty()
            authorNoteDepth = session?.authorNoteDepth ?: 0
            authorNoteInHistory = authorNoteDepth > 0
            if (!authorNoteInHistory) {
                if (authorNote.isNotEmpty()) {
                    messages.addSegment("system", "【附加指令】\n${MacroEngine.render(authorNote, mctx)}", "附加指令")
                }

                // AN 锚点（作者注释前/后）：紧贴【附加指令】块上沿/下沿；会话无附加指令时
                // 两块依次落在历史之后，位置语义保持稳定
                // 缓存优化：这些块本就在历史后，但只注入 constant 条目，activated 稍后统一注入
                worldInfoBlock(
                    wbPlan.anBefore,
                    activatedEntryIds,
                    mctx,
                    selection = WiSelection.CONSTANT,
                    allowedKeys = constantBudget.allowed,
                )?.let {
                    messages.addSegment("system", it, "世界书·作者注释前")
                }
                worldInfoBlock(
                    wbPlan.anAfter,
                    activatedEntryIds,
                    mctx,
                    selection = WiSelection.CONSTANT,
                    allowedKeys = constantBudget.allowed,
                )?.let {
                    messages.addSegment("system", it, "世界书·作者注释后")
                }
            }
        }

        /** @D 深度块构建（渲染用 ALL：constant 恒注入 ∪ 非常驻按激活）+ AN 深度化并入 */
        fun buildDepthBlocks() {
            // 落点两模式：worldInfoInHistory=true（默认）插进历史内部（酒馆一致，见 insertWiDepthBlocks）；
            // false 时后移到历史之后统一注入（省 token：保住「角色卡 + 历史」前缀缓存）
            wbPlan.byDepth.forEach { (key, group) ->
                val (depth, role) = key
                worldInfoBlock(
                    group,
                    activatedEntryIds,
                    mctx,
                    selection = WiSelection.ALL,
                    allowedKeys = constantBudget.allowed,
                )?.let { block ->
                    depthBlocks.add(
                        WiDepthBlock(
                            depth = depth,
                            role = role,
                            segment =
                                Segment(
                                    message = ChatMessage(role = role, content = block),
                                    source = "世界书·插入深度 @D$depth",
                                    activationReason =
                                        if (group.any { it.constant }) "constant 常驻" else "关键词/次关键词命中且通过概率",
                                    insertionPosition = "@D$depth/$role",
                                ),
                        ),
                    )
                }
            }

            // 作者注释深度化（v33，对齐酒馆 AN 的 IN_CHAT 深度插入）：depth>0 时 AN 顶/底锚点
            // 并入 AN 文本合成一条深度块，与 @D 块同算法插进历史
            if (authorNoteInHistory) {
                authorNoteDepthBlock(
                    authorNote = authorNote,
                    depth = authorNoteDepth,
                    role =
                        when (session?.authorNoteRole) {
                            1 -> WorldBookPlacement.DEPTH_ROLE_USER
                            2 -> WorldBookPlacement.DEPTH_ROLE_ASSISTANT
                            else -> WorldBookPlacement.DEPTH_ROLE_SYSTEM
                        },
                    anBefore = wbPlan.anBefore,
                    anAfter = wbPlan.anAfter,
                    activatedIds = activatedEntryIds,
                    allowedKeys = constantBudget.allowed,
                    mctx = mctx,
                )?.let { depthBlocks.add(it) }
            }
        }

        /** 长期记忆块注入（历史之后、激活锚点之前、最终用户消息之前） */
        fun appendMemoryBlocks() {
            // 逐轮易变的内容集中尾部；@D 块默认插进历史内部（worldInfoInHistory），省电模式才落尾部
            // 会话历史**未被裁剪**时丢弃"相关历史片段"召回块：整段会话都在提示词里，
            // 召回片段必然与之重复（纯浪费 token 且拉低缓存命中率），此判定无信息损失
            // （与 MemoryContext.messagesFor 同一顺序：先结构化、后召回；标签分开便于预览区分来源）
            memoryContext.structuredBlock?.let { messages.add(Segment(it, "会话记忆·结构化")) }
            if (historyTruncated) {
                memoryContext.recallBlock?.let { messages.add(Segment(it, "会话记忆·召回")) }
            }
        }

        /** 在长期记忆之后统一注入所有激活条目（非 constant），含省 token 模式的 @D 尾注 */
        fun appendActivatedWorldInfo() {
            // 顺序：按原语义锚点排列 beforePrompt → afterPrompt → emBefore → emAfter → anBefore → anAfter；
            // @D 深度块随后按 worldInfoInHistory 分流：默认插进历史内部（酒馆一致），否则追加在此尾部
            // beforePrompt 的激活条目此前在历史前/后两遍都不渲染（第一遍仅 constant），整条丢失——此处补回
            worldInfoBlock(wbPlan.beforePrompt, activatedEntryIds, mctx)?.let {
                messages.addSegment("system", it, "世界书·角色定义前（激活）")
            }
            worldInfoBlock(wbPlan.afterPrompt, activatedEntryIds, mctx)?.let {
                messages.addSegment("system", it, "世界书·角色定义后（激活）")
            }
            worldInfoBlock(wbPlan.emBefore, activatedEntryIds, mctx)?.let {
                messages.addSegment("system", it, "世界书·示例消息前（激活）")
            }
            worldInfoBlock(wbPlan.emAfter, activatedEntryIds, mctx)?.let {
                messages.addSegment("system", it, "世界书·示例消息后（激活）")
            }
            // AN 深度化时（authorNoteInHistory）AN 锚点已并入深度块，此处跳过防双注
            if (!authorNoteInHistory) {
                worldInfoBlock(wbPlan.anBefore, activatedEntryIds, mctx)?.let {
                    messages.addSegment("system", it, "世界书·作者注释前（激活）")
                }
                worldInfoBlock(wbPlan.anAfter, activatedEntryIds, mctx)?.let {
                    messages.addSegment("system", it, "世界书·作者注释后（激活）")
                }
            }
            // @D 深度块尾部注入（仅省 token 模式）：byDepth 已按深度降序排列，
            // 追加后即「大 D 在前、D=1 最贴近最终用户消息」，与酒馆「depth 越小越靠底」一致
            if (!worldInfoInHistory) {
                messages.addAll(depthBlocks.map { it.segment })
            }
        }

        /** 最终用户消息（宏渲染；群聊对齐历史行前缀格式）或群聊推动语 */
        fun appendFinalMessage() {
            // continueGroupChat 场景（groupNudge 非 null）无新用户消息，
            // 改为注入系统级推动语（classic 路径 groupNudge 恒为 null，零改动）
            val groupNudge = request.groupNudge
            if (groupNudge != null) {
                messages.addSegment("system", groupNudge, "群聊推动语")
                return
            }
            val promptUserMessage =
                CardRegexEngine.applyToPrompt(
                    content = request.userMessage,
                    role = "user",
                    scripts = cardRegexScripts,
                    depth = 0,
                )
            val renderedUserMessage = MacroEngine.render(promptUserMessage, mctx)
            // 群聊模式：当前用户消息与历史行格式对齐，加 "{user}: " 前缀（历史每行已带
            // 『名字:』前缀标注发言者，当前消息同样标注可显著提高模型按格式发言的命中率）；
            // classic 模式不添加说话方前缀
            val finalUserMessage =
                if (request.groupContext != null) {
                    groupCurrentUserMessage(userName = mctx.user, renderedUserMessage = renderedUserMessage)
                } else {
                    renderedUserMessage
                }
            messages.addSegment("user", finalUserMessage, "当前消息")
        }

        /** @D 深度插入（默认模式）：在完整列表上按酒馆语义插进历史内部 */
        fun insertDepthBlocksIfNeeded() {
            // 前缀缓存断点 = 最浅 depth 处，断点之上逐字节稳定
            if (worldInfoInHistory) {
                insertWiDepthBlocks(messages, depthBlocks)
            }
        }

        /** 扩展钩子（对标酒馆 generate_interceptor + setExtensionPrompt） */
        suspend fun runInterceptors() {
            // 装配完成后、发送前统一执行生成前拦截器，可增删改 Segment。拦截器的改动同时进入
            // 真实请求与「查看提示词」预览（同一装配路径）；无注册拦截器时零开销、输出与改动前一致
            runPromptInterceptors(
                messages,
                PromptHookContext(request.ownerId, request.characterId, request.sessionId, mctx) { db },
            )
        }

        /** 诊断 + 段标注 + PromptTrace 组装 */
        fun buildTrace(): PromptTrace {
            // 组成诊断（只记条数与估算 token，不记内容）：用于核对上下文窗口是否装得下、
            // 历史是否被裁剪、以及估算值与 API 实发 token 的偏差——排查"实发超配置/缓存命中率低"的直接依据
            val estimatedTotal = messages.sumOf { estimateTokens(it.message.content) }
            val insertedMemory = memoryContext.messagesFor(historyTruncated = historyTruncated)
            // 召回诊断的最终形态：召回块装配成功但历史未裁剪时，注入点会丢弃它——
            // 这是唯一一个装配点之后才确定的 droppedReason，在这里补上（诊断与预览/日志同源）
            val recallDiagnostics =
                memoryContext.diagnostics.let { diag ->
                    if (diag.droppedReason == null && memoryContext.recallBlock != null && !historyTruncated) {
                        diag.copy(droppedReason = RecallDiagnostics.DROP_HISTORY_INTACT)
                    } else {
                        diag
                    }
                }
            android.util.Log.d(
                "PromptDiag",
                "contextLimit=$contextTokenLimit 消息=${messages.size}条 估算=${estimatedTotal}tok " +
                    "历史=${history.size}/${recentMessages.size}条(${history.sumOf { estimateTokens(it.content) }}tok) " +
                    "记忆=${insertedMemory.size}块(${insertedMemory.sumOf { estimateTokens(it.content) }}tok) " +
                    "历史被裁剪=$historyTruncated 召回块丢弃=${memoryContext.recallBlock != null && !historyTruncated} " +
                    "召回诊断=${recallDiagnostics.summaryLine()}",
            )

            // Trace is also consumed by the preview UI.  Every segment gets a stable
            // insertion marker and an explicit conversion note, including ordinary
            // history/current-message segments that do not have World Info metadata.
            val tracedSegments =
                messages.mapIndexed { index, segment ->
                    segment.copy(
                        activationReason = segment.activationReason ?: "source=${segment.source}",
                        insertionPosition = segment.insertionPosition ?: "prompt[$index]",
                        protocolConversionResult =
                            segment.protocolConversionResult
                                ?: "ChatMessage(role=${segment.message.role}, content) kept; " +
                                "provider adapter maps this role/content",
                    )
                }

            return PromptTrace(
                segments = tracedSegments,
                totalEstimatedTokens = estimatedTotal,
                truncationReasons =
                    buildList {
                        if (historyTruncated) {
                            add("历史消息因上下文预算被裁剪：保留最近连续窗口")
                        }
                        addAll(wiBudgetDrops)
                    },
                recallDiagnostics = recallDiagnostics,
                protocolConversionResults =
                    listOf(
                        "OpenAI/custom: ChatMessage role/content -> messages[].role/content",
                        "Anthropic: system messages -> top-level system; user/assistant -> messages[].content",
                        "Gemini: system -> systemInstruction; assistant -> model; others -> user",
                        "Thinking remains on the unified message model and is mapped by provider response parsers",
                    ),
            )
        }
    }

    /**
     * 执行已注册的生成前拦截器（[ExtensionRegistry.promptInterceptors]，按 loadingOrder 升序）。
     *
     * 单拦截器抛异常只记日志并跳过后续该拦截器——扩展故障绝不能中断聊天装配；
     * 协程取消（用户停止生成）必须原样上抛。
     */
    @Suppress("TooGenericExceptionCaught") // 拦截器边界必须兜住任意扩展异常
    private suspend fun runPromptInterceptors(
        messages: MutableList<Segment>,
        context: PromptHookContext,
    ) {
        val interceptors = ExtensionRegistry.promptInterceptors
        if (interceptors.isEmpty()) return
        for (interceptor in interceptors) {
            try {
                interceptor.intercept(messages, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(
                    "PromptBuilder",
                    "Prompt 拦截器执行失败（已跳过）：${e.message}",
                    e,
                )
            }
        }
    }

    /** 角色卡 data_json → CharacterData；空白/损坏静默返回 null（装配绝不让坏卡中断） */
    internal fun parseCharacterData(dataJson: String): com.mistbell.tavern.android.data.api.model.CharacterData? {
        if (dataJson.isBlank()) return null
        return try {
            Json.decodeFromString<com.mistbell.tavern.android.data.api.model.CharacterData>(dataJson)
        } catch (_: Exception) {
            null
        }
    }

    // 群聊模式当前用户消息组装（internal 仅为单元测试开放）：
    // 与历史行格式一致加 "{用户名}: " 前缀（跨代理契约 4，提高模型命中率）
    internal fun groupCurrentUserMessage(
        userName: String,
        renderedUserMessage: String,
    ): String = "$userName: $renderedUserMessage"

    // F2.1 沿用：剔除 <think>…</think> 块并 trim（classic/群聊两条历史路径共用）
    private fun stripThinkingBlocks(content: String): String {
        return THINKING_BLOCK_REGEX.replace(content, "").trim()
    }

    /**
     * 群聊历史组装：为每条历史消息加说话方前缀，转成 ChatMessage。
     * - assistant 消息前加 "{说话角色名}: "（character_id 查 speakerNames，查不到取 id 前 4 位，仍空则"角色"）；
     *   群聊落库时 character_id 已写为实际发言的 NPC id、内容已剥前缀，此处还原说话方供模型区分角色；
     * - 其余角色（user）消息前加 "{用户名}: "；
     * - 同样剔除 <think>…</think> 块。
     * internal 仅为单元测试开放（JVM 可测：MessageEntity/ChatMessage 均为纯 Kotlin 类型）。
     */
    internal fun annotateGroupHistory(
        history: List<MessageEntity>,
        speakerNames: Map<String, String>,
        userName: String,
    ): List<ChatMessage> =
        history.map { msg ->
            val cleanContent = stripThinkingBlocks(msg.content)
            val annotated =
                if (msg.role == "assistant") {
                    "${resolveGroupSpeakerName(msg.characterId, speakerNames)}: $cleanContent"
                } else {
                    "$userName: $cleanContent"
                }
            ChatMessage(role = msg.role, content = annotated)
        }

    // 群聊历史归属兜底：speakerNames 查不到时取 character_id 前 4 位；
    // 不足 4 位的短 id 没有辨识度（如 "x"），用通用名"角色"
    private fun resolveGroupSpeakerName(
        characterId: String,
        speakerNames: Map<String, String>,
    ): String =
        speakerNames[characterId]
            ?: characterId.take(GROUP_SPEAKER_NAME_FALLBACK_LENGTH)
                .takeIf { it.length == GROUP_SPEAKER_NAME_FALLBACK_LENGTH }
            ?: GROUP_SPEAKER_FALLBACK_NAME

    // internal 仅为单元测试开放（ROADMAP M2-2：token 预算截断逻辑需要回归测试）
    internal fun selectHistoryWithinBudget(
        recentMessages: List<MessageEntity>,
        currentMessages: List<ChatMessage>,
        currentUserMessage: String,
        contextTokenLimit: Int,
    ): List<MessageEntity> {
        val reservedForReply = 768
        val fixedTokens = currentMessages.sumOf { estimateTokens(it.content) }
        val currentUserTokens = estimateTokens(currentUserMessage)
        val historyBudget =
            (contextTokenLimit - fixedTokens - currentUserTokens - reservedForReply)
                .coerceAtLeast(256)

        val selected = ArrayDeque<MessageEntity>()
        var usedTokens = 0

        // 从最新往回连续选取，预算耗尽即停止，保证历史片段连续；
        // 最新一条无条件纳入（与历史实现一致：预算极小时也带上最近一轮的上下文）
        for ((i, message) in recentMessages.asReversed().withIndex()) {
            val messageTokens = estimateTokens(message.content) + 4
            if (i > 0 && usedTokens + messageTokens > historyBudget) {
                break
            }
            selected.addFirst(message)
            usedTokens += messageTokens
        }

        return selected.toList()
    }

    /**
     * 酒馆原版 @D 深度插入（script.js doChatInject 的等价物）：在**完整**消息列表
     * （含最终用户消息/群聊推动语）上，按深度升序、同深度 role 序 system→user→assistant 逐组插入。
     *
     * 深度计数对象 = user/assistant 消息（真实历史 + 最终用户消息）+ 已插入的 @D 块
     * （对齐酒馆 totalInsertedMessages 补偿）；夹在尾部或中间的 system 块（记忆/作者注释/
     * 世界书锚点）不占位。depth 超出可计数总数时钳到最旧一条可计数消息之前。
     * 最终呈现序：大 D 在前、D=1 最贴近生成点；同深度 assistant→user→system 自上而下。
     *
     * internal 仅为单元测试开放：落点算法对尾部系统块免疫性/插入补偿需要回归测试。
     */
    internal fun insertWiDepthBlocks(
        messages: MutableList<Segment>,
        blocks: List<WiDepthBlock>,
    ) {
        if (blocks.isEmpty()) return
        val inserted = mutableSetOf<Segment>()

        // 计数对象：真实聊天消息（user/assistant）或已插入的 @D 块（任意 role）
        fun countable(index: Int): Boolean {
            val role = messages[index].message.role
            return role == "user" || role == "assistant" || messages[index] in inserted
        }
        blocks
            .sortedWith(compareBy<WiDepthBlock> { it.depth }.thenBy { depthInsertRoleRank(it.role) })
            .forEach { block ->
                val target = block.depth + inserted.size
                var index = messages.size
                var remaining = target
                var oldestCountable = messages.size
                while (index > 0 && remaining > 0) {
                    index--
                    if (countable(index)) {
                        remaining--
                        oldestCountable = index
                    }
                }
                // remaining > 0 = 深度超出可计数总数：钳到最旧可计数消息之前（酒馆 min(idx, len) 等价）
                val insertAt = if (remaining == 0) index else oldestCountable
                messages.add(insertAt, block.segment)
                inserted += block.segment
            }
    }

    /** @D 深度块（待插入历史内部的一条消息及其深度/角色） */
    internal data class WiDepthBlock(
        val depth: Int,
        val role: String,
        val segment: Segment,
    )

    /** 世界书来源（多来源合并排序用；对齐酒馆 chat/persona/character/global 四源） */
    internal enum class LoreSource { CHAT, PERSONA, CHARACTER, GLOBAL }

    /**
     * 多来源合并的条目排序（对齐酒馆 getSortedEntries）：chat → persona 固定在前；
     * character/global 按策略——evenly 混排、character_first 先角色书、global_first 先全局书。
     * 同来源/同级按 order 升序稳定排列（渲染序即注入序）。internal 仅为单元测试开放。
     */
    internal fun orderLoreEntries(
        entries: List<Pair<WorldBookEntryEntity, LoreSource>>,
        strategy: Int,
    ): List<WorldBookEntryEntity> {
        fun rank(source: LoreSource): Int =
            when (strategy) {
                ChatSettingsResolver.STRATEGY_GLOBAL_FIRST ->
                    when (source) {
                        LoreSource.CHAT -> 0
                        LoreSource.PERSONA -> 1
                        LoreSource.GLOBAL -> 2
                        LoreSource.CHARACTER -> 3
                    }
                ChatSettingsResolver.STRATEGY_EVENLY ->
                    when (source) {
                        LoreSource.CHAT -> 0
                        LoreSource.PERSONA -> 1
                        else -> 2 // character/global 混排
                    }
                else -> // character_first（酒馆默认）
                    when (source) {
                        LoreSource.CHAT -> 0
                        LoreSource.PERSONA -> 1
                        LoreSource.CHARACTER -> 2
                        LoreSource.GLOBAL -> 3
                    }
            }
        return entries
            .sortedWith(compareBy<Pair<WorldBookEntryEntity, LoreSource>> { rank(it.second) }.thenBy { it.first.order })
            .map { it.first }
    }

    /**
     * 作者注释深度块（对齐酒馆 AN 的 IN_CHAT 深度插入）：AN 顶锚点内容 + 附加指令 + AN 底锚点
     * 合成一条；三者全空返回 null（不产生空消息）。internal 仅为单元测试开放。
     */
    @Suppress("LongParameterList") // 参数即深度块语义平铺
    internal fun authorNoteDepthBlock(
        authorNote: String,
        depth: Int,
        role: String,
        anBefore: List<WorldBookEntryEntity>,
        anAfter: List<WorldBookEntryEntity>,
        activatedIds: Set<String>,
        allowedKeys: Set<String>?,
        mctx: MacroContext,
    ): WiDepthBlock? {
        val combined =
            buildString {
                worldInfoBlock(anBefore, activatedIds, mctx, WiSelection.ALL, allowedKeys)?.let { appendLine(it) }
                if (authorNote.isNotEmpty()) append("【附加指令】\n${MacroEngine.render(authorNote, mctx)}")
                worldInfoBlock(anAfter, activatedIds, mctx, WiSelection.ALL, allowedKeys)?.let {
                    appendLine()
                    append(it)
                }
            }.trim()
        if (combined.isBlank()) return null
        return WiDepthBlock(
            depth = depth,
            role = role,
            segment =
                Segment(
                    message = ChatMessage(role = role, content = combined),
                    source = "附加指令",
                    insertionPosition = "@D$depth/$role",
                ),
        )
    }

    /** 同深度插入顺序（酒馆 roles 循环序 system→user→assistant；最终呈现因逐次后插而反转） */
    private fun depthInsertRoleRank(role: String): Int =
        when (role) {
            WorldBookPlacement.DEPTH_ROLE_SYSTEM -> 0
            WorldBookPlacement.DEPTH_ROLE_USER -> 1
            else -> 2
        }

    /**
     * 长期记忆上下文 → 待注入的 system 消息列表（顺序：结构化召回 → 向量/词法召回）。
     *
     * **注入位置约定**：调用方必须在历史之后、最终用户消息之前插入。召回集合由当前用户消息决定、
     * 逐轮都变；若排在历史之前会打断「角色卡 + 历史」这段逐字节稳定的前缀，
     * 使前缀缓存每轮全量未命中（这就是"比酒馆更消耗"的结构性原因之一）。
     *
     * 检索失败不阻塞对话（原样保留：异常吞掉、返回已构建的部分）。
     *
     * 长期记忆两块内容（**分开持有**，便于按会话是否被裁剪决定是否注入召回块）。
     *
     * - [structuredBlock]：结构化召回的事实（"## Known Information"）——绝不与历史重复，始终注入
     * - [recallBlock]：向量/词法召回的**本会话历史片段**——会话未被裁剪时整段历史都在提示词里，
     *   这些片段必然重复，注入只是白烧 token 并拉低前缀缓存命中率
     */
    internal data class MemoryContext(
        val structuredBlock: ChatMessage? = null,
        val recallBlock: ChatMessage? = null,
        /** 本轮召回的诊断（只读投影；装配决策不受影响，见 RecallDiagnostics） */
        val diagnostics: RecallDiagnostics = RecallDiagnostics(),
    ) {
        /**
         * 组装用。
         *
         * 召回范围已永久固定为当前会话（见 [RecallWindow]）：历史未裁剪时召回内容必然已在
         * 历史里，整段丢弃不丢任何信息；历史被裁剪时才注入召回块补回更早的本会话内容。
         */
        fun messagesFor(historyTruncated: Boolean): List<ChatMessage> =
            listOfNotNull(structuredBlock, recallBlock.takeIf { historyTruncated })

        /** 预算用：一律按含召回块计算（偏保守，不会撑爆上下文） */
        fun allMessages(): List<ChatMessage> = listOfNotNull(structuredBlock, recallBlock)
    }

    /** 一次记忆召回的查询维度（收拢成单一入参，避免装配函数的参数表随维度膨胀） */
    private data class MemoryRecallQuery(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val userMessage: String,
    )

    private suspend fun buildMemoryContext(
        db: AppDatabase,
        query: MemoryRecallQuery,
        contextTokenLimit: Int,
    ): MemoryContext {
        // 闸门失败 = 记忆链未就绪：整条记忆上下文降级为空（结构化 + 召回一起不注入），
        // 对话可以继续，只是这一轮没有长期记忆——不召回优于召错
        if (!awaitGateForRecall()) {
            return MemoryContext(
                diagnostics = RecallDiagnostics(droppedReason = RecallDiagnostics.DROP_GATE_CLOSED),
            )
        }

        // 召回范围：结构化 / 向量 / 词法三条通道共用同一窗口（见 [RecallWindow]）。
        // 长期记忆已永久固定为「owner + 当前会话」：档位设置（MemoryScope）归一化为 SESSION，
        // 窗口不再按任何设置扩大会话集合；缺失会话 id 时三条通道一致拒绝召回。
        val window =
            RecallWindow.resolve(
                db = db,
                ownerId = query.ownerId,
                characterId = query.characterId,
                currentSessionId = query.sessionId,
                scope = MemoryScope.SESSION,
            )
        val (structuredBlock, injectedContents) = structuredMemoryBlock(db, query, window, contextTokenLimit)
        val recall = recallBlock(db, query, window, injectedContents)
        return MemoryContext(
            structuredBlock = structuredBlock,
            recallBlock = recall.block,
            diagnostics =
                recall.diagnostics.copy(
                    structuredCount = injectedContents.size,
                    structuredBudgetTokens = memoryBlockBudget(contextTokenLimit),
                ),
        )
    }

    /**
     * 结构化记忆块：按召回窗口取候选 → 相关性选择 → 按 token 预算渲染。
     *
     * @return (注入块, 实际注入的记忆正文)；正文供向量召回去重——被预算裁掉的不算"已覆盖"
     */
    private suspend fun structuredMemoryBlock(
        db: AppDatabase,
        query: MemoryRecallQuery,
        window: RecallWindow,
        contextTokenLimit: Int,
    ): Pair<ChatMessage?, List<String>> {
        val memories = recallCandidates(db, window).map { it.toDomain() }
        val selected = selectRelevantMemories(memories, query.userMessage)
        // 记忆块按 token 预算裁剪：选择器已按重要度/相关性排好序，被裁掉的是最不重要的尾巴
        val (text, injected) =
            formatStructuredMemoryContext(selected, memoryBlockBudget(contextTokenLimit))
        if (injected.isEmpty()) return null to emptyList()

        val accessedAt = Instant.now().toString()
        injected.map { it.id }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.let {
            db.structuredMemoryDao().incrementAccessCount(it, accessedAt)
        }
        return ChatMessage(role = "system", content = "## Known Information\n$text") to
            injected.map { it.content }.filter { it.isNotBlank() }
    }

    /**
     * 按召回窗口取候选：**owner + 当前会话**（档位语义见 [RecallWindow] 文件头的产品决策）。
     *
     * 当前会话 id 缺失（blank）→ 空列表：宁可不召回，绝不退化成 owner 级（跨会话串线）。
     * 不按角色收窄：群聊里 NPC 与主角色的记忆同属这一次对话，会话边界已覆盖角色隔离。
     */
    private suspend fun recallCandidates(
        db: AppDatabase,
        window: RecallWindow,
    ): List<StructuredMemoryEntity> {
        // 防御性收口（不信任调用方装配的窗口）：ownerId / currentSessionId 为空 → 拒绝召回；
        // 记忆链闸门（recallCandidates 也是结构化召回的独立入口，不能只吃 buildMemoryContext 的闸）：
        // 未通过 → 空候选，与 blank 收口同一降级语义。短路求值保证身份合法才走闸门（与原行为一致）。
        val gatePassed =
            window.ownerId.isNotBlank() && window.currentSessionId.isNotBlank() &&
                awaitGateForRecall()
        if (!gatePassed) return emptyList()

        val dao = db.structuredMemoryDao()
        val currentSessionId = window.currentSessionId

        // DAO 内已按 importance DESC, created_at DESC 排序并 LIMIT——单一查询无需再合并截断
        return dao.getPromptCandidatesInSessions(
            window.ownerId,
            listOf(currentSessionId),
            MEMORY_CANDIDATE_LIMIT,
        )
    }

    /** 一次召回块装配的产物：注入块 + 诊断（诊断与块同源，见 RecallDiagnostics） */
    private data class RecallOutcome(
        val block: ChatMessage?,
        val diagnostics: RecallDiagnostics,
    )

    /** 语义召回块：有真实 embedding 服务（API 或本地 ONNX，F3）→ 向量+词法混合召回；否则词法回退（F3-FTS） */
    @Suppress("TooGenericExceptionCaught") // 检索失败不应阻塞对话
    private suspend fun recallBlock(
        db: AppDatabase,
        query: MemoryRecallQuery,
        window: RecallWindow,
        injectedContents: List<String>,
    ): RecallOutcome =
        try {
            val vectorMemoryService = vectorMemoryServiceProvider()
            if (vectorMemoryService.available) {
                hybridRecallBlock(db, query, window, injectedContents, vectorMemoryService)
            } else {
                lexicalRecallBlock(query, window)
            }
        } catch (e: Exception) {
            android.util.Log.e("PromptBuilder", "Memory search failed: ${e.message}", e)
            RecallOutcome(
                block = null,
                diagnostics =
                    RecallDiagnostics(
                        vectorAvailable = vectorMemoryServiceProvider().available,
                        droppedReason = RecallDiagnostics.DROP_RECALL_FAILED,
                    ),
            )
        }

    /**
     * 混合召回块：向量路与词法路**同时**检索，由 [RecallRanker] 融合排序。
     *
     * 向量路擅长语义相近但措辞不同的命中，词法路擅长专有名词/稀有词的精确命中；
     * 旧实现两路二选一，任一路的盲区都没有兜底。词法服务与无 embedding 回退路径
     * 共用同一个 [LexicalMemoryService]（自带当前会话近期窗口排除，不重复召回上下文里
     * 已有的内容），窗口口径与向量路完全一致（见 [RecallWindow]）。
     */
    private suspend fun hybridRecallBlock(
        db: AppDatabase,
        query: MemoryRecallQuery,
        window: RecallWindow,
        injectedContents: List<String>,
        vectorMemoryService: VectorMemoryService,
    ): RecallOutcome {
        // S2 召回设置：top-k 与相似度阈值全局可配（读取时解析，存储为纯数值）
        val topK = ChatSettingsResolver.memoryRecallTopK(db.settingsDao().getValue("memory_recall_top_k"))
        val threshold =
            ChatSettingsResolver.memorySimilarityThreshold(
                db.settingsDao().getValue("memory_similarity_threshold"),
            )
        val outcome =
            vectorMemoryService.searchRelevantMemories(
                query = query.userMessage,
                // 作用域整体交给 RecallWindow：本方法不再自己解释档位（角色是否收窄、
                // 会话是否收窄都在窗口里），三通道口径才不会各自漂移
                window = window,
                topK = topK * HYBRID_VECTOR_CANDIDATE_MULTIPLIER,
            )
        val lexicalStartedAt = System.nanoTime()
        val lexicalItems =
            LexicalMemoryService(TavernApplication.instance)
                .searchRelevantHistory(window = window, query = query.userMessage)
        val lexicalLatencyMs = (System.nanoTime() - lexicalStartedAt) / 1_000_000

        // 阈值不再在这里预先过滤：RecallRanker 需要看到低于阈值的向量候选——
        // 其中有词法 corroborate 的会被保留（混合召回的核心收益），其余才丢弃
        val ranked =
            RecallRanker.fuse(
                vectorHits = outcome.results.map { it.toRecallCandidate() },
                lexicalHits = lexicalItems.map { it.toRecallCandidate() },
                topK = topK,
                similarityThreshold = threshold,
                nowEpochMs = System.currentTimeMillis(),
            )

        val diagnostics =
            RecallDiagnostics(
                vectorAvailable = true,
                vectorCandidates = outcome.results.size,
                lexicalCandidates = lexicalItems.size,
                fusedCount = ranked.size,
                vectorCacheHit = outcome.cacheHit,
                vectorLatencyMs = outcome.latencyMs,
                lexicalLatencyMs = lexicalLatencyMs,
            )

        // sessionScoped=false 只可能是"窗口缺会话 id、向量层已拒绝召回"——此时词法路同样
        // 拒绝召回（同一窗口口径），ranked 必为空，无需额外提示
        //
        // 去重：重要度≥7 的记忆会同步一份 summary 进向量库，与结构化召回内容重复——
        // 内容已被本提示词覆盖的召回行直接丢弃，同一事实不再烧两次 token
        val render =
            if (ranked.isEmpty()) {
                null
            } else {
                buildRecallContextForPrompt(ranked, injectedContents).takeIf { it.text.isNotBlank() }
            }
        if (render == null) {
            val reason =
                when {
                    ranked.isEmpty() && !outcome.sessionScoped -> RecallDiagnostics.DROP_NO_SESSION
                    ranked.isEmpty() -> RecallDiagnostics.DROP_NO_HITS
                    else -> RecallDiagnostics.DROP_ALL_DUPLICATED
                }
            return RecallOutcome(null, diagnostics.copy(droppedReason = reason))
        }
        return RecallOutcome(
            block = ChatMessage(role = "system", content = render.text),
            diagnostics = diagnostics.copy(injectedRecallLines = render.injectedCount),
        )
    }

    /** 向量检索结果 → 融合候选：list 顺序即名次（store 按分数降序返回） */
    private fun VectorStore.SearchResult.toRecallCandidate() =
        RecallRanker.RecallCandidate(
            messageId = metadata["message_id"] as? String ?: id,
            content = content,
            vectorScore = score,
            createdAtEpochMs = (metadata["created_at"] as? String)?.toLongOrNull(),
            isSummary = metadata["content_type"] == VectorStore.SUMMARY_CONTENT_TYPE,
        )

    /** 词法检索结果 → 融合候选：list 顺序即名次（DAO 按最新优先返回），无分数语义 */
    private fun MessageEntity.toRecallCandidate() =
        RecallRanker.RecallCandidate(
            messageId = id,
            content = content,
            createdAtEpochMs = parseIsoEpochMs(createdAt),
        )

    /** createdAt 存的是 ISO-8601 字符串；解析失败返回 null（时间因子取中性值，不参与衰减） */
    private fun parseIsoEpochMs(iso: String): Long? =
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }

    private suspend fun lexicalRecallBlock(
        query: MemoryRecallQuery,
        window: RecallWindow,
    ): RecallOutcome {
        // 无 embedding API：诚实的关键词词法召回（OMate 式历史全文检索思路）
        val lexical = LexicalMemoryService(TavernApplication.instance)
        val startedAt = System.nanoTime()
        val items =
            lexical.searchRelevantHistory(
                window = window,
                query = query.userMessage,
            )
        val lexicalLatencyMs = (System.nanoTime() - startedAt) / 1_000_000
        val diagnostics =
            RecallDiagnostics(
                vectorAvailable = false,
                lexicalOnly = true,
                lexicalCandidates = items.size,
                fusedCount = items.size,
                lexicalLatencyMs = lexicalLatencyMs,
            )
        val text = lexical.formatHistory(items)
        if (text.isBlank()) {
            return RecallOutcome(
                null,
                diagnostics.copy(droppedReason = RecallDiagnostics.DROP_NO_HITS),
            )
        }
        return RecallOutcome(
            block = ChatMessage(role = "system", content = text),
            diagnostics = diagnostics.copy(injectedRecallLines = items.size),
        )
    }

    private fun selectRelevantMemories(
        memories: List<StructuredMemory>,
        userMessage: String,
    ): List<StructuredMemory> {
        if (memories.isEmpty()) return emptyList()

        val result = linkedMapOf<Long, StructuredMemory>()

        memories
            .filter { it.importance >= 8 }
            .sortedWith(memoryComparator())
            .take(5)
            .forEach { result[it.stableKey()] = it }

        memories
            .filter { it.stableKey() !in result }
            .filter { it.importance >= 6 && it.memoryType.lowercase() in profileMemoryTypes }
            .sortedWith(memoryComparator())
            .take(3)
            .forEach { result[it.stableKey()] = it }

        val keywordTokens = extractQueryTokens(userMessage)
        if (keywordTokens.isNotEmpty()) {
            memories
                .filter { it.stableKey() !in result }
                .mapNotNull { memory ->
                    val score = keywordMatchScore(memory, keywordTokens)
                    if (score > 0) memory to score else null
                }
                .sortedWith(
                    compareByDescending<Pair<StructuredMemory, Int>> { it.second }
                        .thenByDescending { it.first.importance }
                        .thenByDescending { it.first.updatedAt },
                )
                .take(3)
                .forEach { result[it.first.stableKey()] = it.first }
        }

        return result.values
            .sortedWith(memoryComparator())
            .take(10)
    }

    /** 记忆块 token 预算：占上下文长度的比例，钳制在上下限内（取值理由见常量注释） */
    internal fun memoryBlockBudget(contextTokenLimit: Int): Int =
        (contextTokenLimit * MEMORY_BLOCK_BUDGET_RATIO).toInt()
            .coerceIn(MEMORY_BLOCK_MIN_TOKENS, MEMORY_BLOCK_MAX_TOKENS)

    /**
     * 渲染记忆块，返回 (文本, **实际注入**的记忆)。
     *
     * [budgetTokens] 是硬约束：逐行累加 token，超预算即止。选择器已按重要度/相关性排好序，
     * "先到先得"等价于"优先保留更重要的"——没有任何排序能保证不超长，预算才能。
     *
     * 返回实际注入的集合，而不是把入参原样带出去：访问计数与向量去重都只该认真正进了提示词的
     * 那些记忆，被预算裁掉的不算"被访问"，也不算"内容已被覆盖"。
     *
     * internal 仅为单元测试开放（与 [insertWiDepthBlocks] / [selectHistoryWithinBudget] 同一先例）。
     */
    internal fun formatStructuredMemoryContext(
        memories: List<StructuredMemory>,
        budgetTokens: Int,
    ): Pair<String, List<StructuredMemory>> {
        val grouped = groupMemories(memories)

        // 第一步：在**全局排序**的候选上分配预算。选择器已按重要度/相关性排好序，先到先得
        // 就等于"优先保住更重要的"。
        //
        // 这一步必须先于分组：此前是边按固定分组顺序（用户信息 → 关系 → 重要事件 …）渲染边扣预算，
        // 于是低重要度的"用户信息"能先把预算占满，把更高重要度的"重要事件"整组挡在门外——
        // 注释里"裁掉最不重要的尾巴"对不上实际行为。
        //
        // 放不下的**跳过**而不是就此停止：列表已按重要度排序，跳过一条大的不会漏掉更重要的
        // （后面的只会同等或更不重要），但能让预算装下更多条。若在这里 break，一条超长的
        // 低优先级记忆就能把后面所有更重要的条目一起饿死。
        //
        // 分组标题的开销先整体预留（宁多勿少：部分分组可能因保留项为空而不出现）。
        val headerTokens = grouped.sumOf { estimateTokens("${it.first}：") } + estimateTokens(FACTS_HEADER)
        val contentBudget = (budgetTokens - headerTokens).coerceAtLeast(0)
        var usedTokens = 0
        val injected = mutableListOf<StructuredMemory>()
        for (memory in memories) {
            val cost = estimateTokens(memoryLine(memory))
            if (usedTokens + cost > contentBudget) continue
            usedTokens += cost
            injected.add(memory)
        }

        // 第二步：只把保留项按分组渲染出来（此时不再扣预算，总量已在预算内）
        val keptKeys = injected.map { it.stableKey() }.toSet()
        val lines = mutableListOf<String>()
        val groupedKeys = mutableSetOf<Long>()
        for ((label, items) in grouped) {
            val shown = items.filter { it.stableKey() in keptKeys }
            if (shown.isEmpty()) continue
            lines.add("$label：")
            shown.forEach { memory ->
                groupedKeys.add(memory.stableKey())
                lines.add(memoryLine(memory))
            }
            lines.add("")
        }

        val facts = injected.filter { it.stableKey() !in groupedKeys }
        if (facts.isNotEmpty()) {
            lines.add(FACTS_HEADER)
            facts.forEach { lines.add(memoryLine(it)) }
        }

        return lines.joinToString("\n").trim() to injected
    }

    /** 按分组归拢记忆，空组不出现（顺序即展示顺序） */
    private fun groupMemories(memories: List<StructuredMemory>): List<Pair<String, List<StructuredMemory>>> =
        MEMORY_GROUPS.mapNotNull { (label, types) ->
            memories
                .filter { it.memoryType.lowercase() in types }
                .takeIf { it.isNotEmpty() }
                ?.let { label to it }
        }

    /** 记忆渲染成行；存量超长记忆（长度上限引入前落库的）在此再截一次 */
    private fun memoryLine(memory: StructuredMemory): String {
        return "  - ${memory.content.truncateMemoryContent()}（重要度：${memory.importance}/10）"
    }

    private fun keywordMatchScore(
        memory: StructuredMemory,
        queryTokens: Set<String>,
    ): Int {
        val haystack =
            buildString {
                append(memory.title.orEmpty()).append(' ')
                append(memory.content).append(' ')
                append(memory.tags.joinToString(" ")).append(' ')
                append(memory.keywords.joinToString(" "))
            }.lowercase()

        var score = 0
        queryTokens.forEach { token ->
            if (token.length >= 2 && haystack.contains(token)) score += 1
        }

        memory.tags.forEach { tag ->
            val normalized = tag.lowercase().trim()
            if (normalized.length >= 2 && queryTokens.any { it.contains(normalized) || normalized.contains(it) }) {
                score += 2
            }
        }
        memory.keywords.forEach { keyword ->
            val normalized = keyword.lowercase().trim()
            if (normalized.length >= 2 && queryTokens.any { it.contains(normalized) || normalized.contains(it) }) {
                score += 2
            }
        }

        return score
    }

    private fun extractQueryTokens(text: String): Set<String> {
        val tokens = linkedSetOf<String>()
        Regex("""[\u4e00-\u9fff]{2,}|[a-z0-9_]{3,}""").findAll(text.lowercase()).forEach { match ->
            val value = match.value.trim()
            if (value.length in 2..24) tokens.add(value)
            if (value.any { it.isCjk() } && value.length > 4) {
                value.windowed(2).forEach { tokens.add(it) }
                value.windowed(3).forEach { tokens.add(it) }
            }
        }
        return tokens
    }

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var ascii = 0
        var nonAscii = 0
        text.forEach { char ->
            if (char.code <= 127) ascii++ else nonAscii++
        }
        return maxOf(1, kotlin.math.ceil(ascii / ASCII_CHARS_PER_TOKEN + nonAscii / CJK_CHARS_PER_TOKEN).toInt())
    }

    private fun memoryComparator(): Comparator<StructuredMemory> =
        compareByDescending<StructuredMemory> { it.importance }
            .thenByDescending { it.accessCount }
            .thenByDescending { it.updatedAt }

    private fun StructuredMemory.stableKey(): Long = if (id > 0) id else content.hashCode().toLong()

    private fun Char.isCjk(): Boolean = this in '\u4e00'..'\u9fff'

    // profile 类的定义收敛到 MemoryType.PROFILE_TYPES：写侧（抽取落库的作用域）与这里
    // 必须用同一份——两处各写一份就会出现"选择器优待的类型其实召回不到"
    private val profileMemoryTypes: Set<String> = MemoryType.PROFILE_TYPES

    /**
     * 构建混合召回上下文（用于 Prompt 注入）。
     *
     * 相似度阈值已在 [RecallRanker.fuse] 里统一应用，这里**不再设分数下限**——旧实现此处
     * 硬编码 `score > 0.5`，把用户可配阈值（默认 0.35）实际钳到 0.5 才生效。
     *
     * 行尾标注诚实区分来源：向量命中带 `(similarity: N%)`；纯词法命中没有分数语义，
     * 标 `(keyword match)` 而不是伪造一个相似度数字。
     *
     * @param alreadyInjected 本提示词中已注入的结构化记忆正文——向量库里那些「重要度≥7 记忆的 summary 副本」
     *                        内容即为其正文，若再注入就是同一事实烧两遍 token，故按内容覆盖关系丢弃
     *
     * internal 仅为单元测试开放：重复注入的过滤规则需要回归测试
     */
    internal fun buildRecallContextForPrompt(
        ranked: List<RecallRanker.RankedRecall>,
        alreadyInjected: List<String>,
    ): RecallRender {
        val relevantResults =
            ranked.filterNot { result -> isDuplicateOfInjected(result.candidate.content, alreadyInjected) }
        if (relevantResults.isEmpty()) return RecallRender("", 0)

        val text =
            buildString {
                appendLine("## Relevant Past Conversations")
                relevantResults.forEachIndexed { index, result ->
                    val annotation =
                        result.candidate.vectorScore?.let { "similarity: ${(it * 100).toInt()}%" }
                            ?: "keyword match"
                    // 召回行是整条历史消息，逐条截断——条数有上限（topK）、长度没有
                    appendLine("${index + 1}. ${result.candidate.content.truncateMemoryContent()} ($annotation)")
                }
            }
        return RecallRender(text, relevantResults.size)
    }

    /** 召回块渲染产物：注入文本 + 实际注入行数（诊断面板的"去重后条数"来源） */
    internal data class RecallRender(
        val text: String,
        val injectedCount: Int,
    )

    /**
     * 向量召回行是否已被注入的记忆正文覆盖（双向包含，短文本不参与判定以免误伤）。
     * summary 副本的形态是 "标题: 正文 [类型]"，故「向量行包含记忆正文」即为重复。
     */
    private fun isDuplicateOfInjected(
        content: String,
        alreadyInjected: List<String>,
    ): Boolean {
        if (content.length < MIN_DUPLICATE_LENGTH) return false
        return alreadyInjected.any { injected ->
            injected.length >= MIN_DUPLICATE_LENGTH &&
                (content.contains(injected) || injected.contains(content))
        }
    }

    /** 世界书渲染过滤：CONSTANT=仅常驻（历史前注入）；ACTIVATED=仅非常驻且已激活（历史后注入）；ALL=常驻 ∪ 已激活（@D 用） */
    private enum class WiSelection { CONSTANT, ACTIVATED, ALL }

    /** constant 条目的预算分配结果（[allowed] 之外的 constant 因预算被裁，不再注入） */
    internal data class WiConstantBudget(
        val allowed: Set<String>,
        val dropped: List<WorldBookEntryEntity>,
        val remainingTokens: Int,
    )

    /**
     * WI token 预算的第一刀：constant 常驻条目按 order 升序先装（优先级最高），
     * 装不下即溢出——溢出后**停止**（不再装更小的，与酒馆溢出即停一致），
     * 剩余额度留给激活条目。估算口径与历史预算同为 [estimateTokens]，不加第二套估算器。
     * internal 仅为单元测试开放。
     */
    internal fun allocateConstantBudget(
        entries: List<WorldBookEntryEntity>,
        budgetTokens: Int,
    ): WiConstantBudget {
        val constants = entries.filter { it.constant && !it.disable }
        if (budgetTokens == Int.MAX_VALUE) {
            return WiConstantBudget(constants.map { "${it.bookId}:${it.id}" }.toSet(), emptyList(), Int.MAX_VALUE)
        }
        val allowed = mutableSetOf<String>()
        val dropped = mutableListOf<WorldBookEntryEntity>()
        var remaining = budgetTokens
        var overflowed = false
        constants.sortedBy { it.order }.forEach { entry ->
            // ignoreBudget（对齐酒馆）：恒放行且不占额度
            if (entry.ignoreBudget) {
                allowed += "${entry.bookId}:${entry.id}"
                return@forEach
            }
            if (overflowed) {
                dropped += entry
                return@forEach
            }
            val cost = estimateTokens(entry.content)
            if (cost > remaining) {
                overflowed = true
                dropped += entry
            } else {
                remaining -= cost
                allowed += "${entry.bookId}:${entry.id}"
            }
        }
        return WiConstantBudget(allowed, dropped, remaining)
    }

    /**
     * 组内条目渲染为一条 system 消息文本（World Info 常驻段 + Activated World Info 激活段）；
     * 组内无有效内容（空组或非常驻条目均未激活）返回 null，不产生空消息
     *
     * @param selection CONSTANT=仅渲染 constant 条目（用于历史前注入，保持前缀稳定）；
     *                  ACTIVATED=仅渲染激活条目（用于历史后注入，易变部分）；
     *                  ALL=constant 恒注入 ∪ 非常驻按激活（@D 深度块用，酒馆 constant 恒激活语义）
     * @param allowedKeys constant 条目的预算白名单（[allocateConstantBudget] 产出）；null=不过滤
     */
    private fun worldInfoBlock(
        group: List<WorldBookEntryEntity>,
        activatedIds: Set<String>,
        mctx: MacroContext,
        selection: WiSelection = WiSelection.ACTIVATED,
        allowedKeys: Set<String>? = null,
    ): String? {
        fun allowed(entry: WorldBookEntryEntity): Boolean = allowedKeys == null || "${entry.bookId}:${entry.id}" in allowedKeys
        val entries =
            when (selection) {
                WiSelection.CONSTANT -> group.filter { it.constant && allowed(it) }
                WiSelection.ACTIVATED -> group.filter { !it.constant && "${it.bookId}:${it.id}" in activatedIds }
                WiSelection.ALL ->
                    group.filter {
                        (it.constant && allowed(it)) || (!it.constant && "${it.bookId}:${it.id}" in activatedIds)
                    }
            }
        val title = if (selection == WiSelection.ACTIVATED) "Activated World Info:" else "World Info:"
        return renderWorldInfoSection(title, entries, mctx)
    }

    /** 单段渲染：标题 + 逐条 "[comment] 宏渲染内容"；空列表返回 null */
    private fun renderWorldInfoSection(
        title: String,
        entries: List<WorldBookEntryEntity>,
        mctx: MacroContext,
    ): String? =
        entries.takeIf { it.isNotEmpty() }?.let {
            "$title\n" +
                it.joinToString("\n\n") { e -> "[${e.comment}] ${MacroEngine.render(e.content, mctx)}" }
        }
}
