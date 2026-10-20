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
    )

    @Suppress("LongParameterList") // 测试辅助函数：参数即 ScanInput 平铺，不再二次封装
    private fun activate(
        session: String,
        entries: List<WorldBookEntryEntity>,
        history: List<String> = emptyList(),
        userMessage: String = "",
        globalScanDepth: Int = 2,
        budget: Int = Int.MAX_VALUE,
    ) = PromptBuilder.WorldInfoRuntime.activate(
        session,
        entries,
        PromptBuilder.WorldInfoRuntime.ScanInput(history, userMessage, globalScanDepth, mctx, budget),
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
        val result = activate("s-budget-recursion", listOf(a, b), userMessage = "k", budget = 4)
        assertEquals(setOf("book:a"), result.activated)
        assertEquals(listOf("b"), result.droppedByBudget.map { it.id })
        // 对照：预算充足时 b 经递归激活
        val rich = activate("s-budget-recursion-rich", listOf(a, b), userMessage = "k")
        assertEquals(setOf("book:a", "book:b"), rich.activated)
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
}
