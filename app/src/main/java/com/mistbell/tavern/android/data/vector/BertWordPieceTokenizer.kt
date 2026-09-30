package com.mistbell.tavern.android.data.vector

import java.text.Normalizer

/**
 * BERT WordPiece 分词器（纯 Kotlin，无 Android 依赖，JVM 可测）。
 *
 * 实现参照 HuggingFace `BertTokenizer` 的默认流程：
 * 1. **基础分词**：清洗控制字符 → 空白折叠为单空格 → 按空格切词 → 每个词内标点与
 *    CJK 表意文字逐字切分（中文模型不 lower-case，保留大小写为构造参数）；
 * 2. **WordPiece 贪心最长匹配**：词内子词加 `##` 前缀续接，词表未命中产出 `[UNK]`。
 *
 * 词表格式：每行一个 token（HF `vocab.txt`），行号即 id。
 * 供 [LocalEmbeddingService] 把文本编码为模型输入 id（含 `[CLS]`/`[SEP]` 与截断）。
 */
class BertWordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val doLowerCase: Boolean = false,
    private val stripAccents: Boolean = false,
    private val maxInputCharsPerWord: Int = 100,
) {
    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val CONTINUATION_PREFIX = "##"

        // HF 标准词表中 [CLS]=101 / [SEP]=102；仅当词表缺失这两个键时作兜底
        private const val CLS_FALLBACK_ID = 101
        private const val SEP_FALLBACK_ID = 102

        /** 从 HF vocab.txt 文本构建：每行一个 token，紧凑行号即 id（空行跳过不占位） */
        fun fromVocabText(
            text: String,
            doLowerCase: Boolean = false,
            stripAccents: Boolean = false,
        ): BertWordPieceTokenizer {
            val vocab = LinkedHashMap<String, Int>()
            var id = 0
            for (line in text.lineSequence()) {
                val token = line.trim()
                if (token.isNotEmpty()) {
                    vocab[token] = id++
                }
            }
            return BertWordPieceTokenizer(vocab, doLowerCase, stripAccents)
        }
    }

    private val unkId: Int = vocab[UNK] ?: 0

    /** 基础分词后的词列表（未做 WordPiece 切分） */
    internal fun basicTokens(text: String): List<String> {
        return whitespaceTokenize(cleanText(text)).flatMap { token -> runBasicSplit(token) }
    }

    /** WordPiece 分词结果（不含 [CLS]/[SEP]） */
    fun tokenize(text: String): List<String> = basicTokens(text).flatMap { word -> wordpiece(word) }

    /**
     * 编码为模型输入 id：`[CLS] tokens [SEP]`，超长从右侧截断保留 [SEP]。
     * @param maxLen 最大序列长度（含特殊符号）
     */
    fun encode(
        text: String,
        maxLen: Int = 512,
    ): IntArray {
        val ids = ArrayList<Int>(maxLen)
        ids.add(vocab[CLS] ?: CLS_FALLBACK_ID)
        for (token in tokenize(text)) {
            if (ids.size >= maxLen - 1) break
            ids.add(vocab[token] ?: unkId)
        }
        ids.add(vocab[SEP] ?: SEP_FALLBACK_ID)
        return ids.toIntArray()
    }

    val vocabSize: Int get() = vocab.size

    // ---- 基础分词（对照 BasicTokenizer） ----

    /** 清洗：控制字符删除、空白（含 \t\n\r 与 NBSP 等）折叠为普通空格 */
    internal fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (char in text) {
            if (isControl(char)) continue
            sb.append(if (isWhitespace(char)) ' ' else char)
        }
        return sb.toString()
    }

    /** 空白切分 */
    internal fun whitespaceTokenize(text: String): List<String> =
        text
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    /** 单词内的基础切分：标点与 CJK 逐字成词，按需 lower-case / 去重音 */
    internal fun runBasicSplit(token: String): List<String> {
        val processed = preProcess(token)
        val output = ArrayList<String>()
        var current = StringBuilder()
        for (char in processed) {
            if (isPunctuation(char) || isChineseChar(char)) {
                if (current.isNotEmpty()) {
                    output.add(current.toString())
                    current = StringBuilder()
                }
                output.add(char.toString())
            } else {
                current.append(char)
            }
        }
        if (current.isNotEmpty()) output.add(current.toString())
        return output
    }

    private fun preProcess(token: String): String {
        var t = if (doLowerCase) token.lowercase() else token
        if (stripAccents) {
            t =
                Normalizer.normalize(t, Normalizer.Form.NFD)
                    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        }
        return t
    }

    // ---- WordPiece 贪心最长匹配 ----

    internal fun wordpiece(token: String): List<String> {
        if (token.length > maxInputCharsPerWord) return listOf(UNK)
        val output = ArrayList<String>()
        var start = 0
        var failed = false
        while (start < token.length && !failed) {
            var end = token.length
            var found: String? = null
            while (start < end) {
                val piece =
                    if (start == 0) {
                        token.substring(start, end)
                    } else {
                        CONTINUATION_PREFIX + token.substring(start, end)
                    }
                if (vocab.containsKey(piece)) {
                    found = piece
                    break
                }
                end--
            }
            if (found == null) {
                failed = true
            } else {
                output.add(found)
                start = end
            }
        }
        return if (failed) listOf(UNK) else output
    }
}

// ---- 字符分类（对照 transformers 的 _is_whitespace/_is_control/_is_punctuation） ----
// 文件级 internal：置类外避免触发 TooManyFunctions，同时保留 JVM 测试可达性

private const val NBSP_CHAR_CODE = 0x00A0

internal fun isWhitespace(char: Char): Boolean =
    char == ' ' || char == '\t' || char == '\n' || char == '\r' || char == Char(NBSP_CHAR_CODE) ||
        Character.getType(char) == Character.SPACE_SEPARATOR.toInt()

internal fun isControl(char: Char): Boolean {
    if (char == '\t' || char == '\n' || char == '\r') return false
    val type = Character.getType(char)
    return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
}

internal fun isPunctuation(char: Char): Boolean {
    val type = Character.getType(char)
    return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
        type == Character.DASH_PUNCTUATION.toInt() ||
        type == Character.START_PUNCTUATION.toInt() ||
        type == Character.END_PUNCTUATION.toInt() ||
        type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
        type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
        type == Character.OTHER_PUNCTUATION.toInt()
}

/**
 * CJK 表意文字：BERT 规范要求逐字切分（中文按字进词表再 WordPiece 组合）。
 * 码点区间即 Unicode 规范本体，非魔法数字。
 */
@Suppress("MagicNumber")
internal fun isChineseChar(char: Char): Boolean {
    val cp = char.code
    return (cp in 0x4E00..0x9FFF) ||
        (cp in 0x3400..0x4DBF) ||
        (cp in 0x20000..0x2A6DF) ||
        (cp in 0x2A700..0x2B73F) ||
        (cp in 0x2B740..0x2B81F) ||
        (cp in 0x2B820..0x2CEAF) ||
        (cp in 0xF900..0xFAFF) ||
        (cp in 0x2F800..0x2FA1F)
}
