package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.entity.QuickReplyEntity
import com.mistbell.tavern.android.data.rulepack.RulePackContent
import com.mistbell.tavern.android.data.rulepack.RulePackQuickReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QuickReplyRepository.aggregate 纯函数聚合的 JVM 单测。 */
class QuickReplyRepositoryTest {
    private fun global(
        id: String,
        label: String,
    ) = QuickReplyEntity(id = id, label = label, action = "send", payload = "p-$id")

    private fun packQr(
        label: String,
        enabled: Boolean = true,
    ) = RulePackQuickReply(label = label, action = "insert", payload = "p-$label", enabled = enabled)

    private fun pack(
        id: String,
        quickReplies: List<RulePackQuickReply>,
    ) = EnabledRulePack(id, "包$id", 100, RulePackContent(quickReplies = quickReplies))

    @Test
    fun `全局在前规则包在后`() {
        val result =
            QuickReplyRepository.aggregate(
                global = listOf(global("g1", "全局一")),
                enabledPacks = listOf(pack("a", listOf(packQr("包一")))),
            )
        assertEquals(listOf("全局一", "包一"), result.map { it.label })
        assertNull(result[0].packName)
        assertEquals("包a", result[1].packName)
    }

    @Test
    fun `包内禁用条目不出现`() {
        val result =
            QuickReplyRepository.aggregate(
                global = emptyList(),
                enabledPacks = listOf(pack("a", listOf(packQr("开"), packQr("关", enabled = false)))),
            )
        assertEquals(listOf("开"), result.map { it.label })
    }

    @Test
    fun `action 归一小写`() {
        val result =
            QuickReplyRepository.aggregate(
                global = emptyList(),
                enabledPacks = listOf(pack("a", listOf(RulePackQuickReply("x", "SEND", "p")))),
            )
        assertEquals("send", result.first().action)
    }

    @Test
    fun `空源聚合为空`() {
        assertTrue(QuickReplyRepository.aggregate(emptyList(), emptyList()).isEmpty())
    }
}
