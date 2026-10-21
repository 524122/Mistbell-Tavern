package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.util.MacroContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorldInfoRuntime 触发语义单测（对齐酒馆 world-info.js）：
 * selectiveLogic 四态、selective 门控、caseSensitive/matchWholeWords、regex flags、
 * per-entry scanDepth、WI 预算裁剪与溢出停递归。
 *
 * 注意：WorldInfoRuntime 持有会话级静态状态，每个用例必须用独立 sessionId 隔离。
 */
class WorldInfoRuntimeTest {
    private val mctx = MacroContext(char = "莉莉", user = "User")

    @Suppress("LongParameterList") // 测试条目工厂：字段平铺即全部可调，不再封装
    private fun entry(
        id: String,
        keys: List<String>,
        secondaryKeys: List<String> = emptyList(),
        selective: Boolean = true,
        selectiveLogic: Int = 0,
        caseSensitive: Boolean? = null,
        matchWholeWords: Boolean? = null,
        scanDepth: Int? = null,
        content: String = "内容$id",
        order: Int = 100,
        sticky: Int = 0,
        ignoreBudget: Boolean = false,
        groupName: String = "",
        groupWeight: Int = 100,
        groupOverride: Boolean = false,
        excludeRecursion: Boolean = false,
        preventRecursion: Boolean = false,
        delayUntilRecursion: Int = 0,
        matchPersonaDescription: Boolean = false,
        matchCharacterDescription: Boolean = false,
        matchScenario: Boolean = false,
        characterFilterNames: List<String> = emptyList(),
        characterFilterTags: List<String> = emptyList(),
        characterFilterExclude: Boolean = false,
    ) = WorldBookEntryEntity(
        id = id,
        bookId = "book",
        comment = id,
        keysJson = keys.joinToString(",", "[", "]") { "\"$it\"" },
        content = content,
        constant = false,
        disable = false,
        order = order,
        insertPosition = "before_prompt",
        depth = 0,
        probability = 1.0,
        depthRole = "system",
        secondaryKeysJson = secondaryKeys.joinToString(",", "[", "]") { "\"$it\"" },
        selective = selective,
        selectiveLogic = selectiveLogic,
        caseSensitive = caseSensitive,
        matchWholeWords = matchWholeWords,
        scanDepth = scanDepth,
        sticky = sticky,
        ignoreBudget = ignoreBudget,
        groupName = groupName,
        groupWeight = groupWeight,
        groupOverride = groupOverride,
        excludeRecursion = excludeRecursion,
        preventRecursion = preventRecursion,
        delayUntilRecursion = delayUntilRecursion,
        matchPersonaDescription = matchPersonaDescription,
        matchCharacterDescription = matchCharacterDescription,
        matchScenario = matchScenario,
        characterFilterNamesJson = characterFilterNames.joinToString(",", "[", "]") { "\"$it\"" },
        characterFilterTagsJson = characterFilterTags.joinToString(",", "[", "]") { "\"$it\"" },
        characterFilterExclude = characterFilterExclude,
    )

    @Suppress("LongParameterList") // 测试辅助函数：参数即 ScanInput 平铺，不再二次封装
    private fun activate(
        session: String,
        entries: List<WorldBookEntryEntity>,
        history: List<String> = emptyList(),
        userMessage: String = "",
        globalScanDepth: Int = 2,
        budget: Int = Int.MAX_VALUE,
        recursive: Boolean = false,
        minActivations: Int = 0,
        minActivationsDepthMax: Int = 0,
        persistedStateJson: String? = null,
        participantNames: Set<String> = emptySet(),
        participantTags: Set<String> = emptySet(),
    ) = PromptBuilder.WorldInfoRuntime.activate(
        session,
        entries,
        PromptBuilder.WorldInfoRuntime.ScanInput(
            history,
            userMessage,
            globalScanDepth,
            mctx,
            budget,
            recursive,
            minActivations,
            minActivationsDepthMax,
            persistedStateJson,
            participantNames,
            participantTags,
        ),
    )

