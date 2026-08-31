package com.mistbell.tavern.android.ui.chatlist

import com.mistbell.tavern.android.data.api.model.Character
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ChatListItem 相等性回归测试（ROADMAP M1-3）。
 *
 * 0.2.2 曾手写 equals 只比较部分字段，导致角色改名/换头像/换参与者后
 * 聊天列表不刷新（陈旧数据）。当前实现已删除手写覆盖、回归 data class
 * 全字段比较——本测试锁定"每一个展示字段都必须参与相等性判断"，
 * 防止同类"性能优化"再次引入该缺陷。
 */
class ChatListItemEqualsTest {

    private fun baseItem() = ChatListItem(
        sessionId = "s-1",
        characterId = "c-1",
        sessionTitle = "标题",
        characterName = "旧名字",
        characterColor = "#6C5CE7",
        characterAvatarData = null,
        participantCharacters = emptyList(),
        lastMessage = "你好",
        lastMessageTime = "刚刚",
        unreadCount = 0,
        isOnline = false,
        isPinned = false,
        isMuted = false,
        lastMessageSender = ""
    )

    private fun assertChangeDetected(mutate: (ChatListItem) -> ChatListItem) {
        val a = baseItem()
        val b = mutate(a)
        assertNotEquals("字段变化后必须不相等（否则列表不会刷新）", a, b)
        // 反向同样成立
        assertNotEquals(b, a)
    }

    @Test
    fun `完全相同的两条数据相等`() {
        assertEquals(baseItem(), baseItem())
        assertEquals(baseItem().hashCode(), baseItem().hashCode())
    }

    @Test
    fun `角色名变化参与相等性`() =
        assertChangeDetected { it.copy(characterName = "新名字") }

    @Test
    fun `会话标题变化参与相等性`() =
        assertChangeDetected { it.copy(sessionTitle = "新标题") }

    @Test
    fun `头像数据变化参与相等性`() =
        assertChangeDetected { it.copy(characterAvatarData = "data:image/png;base64,xxx") }

    @Test
    fun `参与角色变化参与相等性`() =
        assertChangeDetected {
            it.copy(participantCharacters = listOf(Character(id = "c-2", name = "同伴")))
        }

    @Test
    fun `最后消息发送者变化参与相等性`() =
        assertChangeDetected { it.copy(lastMessageSender = "我") }

    @Test
    fun `最后消息内容与时间变化参与相等性`() {
        assertChangeDetected { it.copy(lastMessage = "新消息") }
        assertChangeDetected { it.copy(lastMessageTime = "5分钟前") }
    }

    @Test
    fun `未读数与置顶静音变化参与相等性`() {
        assertChangeDetected { it.copy(unreadCount = 3) }
        assertChangeDetected { it.copy(isPinned = true) }
        assertChangeDetected { it.copy(isMuted = true) }
    }
}
