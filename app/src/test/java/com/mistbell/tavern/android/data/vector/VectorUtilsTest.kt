package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VectorUtils 纯函数单元测试（ROADMAP M2-2 首批）。
 * 重点覆盖审查指出的边界：零向量、维度不匹配。
 */
class VectorUtilsTest {
    @Test
    fun `相同向量余弦相似度为1`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, VectorUtils.cosineSimilarity(v, v.copyOf()), 1e-6f)
    }

    @Test
    fun `正交向量余弦相似度为0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, VectorUtils.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `反方向向量余弦相似度为负1`() {
        val a = floatArrayOf(1f, 1f)
        val b = floatArrayOf(-1f, -1f)
        assertEquals(-1f, VectorUtils.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `零向量返回0而不是NaN`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val other = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, VectorUtils.cosineSimilarity(zero, other), 1e-6f)
        assertEquals(0f, VectorUtils.cosineSimilarity(zero, zero.copyOf()), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `维度不匹配抛出IllegalArgumentException`() {
        VectorUtils.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f))
    }

    @Test
    fun `欧氏距离基本性质`() {
        assertEquals(0f, VectorUtils.euclideanDistance(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)), 1e-6f)
        assertEquals(5f, VectorUtils.euclideanDistance(floatArrayOf(0f, 0f), floatArrayOf(3f, 4f)), 1e-6f)
    }

    @Test
    fun `归一化后模长为1`() {
        val v = floatArrayOf(3f, 4f)
        val n = VectorUtils.normalize(v)
        assertEquals(1.0, VectorUtils.euclideanDistance(n, floatArrayOf(0f, 0f)).toDouble(), 1e-6)
    }

    @Test
    fun `零向量归一化原样返回`() {
        val zero = floatArrayOf(0f, 0f)
        assertTrue(VectorUtils.normalize(zero).contentEquals(zero))
    }
}
