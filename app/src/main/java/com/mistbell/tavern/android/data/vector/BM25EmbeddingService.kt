package com.mistbell.tavern.android.data.vector

import kotlin.math.ln

/**
 * BM25 算法实现的 Embedding 服务
 *
 * 在没有真实 embedding API 时使用传统的 BM25 算法进行文本相似度计算
 * 通过生成基于词频的伪向量来模拟 embedding
 */
class BM25EmbeddingService : EmbeddingService {

    companion object {
        private const val DIMENSION = 512  // 伪向量维度
        private const val K1 = 1.5f  // BM25 参数 k1
        private const val B = 0.75f  // BM25 参数 b
    }

    // 文档集合（用于计算 IDF）
    private val documentCollection = mutableListOf<List<String>>()
    private val idfCache = mutableMapOf<String, Double>()
    private var avgDocLength = 0.0

    override suspend fun embed(text: String): FloatArray {
        val tokens = tokenize(text)

        // 添加到文档集合（用于后续的 IDF 计算）
        synchronized(documentCollection) {
            documentCollection.add(tokens)
            updateStatistics()
        }

        // 生成基于 TF-IDF 的伪向量
        return generatePseudoVector(tokens)
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override fun getDimension(): Int = DIMENSION

    /**
     * 分词（支持中英文）
     */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()

        // 提取中文字符（单字或双字）
        val chinesePattern = Regex("[一-鿿]{1,2}")
        chinesePattern.findAll(text).forEach { match ->
            tokens.add(match.value)
        }

        // 提取英文单词
        val englishPattern = Regex("[a-zA-Z]{2,}")
        englishPattern.findAll(text.lowercase()).forEach { match ->
            tokens.add(match.value)
        }

        // 提取数字
        val numberPattern = Regex("\\d+")
        numberPattern.findAll(text).forEach { match ->
            tokens.add(match.value)
        }

        return tokens
    }

    /**
     * 更新文档统计信息
     */
    private fun updateStatistics() {
        if (documentCollection.isEmpty()) return

        // 计算平均文档长度
        avgDocLength = documentCollection.map { it.size }.average()

        // 清除 IDF 缓存（需要重新计算）
        idfCache.clear()
    }

    /**
     * 计算词的 IDF（逆文档频率）
     */
    private fun calculateIDF(term: String): Double {
        return idfCache.getOrPut(term) {
            val docCount = documentCollection.size.toDouble()
            val termDocCount = documentCollection.count { doc -> term in doc }.toDouble()

            // IDF = log((N - df + 0.5) / (df + 0.5) + 1)
            ln((docCount - termDocCount + 0.5) / (termDocCount + 0.5) + 1.0)
        }
    }

    /**
     * 计算词的 TF（词频）
     */
    private fun calculateTF(term: String, tokens: List<String>, docLength: Int): Double {
        val termFreq = tokens.count { it == term }.toDouble()

        // BM25 TF 公式
        // TF = (f * (k1 + 1)) / (f + k1 * (1 - b + b * (docLength / avgDocLength)))
        return (termFreq * (K1 + 1)) /
               (termFreq + K1 * (1 - B + B * (docLength / avgDocLength)))
    }

    /**
     * 生成伪向量
     *
     * 使用特征哈希将词的 TF-IDF 值映射到固定维度的向量
     */
    private fun generatePseudoVector(tokens: List<String>): FloatArray {
        val vector = FloatArray(DIMENSION) { 0f }
        val uniqueTokens = tokens.distinct()

        if (uniqueTokens.isEmpty()) return vector

        uniqueTokens.forEach { term ->
            val tf = calculateTF(term, tokens, tokens.size)
            val idf = calculateIDF(term)
            val tfidf = (tf * idf).toFloat()

            // 使用特征哈希映射到向量的多个位置（减少冲突）
            val hash1 = term.hashCode().mod(DIMENSION)
            val hash2 = (term.hashCode() * 31).mod(DIMENSION)
            val hash3 = (term.hashCode() * 37).mod(DIMENSION)

            vector[hash1] += tfidf * 0.5f
            vector[hash2] += tfidf * 0.3f
            vector[hash3] += tfidf * 0.2f
        }

        // 归一化向量
        return normalizeVector(vector)
    }

    /**
     * 向量归一化
     */
    private fun normalizeVector(vector: FloatArray): FloatArray {
        val magnitude = kotlin.math.sqrt(vector.map { it * it }.sum())

        if (magnitude == 0f) return vector

        return FloatArray(vector.size) { i -> vector[i] / magnitude }
    }
}
