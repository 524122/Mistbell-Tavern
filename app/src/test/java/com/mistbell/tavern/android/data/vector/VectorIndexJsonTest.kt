package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引线格式（VectorIndexJson）契约：序列化/解析往返、未知字段容忍、坏内容拒绝。
 * 这是备份 zip 里 vector_store.json 条目与两种存储引擎（JSON / sqlite-vec）之间的共享格式。
 */
class VectorIndexJsonTest {
    private fun entry(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) = VectorStore.VectorEntry(id, vector, metadata)

    @Test
    fun `序列化后解析还原条目内容`() {
        val entries =
            listOf(
                entry(
                    "vec_1",
                    floatArrayOf(0.5f, -1.25f, 3.4028235E38f),
                    mapOf(
                        "owner_id" to "owner",
                        "session_id" to "s1",
                        "message_id" to "m1",
                        "content" to "蒋珞要求 user 放学后带芋泥口味的千层",
                        "created_at" to "1770000000000",
                    ),
                ),
                entry("vec_2", floatArrayOf(0f), mapOf("content" to "第二条")),
            )

        val decoded = decodeVectorIndex(encodeVectorIndex(entries))

        assertNotNull(decoded)
        assertEquals(2, decoded!!.size)
        assertEquals("vec_1", decoded[0].id)
        assertTrue(decoded[0].vector.contentEquals(floatArrayOf(0.5f, -1.25f, 3.4028235E38f)))
        assertEquals("蒋珞要求 user 放学后带芋泥口味的千层", decoded[0].metadata["content"])
        // 线格式只有字符串映射：数值型元数据按 toString 往返（与历史格式一致）
        assertEquals("1770000000000", decoded[0].metadata["created_at"])
        assertEquals("vec_2", decoded[1].id)
    }

    @Test
    fun `空索引往返为空的条目列表`() {
        val decoded = decodeVectorIndex(encodeVectorIndex(emptyList()))

        assertNotNull(decoded)
        assertTrue(decoded!!.isEmpty())
    }

    @Test
    fun `坏内容解析失败返回null`() {
        assertNull(decodeVectorIndex("not json"))
        assertNull(decodeVectorIndex("{\"vectors\":[{\"id\":1}]}"))
        assertNull(decodeVectorIndex(""))
    }

    @Test
    fun `未知字段被容忍_旧版本读到新格式不炸`() {
        val content =
            """
            {"vectors":[{"id":"v1","vector":[0.1,0.2],"metadata":{"k":"v"},"future_field":true}],
             "futureTopLevel":"x"}
            """.trimIndent()

        val decoded = decodeVectorIndex(content)

        assertNotNull(decoded)
        assertEquals("v1", decoded!![0].id)
        assertEquals("v", decoded[0].metadata["k"])
    }

    @Test
    fun `isValidVectorIndex与解析结果一致`() {
        assertTrue(isValidVectorIndex(encodeVectorIndex(listOf(entry("v", floatArrayOf(1f), emptyMap())))))
        assertTrue(isValidVectorIndex("{\"vectors\":[]}"))
        assertTrue(!isValidVectorIndex("broken"))
    }
}
