package com.mistbell.tavern.android.ui.chat

import com.mistbell.tavern.android.data.api.model.SESSION_MODE_CLASSIC
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_GROUP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色点选收敛纯函数 resolveCharacterSelection 的 JVM 单测（契约 6 B'）。
 *
 * toggleCharacter 依赖 Application（AndroidViewModel）无法在 JVM 测，收敛规则已抽为
 * 顶层纯函数：经典模式 =「含且仅含该角色则取消，否则收敛为单选该角色」，绝不再走
 * toggle-off 分支（修复旧实现"点新角色先替换又被 toggle-off 删除 → 清空且无法再选"的
 * 自相矛盾逻辑）；群聊模式 = 多选 toggle + 上限 MAX_SELECTABLE_CHARACTERS。
 */
class ChatSetupToggleTest {
    // ---- 经典模式：换选（收敛为单选新角色） ----

    @Test
    fun `经典模式从无选中到点选收敛为单选`() {
        val result = resolveCharacterSelection(emptySet(), "b", SESSION_MODE_CLASSIC)
        assertEquals(setOf("b"), result)
    }

    @Test
    fun `经典模式点新角色替换旧选中而非清空`() {
        // 旧实现的 bug 场景：已选 {a} 时点 b，先被替换成 {b} 又被 toggle-off 删成空集；
        // 新逻辑必须收敛为单选 {b}
        val result = resolveCharacterSelection(setOf("a"), "b", SESSION_MODE_CLASSIC)
        assertEquals(setOf("b"), result)
    }

    @Test
    fun `经典模式多选中点其一收敛为单选该角色`() {
        // setMode 已把多选收敛为单选，但防御性验证：即使残留多选，经典点选仍收敛
        val result = resolveCharacterSelection(setOf("a", "b", "c"), "c", SESSION_MODE_CLASSIC)
        assertEquals(setOf("c"), result)
    }

    // ---- 经典模式：取消 ----

    @Test
    fun `经典模式已选集恰为该角色时点选取消清空`() {
        val result = resolveCharacterSelection(setOf("a"), "a", SESSION_MODE_CLASSIC)
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `经典模式取消后可重新点选同一角色`() {
        // 取消（清空）→ 再点：收敛为单选，验证不存在"无法再选"的死锁
        val cleared = resolveCharacterSelection(setOf("a"), "a", SESSION_MODE_CLASSIC)
        val reselected = resolveCharacterSelection(cleared, "a", SESSION_MODE_CLASSIC)
        assertEquals(setOf("a"), reselected)
    }

    // ---- 群聊模式：多选 toggle ----

    @Test
    fun `群聊模式未选角色点选加入多选集`() {
        val result = resolveCharacterSelection(setOf("a"), "b", SESSION_MODE_GROUP)
        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun `群聊模式已选角色点选移除且不影响其他选中`() {
        val result = resolveCharacterSelection(setOf("a", "b", "c"), "b", SESSION_MODE_GROUP)
        assertEquals(setOf("a", "c"), result)
    }

    // ---- 群聊模式：上限 ----

    @Test
    fun `群聊模式满员后点新角色被拒绝原集不变`() {
        val full = setOf("a", "b", "c", "d")
        val result = resolveCharacterSelection(full, "e", SESSION_MODE_GROUP)
        // 超上限拒绝：返回与输入相同的集合（ViewModel 据此弹提示），绝不静默挤掉已有选中
        assertEquals(full, result)
        assertTrue(MAX_SELECTABLE_CHARACTERS == 4)
    }

    @Test
    fun `群聊模式满员时点已选角色仍可移除`() {
        // 上限只拦截"新增"，toggle-off 移除不受影响
        val full = setOf("a", "b", "c", "d")
        val result = resolveCharacterSelection(full, "d", SESSION_MODE_GROUP)
        assertEquals(setOf("a", "b", "c"), result)
    }
}
