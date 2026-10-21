package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.SseParser
import com.mistbell.tavern.android.data.repository.RecallRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示词重排相关纯逻辑：@D 历史内插入（酒馆 doChatInject 语义）、混合召回去重与来源标注、流式 usage 解析 */
class PromptBuilderMemoryLayoutTest {
    // ---- insertWiDepthBlocks：酒馆原版深度插入（计数=历史消息+已插入@D块，system 块不占位）----

    private fun segment(
        role: String,
        content: String,
    ) = PromptBuilder.Segment(ChatMessage(role = role, content = content), content)

    private fun chatSeg(vararg contents: String) = contents.map { segment("assistant", it) }

    private fun wi(
        depth: Int,
        role: String = "system",
        tag: String = "d$depth$role",
    ) = PromptBuilder.WiDepthBlock(depth, role, segment(role, "@D$tag"))

    private fun contentsOf(messages: List<PromptBuilder.Segment>) = messages.map { it.message.content }

    @Test
    fun `深度1落在最终用户消息之前`() {
        val messages = (chatSeg("一", "二", "三") + segment("user", "当前")).toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(1)))
        assertEquals(listOf("一", "二", "三", "@Dd1system", "当前"), contentsOf(messages))
    }

    @Test
    fun `深度3之后恰好剩3条可计数消息`() {
        val messages = (chatSeg("一", "二", "三", "四", "五") + segment("user", "当前")).toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(3)))
        assertEquals(listOf("一", "二", "三", "@Dd3system", "四", "五", "当前"), contentsOf(messages))
    }

    @Test
    fun `尾部system块不占深度位`() {
        val messages =
            (
                chatSeg("一", "二", "三", "四", "五") +
                    segment("system", "记忆块") +
                    segment("system", "作者注释") +
                    segment("user", "当前")
            ).toMutableList()
        // 深度 2 → 之后剩 2 条可计数（五、当前）：落在五之前，不被尾部 system 块顶歪
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(2)))
        assertEquals(
            listOf("一", "二", "三", "四", "@Dd2system", "五", "记忆块", "作者注释", "当前"),
            contentsOf(messages),
        )
    }

    @Test
    fun `历史中间的system块同样不占位`() {
        val messages =
            (chatSeg("一") + segment("system", "世界书锚点") + chatSeg("二", "三") + segment("user", "当前"))
                .toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(1)))
        assertEquals(listOf("一", "世界书锚点", "二", "三", "@Dd1system", "当前"), contentsOf(messages))
    }

    @Test
    fun `群聊推动场景无最终用户消息_深度按历史计数`() {
        val messages = (chatSeg("一", "二", "三") + segment("system", "群聊推动")).toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(1)))
        assertEquals(listOf("一", "二", "@Dd1system", "三", "群聊推动"), contentsOf(messages))
    }

    @Test
    fun `深度超过可计数总数时钳到最旧历史之前`() {
        val messages =
            (listOf(segment("system", "角色卡")) + chatSeg("一") + segment("user", "当前")).toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(9)))
        assertEquals(listOf("角色卡", "@Dd9system", "一", "当前"), contentsOf(messages))
    }

    @Test
    fun `深度0追加到末尾`() {
        val messages = (chatSeg("一") + segment("user", "当前")).toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(0)))
        assertEquals(listOf("一", "当前", "@Dd0system"), contentsOf(messages))
    }

    @Test
    fun `多深度按酒馆补偿序_大深度更高`() {
        // 酒馆 doChatInject 等价：D2 块落在 D1 块之上（各自深度含已插入块补偿）
        val messages = chatSeg("一", "二", "三").toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, listOf(wi(1, tag = "A"), wi(2, tag = "B")))
        assertEquals(listOf("一", "@DB", "二", "@DA", "三"), contentsOf(messages))
    }

    @Test
    fun `同深度三角色最终呈现序为assistant到user到system`() {
        val messages = (chatSeg("一", "二") + segment("user", "当前")).toMutableList()
        PromptBuilder.insertWiDepthBlocks(
            messages,
            listOf(wi(1, "system", "S"), wi(1, "user", "U"), wi(1, "assistant", "A")),
        )
        assertEquals(listOf("一", "二", "@DA", "@DU", "@DS", "当前"), contentsOf(messages))
    }

    @Test
    fun `空块列表不改变消息`() {
        val messages = chatSeg("一", "二").toMutableList()
        PromptBuilder.insertWiDepthBlocks(messages, emptyList())
        assertEquals(listOf("一", "二"), contentsOf(messages))
    }

    // ---- buildRecallContextForPrompt：重复注入去重与来源标注 ----

    private fun hit(
        content: String,
        score: Float? = 0.9f,
    ) = RecallRanker.RankedRecall(
        candidate =
            RecallRanker.RecallCandidate(
                messageId = "m_${content.hashCode()}",
                content = content,
                vectorScore = score,
            ),
        fusedScore = 0f,
        fromVector = score != null,
        fromLexical = score == null,
    )

    @Test
    fun `内容已被结构化记忆覆盖的召回行被丢弃`() {
        val injected = listOf("蒋珞要求 user 放学后带芋泥口味的千层")
        // summary 副本形态："标题: 正文 [类型]"——正文被包含即为重复
        val render =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("蒋珞要千层: 蒋珞要求 user 放学后带芋泥口味的千层 [preference]")),
                alreadyInjected = injected,
            )
        assertEquals("", render.text)
        assertEquals(0, render.injectedCount)
    }

    @Test
    fun `未覆盖的召回行保留并带标题`() {
        val injected = listOf("蒋珞要求 user 放学后带芋泥口味的千层")
        val render =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("昨晚课堂上张老师点名提问了三次")),
                alreadyInjected = injected,
            )
        assertTrue(render.text.contains("## Relevant Past Conversations"))
        assertTrue(render.text.contains("昨晚课堂上张老师点名提问了三次"))
        assertEquals(1, render.injectedCount)
    }

    @Test
    fun `重复结果全部过滤时返回空串`() {
        val injected = listOf("user 的名字是墨轩")
        val render =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("user 的名字是墨轩 [identity]")),
                alreadyInjected = injected,
            )
        assertEquals("", render.text)
        assertEquals(0, render.injectedCount)
    }

    @Test
    fun `低分候选不再被硬编码分数下限误删`() {
        // 阈值在 RecallRanker 统一应用；旧格式化器硬编码 score > 0.5，
        // 把用户可配阈值（默认 0.35）实际钳到 0.5 才生效
        val out =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("阈值分段内的历史片段", score = 0.4f)),
                alreadyInjected = emptyList(),
            ).text
        assertTrue(out.contains("阈值分段内的历史片段"))
        assertTrue(out.contains("similarity: 40%"))
    }

    @Test
    fun `纯词法命中标注关键词来源而不伪造相似度`() {
        val out =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("含有专有名词的历史片段", score = null)),
                alreadyInjected = emptyList(),
            ).text
        assertTrue(out.contains("(keyword match)"))
        assertFalse(out.contains("similarity"))
    }

    @Test
    fun `无注入记忆时不做去重_结果原样保留`() {
        val out =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("任意历史片段")),
                alreadyInjected = emptyList(),
            ).text
        assertTrue(out.contains("1. 任意历史片段"))
    }

    @Test
    fun `记忆正文比召回行长时_双向包含判定仍视为重复`() {
        val out =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("user 讨厌被叫主人")),
                alreadyInjected = listOf("user 讨厌被叫主人，尤其是带嘲讽的语气"),
            ).text
        assertEquals("", out)
    }

    @Test
    fun `过短文本不参与包含判定_不被误删`() {
        val out =
            PromptBuilder.buildRecallContextForPrompt(
                ranked = listOf(hit("片段")),
                alreadyInjected = listOf("这是一条足够长的记忆正文片段内容"),
            ).text
        // 短文本不参与判定（长度 < 8），因此这条应保留
        assertTrue(out.contains("片段"))
    }

    // ---- MemoryContext：会话未裁剪时丢弃召回块（整段历史已可见，召回片段必然重复） ----

    private val structured = ChatMessage(role = "system", content = "## Known Information")
    private val recall = ChatMessage(role = "system", content = "## Relevant Past Conversations")

    @Test
    fun `会话未被裁剪时只注入结构化记忆_丢弃召回块`() {
        val context = PromptBuilder.MemoryContext(structuredBlock = structured, recallBlock = recall)
        assertEquals(listOf(structured), context.messagesFor(historyTruncated = false))
    }

    @Test
    fun `会话被裁剪时两块都注入`() {
        val context = PromptBuilder.MemoryContext(structuredBlock = structured, recallBlock = recall)
        assertEquals(listOf(structured, recall), context.messagesFor(historyTruncated = true))
    }

    @Test
    fun `预算口径始终按两块计算_避免低估撑爆上下文`() {
        val context = PromptBuilder.MemoryContext(structuredBlock = structured, recallBlock = recall)
        assertEquals(listOf(structured, recall), context.allMessages())
    }

    @Test
    fun `空上下文两种情况都为空`() {
        val empty = PromptBuilder.MemoryContext()
        assertEquals(emptyList<ChatMessage>(), empty.messagesFor(historyTruncated = false))
        assertEquals(emptyList<ChatMessage>(), empty.messagesFor(historyTruncated = true))
        assertEquals(emptyList<ChatMessage>(), empty.allMessages())
    }

    @Test
    fun `只有召回块时_未裁剪则什么都不注入`() {
        val context = PromptBuilder.MemoryContext(recallBlock = recall)
        assertEquals(emptyList<ChatMessage>(), context.messagesFor(historyTruncated = false))
        assertEquals(listOf(recall), context.messagesFor(historyTruncated = true))
    }

    // ---- SseParser：流式 usage（缓存统计）解析 ----

    @Test
    fun `流式 chunk 中的 usage 可被解析_用于缓存命中率观测`() {
        val line =
            "{\"choices\":[],\"usage\":{" +
                "\"prompt_tokens\":8512," +
                "\"completion_tokens\":412," +
                "\"prompt_cache_hit_tokens\":7357," +
                "\"prompt_cache_miss_tokens\":1155" +
                "}}"
        val chunk = SseParser.parseChunk(line)
        assertNotNull(chunk)
        assertEquals(7357, chunk!!.usage!!.cacheHitTokens)
        assertEquals(1155, chunk.usage!!.cacheMissTokens)
        assertEquals(8512, chunk.usage!!.promptTokens)
        assertNull(SseParser.contentOf(chunk)) // 该 chunk 无正文增量，不应产生输出
    }

    @Test
    fun `无 usage 字段的 chunk 返回 null_调用方按网关不提供统计处理`() {
        val chunk = SseParser.parseChunk("""{"choices":[{"delta":{"content":"你好"}}]}""")
        assertNotNull(chunk)
        assertNull(chunk!!.usage)
        assertEquals("你好", SseParser.contentOf(chunk))
    }

    // ---- orderLoreEntries：多来源合并排序（对齐酒馆 getSortedEntries）----

    private fun loreEntry(
        id: String,
        order: Int,
    ) = com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity(
        id = id,
        bookId = "book",
        comment = id,
        keysJson = "[]",
        content = "c$id",
        constant = false,
        disable = false,
        order = order,
        insertPosition = "before_prompt",
        depth = 0,
        probability = 1.0,
        depthRole = "system",
    )

    private fun orderIds(
        pairs: List<Pair<com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity, PromptBuilder.LoreSource>>,
        strategy: Int,
    ) = PromptBuilder.orderLoreEntries(pairs, strategy).map { it.id }

    @Test
    fun `角色优先时chat到persona到角色到全局`() {
        val pairs =
            listOf(
                loreEntry("g2", 20) to PromptBuilder.LoreSource.GLOBAL,
                loreEntry("c2", 20) to PromptBuilder.LoreSource.CHARACTER,
                loreEntry("p1", 30) to PromptBuilder.LoreSource.PERSONA,
                loreEntry("g1", 10) to PromptBuilder.LoreSource.GLOBAL,
                loreEntry("c1", 10) to PromptBuilder.LoreSource.CHARACTER,
                loreEntry("chat", 99) to PromptBuilder.LoreSource.CHAT,
            )
        assertEquals(
            listOf("chat", "p1", "c1", "c2", "g1", "g2"),
            orderIds(pairs, com.mistbell.tavern.android.data.repository.ChatSettingsResolver.STRATEGY_CHARACTER_FIRST),
        )
    }

    @Test
    fun `全局优先时全局先于角色`() {
        val pairs =
            listOf(
                loreEntry("c1", 10) to PromptBuilder.LoreSource.CHARACTER,
                loreEntry("g1", 10) to PromptBuilder.LoreSource.GLOBAL,
            )
        assertEquals(
            listOf("g1", "c1"),
            orderIds(pairs, com.mistbell.tavern.android.data.repository.ChatSettingsResolver.STRATEGY_GLOBAL_FIRST),
        )
    }

    @Test
    fun `evenly时角色与全局按order混排`() {
        val pairs =
            listOf(
                loreEntry("g2", 30) to PromptBuilder.LoreSource.GLOBAL,
                loreEntry("c2", 20) to PromptBuilder.LoreSource.CHARACTER,
                loreEntry("g1", 10) to PromptBuilder.LoreSource.GLOBAL,
                loreEntry("c1", 5) to PromptBuilder.LoreSource.CHARACTER,
            )
        assertEquals(
            listOf("c1", "g1", "c2", "g2"),
            orderIds(pairs, com.mistbell.tavern.android.data.repository.ChatSettingsResolver.STRATEGY_EVENLY),
        )
    }
}