    // ---- selectiveLogic 四态（酒馆 world_info_logic）----

    @Test
    fun `AND_ANY_任一次键命中即激活`() {
        val e = entry("a", keys = listOf("学院"), secondaryKeys = listOf("夜间", "禁足"), selectiveLogic = 0)
        val result = activate("s-and-any", listOf(e), userMessage = "学院里有夜间巡逻")
        assertEquals(setOf("book:a"), result.activated)
    }

    @Test
    fun `AND_ANY_次键都不命中则不激活`() {
        val e = entry("a", keys = listOf("学院"), secondaryKeys = listOf("夜间", "禁足"), selectiveLogic = 0)
        val result = activate("s-and-any-none", listOf(e), userMessage = "学院开门了")
        assertTrue(result.activated.isEmpty())
    }

    @Test
    fun `AND_ALL_次键缺一不激活`() {
        val e = entry("a", keys = listOf("学院"), secondaryKeys = listOf("夜间", "禁足"), selectiveLogic = 3)
        val result = activate("s-and-all-miss", listOf(e), userMessage = "学院里有夜间巡逻")
        assertTrue(result.activated.isEmpty())
    }

    @Test
    fun `AND_ALL_次键全中才激活`() {
        val e = entry("a", keys = listOf("学院"), secondaryKeys = listOf("夜间", "禁足"), selectiveLogic = 3)
        val result = activate("s-and-all-hit", listOf(e), userMessage = "学院夜间禁足")
        assertEquals(setOf("book:a"), result.activated)
    }

    @Test
    fun `NOT_ANY_次键全不命中才激活`() {
        val e = entry("a", keys = listOf("学院"), secondaryKeys = listOf("夜间", "禁足"), selectiveLogic = 2)
        assertEquals(setOf("book:a"), activate("s-not-any-hit", listOf(e), userMessage = "学院开门了").activated)
        assertTrue(activate("s-not-any-miss", listOf(e), userMessage = "学院夜间巡逻").activated.isEmpty())
    }

    @Test
    fun `NOT_ALL_任一次键未命中即激活`() {
        val e = entry("a", keys = listOf("学院"), secondaryKeys = listOf("夜间", "禁足"), selectiveLogic = 1)
        assertEquals(setOf("book:a"), activate("s-not-all-hit", listOf(e), userMessage = "学院夜间巡逻").activated)
        assertTrue(activate("s-not-all-miss", listOf(e), userMessage = "学院夜间禁足").activated.isEmpty())
    }

    @Test
    fun `selective为false时次键不参与门控`() {
        val e = entry("a", keys = listOf("学院"), secondaryKeys = listOf("夜间"), selective = false, selectiveLogic = 3)
        val result = activate("s-selective-off", listOf(e), userMessage = "学院开门了")
        assertEquals(setOf("book:a"), result.activated)
    }

    // ---- 大小写与全字 ----

    @Test
    fun `默认忽略大小写`() {
        val e = entry("a", keys = listOf("Apple"))
        assertEquals(setOf("book:a"), activate("s-case-def", listOf(e), userMessage = "eat an apple").activated)
    }

    @Test
    fun `caseSensitive开启后大小写必须一致`() {
        val e = entry("a", keys = listOf("Apple"), caseSensitive = true)
        assertTrue(activate("s-case-on-miss", listOf(e), userMessage = "eat an apple").activated.isEmpty())
        assertEquals(setOf("book:a"), activate("s-case-on-hit", listOf(e), userMessage = "Apple pie").activated)
    }

    @Test
    fun `matchWholeWords单词走边界匹配`() {
        val e = entry("a", keys = listOf("cat"), matchWholeWords = true)
        assertTrue(activate("s-ww-miss", listOf(e), userMessage = "concatenate strings").activated.isEmpty())
        assertEquals(setOf("book:a"), activate("s-ww-hit", listOf(e), userMessage = "a cat, here").activated)
    }

