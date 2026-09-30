package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BERT WordPiece 分词器防线（JVM，纯 Kotlin 无 Android 依赖）。
 * 词汇表用受控小词表验证规则；真实 bge-small-zh 词表在 JVM 上同样可读（assets 路径）。
 */
class BertWordPieceTokenizerTest {
    /** 受控小词表：[PAD]=0, [UNK]=100, [CLS]=101, [SEP]=102, 常用中英词与 ## 续接 */
    private fun tokenizer(): BertWordPieceTokenizer {
        val lines =
            listOf(
                "[PAD]", "[unused0]", "[UNK]", "[CLS]", "[SEP]", "un", "##aff", "##able",
                "word", "##piece", "hello", "world", "我", "们", "是", "好", "友", "hello", "，", "。", "a", "b",
            )
        val vocab = lines.mapIndexed { i, t -> t to i }.toMap()
        return BertWordPieceTokenizer(vocab)
    }

    @Test
    fun `英文词 WordPiece 贪心最长匹配`() {
        // unaffable → un + ##aff + ##able（词表无整词，按子词拆）
        assertEquals(listOf("un", "##aff", "##able"), tokenizer().tokenize("unaffable"))
        // hello 词表命中整词，不再下钻
        assertEquals(listOf("hello"), tokenizer().tokenize("hello"))
    }

    @Test
    fun `词表未命中产出 UNK`() {
        // zzz 不在词表且无任何子词命中 → [UNK]
        assertEquals(listOf(BertWordPieceTokenizer.UNK), tokenizer().tokenize("zzzz"))
    }

    @Test
    fun `中文逐字切分后按词表组合`() {
        // 我们是好友：CJK 逐字切分，逐字在词表 → 单字 token 序列
        assertEquals(
            listOf("我", "们", "是", "好", "友"),
            tokenizer().tokenize("我们是好友"),
        )
    }

    @Test
    fun `标点独立成词且空白折叠`() {
        assertEquals(
            listOf("hello", "，", "world", "。"),
            tokenizer().tokenize("hello ，  world。"),
        )
    }

    @Test
    fun `encode 包裹 CLS SEP 且截断保留 SEP`() {
        // 受控词表中 [CLS]=3 / [SEP]=4（行号即 id）；HF 词表才是 101/102（由词表自身提供）
        val ids = tokenizer().encode("hello world hello world hello", maxLen = 6)
        assertEquals(6, ids.size)
        assertEquals(3, ids[0]) // [CLS]
        assertEquals(4, ids[5]) // [SEP]
    }

    @Test
    fun `基础分词规则：清洗与大小写开关`() {
        val tk = tokenizer()
        // 控制字符删除、\t 折叠为空格
        assertEquals(listOf("hello"), tk.whitespaceTokenize(tk.cleanText("hel\u0000lo")))
        // 默认不 lower-case
        assertEquals("Hello", tk.runBasicSplit("Hello").first())
        val lower =
            BertWordPieceTokenizer(
                mapOf("hello" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102),
                doLowerCase = true,
            )
        assertEquals(listOf("hello"), lower.tokenize("Hello"))
    }

    @Test
    fun `超长词直接 UNK`() {
        val long = "a".repeat(200)
        assertEquals(listOf(BertWordPieceTokenizer.UNK), tokenizer().tokenize(long))
    }

    @Test
    fun `字符分类边界`() {
        // 字符分类为文件级顶层函数（避 TooManyFunctions，internal 可测）
        assertTrue(isChineseChar('中'))
        assertFalse(isChineseChar('A'))
        assertTrue(isPunctuation('，')) // 全角逗号为 OTHER_PUNCTUATION
        assertTrue(isWhitespace('　')) // 全角空格 SPACE_SEPARATOR
        assertFalse(isControl('中'))
    }

    @Test
    fun `fromVocabText 紧凑行号即 id 且空行不占位`() {
        val text = "[PAD]\n[UNK]\n\n[CLS]\n[SEP]\n"
        val tk = BertWordPieceTokenizer.fromVocabText(text)
        // [CLS] 前有 2 个非空 token，空行不占 id → CLS=2
        assertEquals(2, tk.encode("anything")[0])
        // 未知词 → UNK=1
        assertEquals(1, tk.encode("anything")[1])
    }

    @Test
    fun `内置 bge 词表可加载且中文分词无 UNK`() {
        // 生产资产防线：词表与分词器在 JVM 上即可对齐验证（模型推理需设备，CI 不覆盖）
        val vocabFile = File("src/main/assets/models/bge-small-zh-vocab.txt")
        assertTrue("bge 词表资产缺失: ${vocabFile.absolutePath}", vocabFile.isFile)
        val tk = BertWordPieceTokenizer.fromVocabText(vocabFile.readText(Charsets.UTF_8))
        val tokens = tk.tokenize("雾铃镇的老板娘记性极好")
        assertTrue(tokens.isNotEmpty())
        assertFalse("常见中文不应产生 UNK: $tokens", tokens.contains(BertWordPieceTokenizer.UNK))
    }
}
