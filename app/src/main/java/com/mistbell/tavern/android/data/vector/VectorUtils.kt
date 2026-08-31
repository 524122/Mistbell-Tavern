package com.mistbell.tavern.android.data.vector

import kotlin.math.sqrt

/**
 * 向量工具类
 *
 * 提供向量计算相关的工具方法
 */
object VectorUtils {
    /**
     * 计算两个向量的余弦相似度
     *
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 相似度分数 (0-1)，1 表示完全相似，0 表示完全不相似
     */
    fun cosineSimilarity(
        vec1: FloatArray,
        vec2: FloatArray,
    ): Float {
        require(vec1.size == vec2.size) {
            "Vector dimensions must match: ${vec1.size} vs ${vec2.size}"
        }

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            normA += vec1[i] * vec1[i]
            normB += vec2[i] * vec2[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator == 0.0) return 0f

        return (dotProduct / denominator).toFloat()
    }

    /**
     * 计算欧氏距离
     */
    fun euclideanDistance(
        vec1: FloatArray,
        vec2: FloatArray,
    ): Float {
        require(vec1.size == vec2.size) {
            "Vector dimensions must match"
        }

        var sum = 0.0
        for (i in vec1.indices) {
            val diff = vec1[i] - vec2[i]
            sum += diff * diff
        }

        return sqrt(sum).toFloat()
    }

    /**
     * 向量归一化
     */
    fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() })
        if (norm == 0.0) return vector

        return FloatArray(vector.size) { i ->
            (vector[i] / norm).toFloat()
        }
    }
}