    @Test
    fun `matchWholeWords多词key退化为普通包含`() {
        val e = entry("a", keys = listOf("fat cat"), matchWholeWords = true)
        assertEquals(setOf("book:a"), activate("s-ww-multi", listOf(e), userMessage = "the fat cat sat").activated)
    }

    @Test
    fun `regex键flags原样生效且不受大小写开关影响`() {
        // 无 i flag = 大小写敏感（酒馆 parseRegexFromString 不附加默认 flag）
        val sensitive = entry("a", keys = listOf("/^abc\$/"))
        assertTrue(activate("s-regex-miss", listOf(sensitive), userMessage = "ABC").activated.isEmpty())
        val insensitive = entry("b", keys = listOf("/^abc\$/i"))
        assertEquals(setOf("book:b"), activate("s-regex-hit", listOf(insensitive), userMessage = "ABC").activated)
        // 条目开了 caseSensitive 也不影响正则（酒馆：regex 覆盖其他选项）
        val regexWithCs = entry("c", keys = listOf("/^abc\$/i"), caseSensitive = true)
        assertEquals(setOf("book:c"), activate("s-regex-cs", listOf(regexWithCs), userMessage = "ABC").activated)
    }

    @Test
    fun `关键词先过宏渲染再匹配`() {
        val e = entry("a", keys = listOf("{{char}}"))
        assertEquals(setOf("book:a"), activate("s-macro", listOf(e), userMessage = "莉莉来了").activated)
    }

    // ---- 扫描深度 ----

    @Test
    fun `全局扫描深度只覆盖最近N减1条历史加当前消息`() {
        val e = entry("a", keys = listOf("旧钥匙"))
        val history = listOf("提到旧钥匙的故事", "无关一", "无关二")
        // 全局深度 2 = 当前消息 + 最近 1 条历史 → 最旧一条扫不到
        assertTrue(activate("s-depth-g2", listOf(e), history, "现在做什么", globalScanDepth = 2).activated.isEmpty())
        // 全局深度 4 = 当前消息 + 最近 3 条历史 → 扫得到
        val deep = activate("s-depth-g4", listOf(e), history, "现在做什么", globalScanDepth = 4)
        assertEquals(setOf("book:a"), deep.activated)
    }

    @Test
    fun `条目scanDepth覆盖全局设置`() {
        val e = entry("a", keys = listOf("旧钥匙"), scanDepth = 4)
        val history = listOf("提到旧钥匙的故事", "无关一", "无关二")
        val result = activate("s-depth-entry", listOf(e), history, "现在做什么", globalScanDepth = 2)
        assertEquals(setOf("book:a"), result.activated)
    }

    @Test
    fun `scanDepth为0时条目永不通过扫描激活`() {
        val e = entry("a", keys = listOf("钥匙"), scanDepth = 0)
        assertTrue(activate("s-depth-zero", listOf(e), userMessage = "钥匙").activated.isEmpty())
    }

    // ---- 预算 ----

    @Test
    fun `预算按order降序优先保住_溢出条目进丢弃清单`() {
        val low = entry("low", keys = listOf("k"), order = 10)
        val high = entry("high", keys = listOf("k"), order = 90)
        // 预算只够一条（内容"内容high"/"内容low"估算 4 CJK 字符 ≈ 3 token，给 4）
        val result = activate("s-budget", listOf(low, high), userMessage = "k", budget = 4)
        assertEquals(setOf("book:high"), result.activated)
        assertEquals(listOf("low"), result.droppedByBudget.map { it.id })
    }

