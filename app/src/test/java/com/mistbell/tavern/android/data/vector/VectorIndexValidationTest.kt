package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份恢复写入向量索引前的校验（`vector_store.json` 是召回的唯一权威来源）。
 *
 * 索引换错一次，向量召回在用户察觉前就一直返回空——所以恢复必须"先验后写"。
 */
class VectorIndexValidationTest {
    @Test
    fun `合法索引通过校验`() {
        assertTrue(InMemoryVectorStore.isValidIndex("""{"vectors":[]}"""))
        assertTrue(
            InMemoryVectorStore.isValidIndex(
                """{"vectors":[{"id":"v1","vector":[0.1,0.2],"metadata":{"owner_id":"u1"}}]}""",
            ),
        )
    }

    @Test
    fun `非 JSON 内容被拒绝`() {
        assertFalse(InMemoryVectorStore.isValidIndex("not a json at all"))
        assertFalse(InMemoryVectorStore.isValidIndex(""))
    }

    @Test
    fun `缺少vectors字段的 JSON 被拒绝`() {
        assertFalse(InMemoryVectorStore.isValidIndex("""{"foo":1}"""))
    }

    @Test
    fun `条目结构不符被拒绝`() {
        // vector 必须是数值数组；写成字符串会解析失败
        assertFalse(InMemoryVectorStore.isValidIndex("""{"vectors":[{"id":"v1","vector":"x","metadata":{}}]}"""))
    }
}