    @Test
    fun `预算溢出后停止递归_连锁条目不再被激活`() {
        // a 的关键词在原文；b 的关键词只出现在 a 的内容里（靠递归激活）
        val a = entry("a", keys = listOf("k"), content = "b的引子", order = 90)
        val b = entry("b", keys = listOf("b的引子"), order = 10)
        // 预算 4 只够 a（内容 4 CJK 字符）；a 消费完溢出 → 不再递归 → b 永不激活
        val result = activate("s-budget-recursion", listOf(a, b), userMessage = "k", budget = 4, recursive = true)
        assertEquals(setOf("book:a"), result.activated)
        assertEquals(listOf("b"), result.droppedByBudget.map { it.id })
        // 对照：预算充足时 b 经递归激活
        val rich = activate("s-budget-recursion-rich", listOf(a, b), userMessage = "k", recursive = true)
        assertEquals(setOf("book:a", "book:b"), rich.activated)
    }

    @Test
    fun `递归关闭时连锁条目不激活`() {
        // 酒馆默认 world_info_recursive=false：b 的关键词只在 a 的内容里，不递归就永不命中
        val a = entry("a", keys = listOf("k"), content = "b的引子")
        val b = entry("b", keys = listOf("b的引子"))
        val result = activate("s-recursive-off", listOf(a, b), userMessage = "k", recursive = false)
        assertEquals(setOf("book:a"), result.activated)
    }

    // ---- sticky 数值语义（酒馆：保持 N 轮，过期后 cooldown 接力）----

    @Test
    fun `sticky条目激活后保持N轮再过期`() {
        val e = entry("a", keys = listOf("k"), sticky = 2)
        // 第 1 轮：关键词命中激活并武装粘性
        assertEquals(setOf("book:a"), activate("s-sticky-1", listOf(e), userMessage = "k").activated)
        // 第 2、3 轮：无关键词仍激活（粘性保持）
        assertEquals(setOf("book:a"), activate("s-sticky-1", listOf(e), userMessage = "无关").activated)
        assertEquals(setOf("book:a"), activate("s-sticky-1", listOf(e), userMessage = "无关").activated)
        // 第 4 轮：粘性过期（激活轮 1 + 保持 2 轮）
        assertTrue(activate("s-sticky-1", listOf(e), userMessage = "无关").activated.isEmpty())
    }

    @Test
    fun `sticky为0不粘`() {
        val e = entry("a", keys = listOf("k"), sticky = 0)
        assertEquals(setOf("book:a"), activate("s-sticky-0", listOf(e), userMessage = "k").activated)
        assertTrue(activate("s-sticky-0", listOf(e), userMessage = "无关").activated.isEmpty())
    }

    // ---- 分组竞争（对齐酒馆：sticky 强制胜 → groupOverride 最高 order → groupWeight 加权随机）----

    @Test
    fun `groupOverride条目按最高order胜出`() {
        val loser = entry("lo", keys = listOf("k"), groupName = "g", order = 10)
        val winner = entry("hi", keys = listOf("k"), groupName = "g", order = 90, groupOverride = true)
        val result = activate("s-group-override", listOf(loser, winner), userMessage = "k")
        assertEquals(setOf("book:hi"), result.activated)
    }

    @Test
    fun `同组加权随机_权重0永不胜出`() {
        val never = entry("never", keys = listOf("k"), groupName = "g", groupWeight = 0)
        val always = entry("always", keys = listOf("k"), groupName = "g", groupWeight = 100)
        repeat(10) { round ->
            val result = activate("s-group-weight-$round", listOf(never, always), userMessage = "k")
            assertEquals(setOf("book:always"), result.activated)
        }
    }

    @Test
    fun `同组sticky候选强制胜出`() {
        val stickyEntry = entry("st", keys = listOf("k"), groupName = "g", order = 10, sticky = 2)
        // 第一轮：关键词激活并武装粘性
        activate("s-group-sticky", listOf(stickyEntry), userMessage = "k")
        // 第二轮：粘性期内的 sticky 条目压过同组新命中条目（order 更高也没用）
        val challenger = entry("ch", keys = listOf("x"), groupName = "g", order = 90)
        val result = activate("s-group-sticky", listOf(stickyEntry, challenger), userMessage = "x")
        assertEquals(setOf("book:st"), result.activated)
    }

    @Test
    fun `组外条目不受分组竞争影响`() {
        val a = entry("a", keys = listOf("k"))
        val b = entry("b", keys = listOf("k"), groupName = "g", groupWeight = 100)
        val result = activate("s-group-outside", listOf(a, b), userMessage = "k")
        assertEquals(setOf("book:a", "book:b"), result.activated)
    }

    // ---- ignoreBudget（酒馆：豁免预算且不被溢出裁剪）----

    @Test
    fun `ignoreBudget条目不占额度且溢出也注入`() {
        val exempt = entry("big", keys = listOf("k"), order = 90, ignoreBudget = true)
        val normal = entry("small", keys = listOf("k"), order = 10)
        // 预算 1：exempt 不消费额度照常注入；normal 消费不起（3 token > 1）进丢弃清单
        val result = activate("s-ignore-budget", listOf(exempt, normal), userMessage = "k", budget = 1)
        assertEquals(setOf("book:big"), result.activated)
        assertEquals(listOf("small"), result.droppedByBudget.map { it.id })
        // 全程无预算时语义不变
        val free = activate("s-ignore-budget-free", listOf(exempt, normal), userMessage = "k")
        assertEquals(setOf("book:big", "book:small"), free.activated)
        assertTrue(free.droppedByBudget.isEmpty())
    }

    @Test
    fun `预算关闭时不裁剪`() {
        val a = entry("a", keys = listOf("k"), order = 90)
        val b = entry("b", keys = listOf("k"), order = 10)
        val result = activate("s-budget-off", listOf(a, b), userMessage = "k", budget = Int.MAX_VALUE)
        assertEquals(setOf("book:a", "book:b"), result.activated)
        assertTrue(result.droppedByBudget.isEmpty())
    }

    // ---- constant 预算分配（装配侧纯函数）----

    @Test
    fun `constant预算按order升序先装_溢出即停`() {
        val first = entry("c1", keys = emptyList(), order = 10).copy(constant = true)
        val second = entry("c2", keys = emptyList(), order = 20).copy(constant = true)
        val third = entry("c3", keys = emptyList(), order = 30).copy(constant = true)
        // 每条内容 2 CJK + 2 ASCII ≈ 2 token；预算 5 装两条后剩 1，第三条被裁
        val result = PromptBuilder.allocateConstantBudget(listOf(first, second, third), budgetTokens = 5)
        assertEquals(setOf("book:c1", "book:c2"), result.allowed)
        assertEquals(listOf("c3"), result.dropped.map { it.id })
    }

    @Test
    fun `constant预算关闭时全部放行`() {
        val c = entry("c1", keys = emptyList()).copy(constant = true)
        val result = PromptBuilder.allocateConstantBudget(listOf(c), budgetTokens = Int.MAX_VALUE)
        assertEquals(setOf("book:c1"), result.allowed)
        assertTrue(result.dropped.isEmpty())
        assertEquals(Int.MAX_VALUE, result.remainingTokens)
    }

    @Test
    fun `禁用与非常驻条目不占constant预算`() {
        val disabled = entry("c1", keys = emptyList()).copy(constant = true, disable = true)
        val notConstant = entry("a", keys = listOf("k"))
        val result = PromptBuilder.allocateConstantBudget(listOf(disabled, notConstant), budgetTokens = 0)
        assertTrue(result.allowed.isEmpty())
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `ignoreBudget的constant条目不占预算恒放行`() {
        val exempt = entry("c1", keys = emptyList()).copy(constant = true, ignoreBudget = true)
        val normal = entry("c2", keys = emptyList(), order = 20).copy(constant = true)
        val result = PromptBuilder.allocateConstantBudget(listOf(normal, exempt), budgetTokens = 0)
        assertEquals(setOf("book:c1"), result.allowed)
        assertEquals(listOf("c2"), result.dropped.map { it.id })
    }

    // ---- 条目级递归控制（酒馆 excludeRecursion/preventRecursion/delayUntilRecursion）----

    @Test
    fun `preventRecursion条目不被递归轮激活`() {
        // direct 首轮命中并产出含 "z" 的内容；guarded 的键只在递归缓冲里出现
        val direct = entry("d", keys = listOf("k"), content = "含z")
        val guarded = entry("g", keys = listOf("z"), preventRecursion = true)
        val normal = entry("n", keys = listOf("z"))
        val result = activate("s-prevent-rec", listOf(direct, guarded, normal), userMessage = "k", recursive = true)
        assertEquals(setOf("book:d", "book:n"), result.activated)
    }

    @Test
    fun `excludeRecursion条目的内容不进递归缓冲`() {
        val excluded = entry("x", keys = listOf("k"), content = "含z", excludeRecursion = true)
        val chained = entry("c", keys = listOf("z"))
        val result = activate("s-exclude-rec", listOf(excluded, chained), userMessage = "k", recursive = true)
        // x 激活了，但其内容未进递归缓冲 → c 无法被连锁激活
        assertEquals(setOf("book:x"), result.activated)
    }

    @Test
    fun `delayUntilRecursion按递归层级放行`() {
        val direct = entry("d", keys = listOf("k"), content = "含z")
        val delayed = entry("y", keys = listOf("z"), delayUntilRecursion = 1)
        val on = activate("s-delay-rec-on", listOf(direct, delayed), userMessage = "k", recursive = true)
        assertEquals(setOf("book:d", "book:y"), on.activated)
        // 递归关闭：只有首轮（层级 0），delay=1 的条目永不放行
        val off = activate("s-delay-rec-off", listOf(direct, delayed), userMessage = "k", recursive = false)
        assertEquals(setOf("book:d"), off.activated)
    }

    // ---- 扫描面扩展（酒馆 matchXxx）----

    @Test
    fun `matchScenario开启后场景文本参与匹配`() {
        val scoped = entry("s", keys = listOf("龙"), matchScenario = true)
        val mctxWithScenario = mctx.copy(scenario = "古堡里有龙")
        val result =
            PromptBuilder.WorldInfoRuntime.activate(
                "s-match-scenario",
                listOf(scoped),
                PromptBuilder.WorldInfoRuntime.ScanInput(
                    emptyList(),
                    "今天去哪",
                    2,
                    mctxWithScenario,
                    Int.MAX_VALUE,
                    false,
                    0,
                    0,
                ),
            )
        assertEquals(setOf("book:s"), result.activated)
        // 不开关：场景不进扫描文本，同样的键不命中
        val plain = entry("p", keys = listOf("龙"))
        val off =
            PromptBuilder.WorldInfoRuntime.activate(
                "s-match-scenario-off",
                listOf(plain),
                PromptBuilder.WorldInfoRuntime.ScanInput(
                    emptyList(),
                    "今天去哪",
                    2,
                    mctxWithScenario,
                    Int.MAX_VALUE,
                    false,
                    0,
                    0,
                ),
            )
        assertTrue(off.activated.isEmpty())
    }

    // ---- min-activations 加深扫描（酒馆 skew）----

    @Test
    fun `激活数不足时逐轮加深扫描直至命中`() {
        val e = entry("a", keys = listOf("旧钥匙"))
        val history = listOf("提到旧钥匙的故事", "无关一", "无关二")
        // 全局深度 1 只扫当前消息；minActivations=1 迫使逐轮加深直至覆盖最旧一条
        val result =
            activate(
                "s-min-act",
                listOf(e),
                history,
                "现在做什么",
                globalScanDepth = 1,
                minActivations = 1,
            )
        assertEquals(setOf("book:a"), result.activated)
    }

    @Test
    fun `加深到聊天长度仍无命中则空手而归不死循环`() {
        val e = entry("a", keys = listOf("不存在的关键词"))
        val result =
            activate(
                "s-min-act-none",
                listOf(e),
                listOf("一", "二"),
                "现在做什么",
                globalScanDepth = 1,
                minActivations = 1,
            )
        assertTrue(result.activated.isEmpty())
    }

    @Test
    fun `加深上限优先于聊天长度`() {
        val e = entry("a", keys = listOf("旧钥匙"))
        val history = listOf("提到旧钥匙的故事", "无关一", "无关二")
        // depthMax=2：加深到深度 2 即止，覆盖不到最旧一条
        val result =
            activate(
                "s-min-act-cap",
                listOf(e),
                history,
                "现在做什么",
                globalScanDepth = 1,
                minActivations = 1,
                minActivationsDepthMax = 2,
            )
        assertTrue(result.activated.isEmpty())
    }

    // ---- 角色过滤（酒馆 characterFilter：白名单/标签/黑名单）----

    @Test
    fun `角色名白名单_仅匹配角色生效`() {
        val e = entry("a", keys = listOf("k"), characterFilterNames = listOf("莉莉"))
        assertEquals(
            setOf("book:a"),
            activate("s-cf-hit", listOf(e), userMessage = "k", participantNames = setOf("莉莉")).activated,
        )
        assertTrue(
            activate("s-cf-miss", listOf(e), userMessage = "k", participantNames = setOf("别人")).activated.isEmpty(),
        )
    }

    @Test
    fun `角色标签匹配也生效`() {
        val e = entry("a", keys = listOf("k"), characterFilterTags = listOf("魔法少女"))
        assertEquals(
            setOf("book:a"),
            activate("s-cf-tag", listOf(e), userMessage = "k", participantTags = setOf("魔法少女")).activated,
        )
    }

    @Test
    fun `exclude为true时匹配角色反而不生效`() {
        val e = entry("a", keys = listOf("k"), characterFilterNames = listOf("莉莉"), characterFilterExclude = true)
        assertTrue(
            activate("s-cf-ex-hit", listOf(e), userMessage = "k", participantNames = setOf("莉莉")).activated.isEmpty(),
        )
        assertEquals(
            setOf("book:a"),
            activate("s-cf-ex-miss", listOf(e), userMessage = "k", participantNames = setOf("别人")).activated,
        )
    }

    @Test
    fun `过滤器为空时不限角色`() {
        val e = entry("a", keys = listOf("k"), characterFilterExclude = true)
        assertEquals(setOf("book:a"), activate("s-cf-empty", listOf(e), userMessage = "k").activated)
    }

    // ---- timed effects 持久化（酒馆 timedWorldInfo：重启后 sticky/cooldown 存续）----

    @Test
    fun `状态快照可持久化并可恢复粘性`() {
        val e = entry("a", keys = listOf("k"), sticky = 2)
        // 第 1 轮：关键词命中并武装粘性，产出可入库的快照
        val first = activate("s-persist-src", listOf(e), userMessage = "k")
        assertEquals(setOf("book:a"), first.activated)
        assertTrue(first.stateSnapshotJson.isNotBlank())
        // 模拟重启：新会话 id（内存无该会话状态），从快照恢复——无关键词仍因粘性激活
        val restored =
            activate("s-persist-restored", listOf(e), userMessage = "无关", persistedStateJson = first.stateSnapshotJson)
        assertEquals(setOf("book:a"), restored.activated)
    }

    @Test
    fun `快照损坏时按无状态降级`() {
        val e = entry("a", keys = listOf("k"))
        val result = activate("s-persist-bad", listOf(e), userMessage = "无关", persistedStateJson = "{ 不是 json")
        assertTrue(result.activated.isEmpty())
        // 但产出的新快照必须合法
        assertTrue(result.stateSnapshotJson.isNotBlank())
    }
}
