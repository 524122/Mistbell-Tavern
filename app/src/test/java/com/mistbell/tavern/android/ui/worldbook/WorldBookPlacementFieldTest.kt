package com.mistbell.tavern.android.ui.worldbook

import com.mistbell.tavern.android.data.prompt.WorldBookPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插入位置下拉（酒馆式 9 选项）纯逻辑单测：
 * 6 锚点 + [系统/用户/AI] 插入深度 @D 与存储字段（insertPosition/depth/depthRole）的互转。
 */
class WorldBookPlacementFieldTest {
    @Test
    fun `下拉共9选项且顺序与酒馆一致`() {
        val options = placementOptions()
        assertEquals(9, options.size)
        assertEquals(
            listOf(
                "角色定义前",
                "角色定义后",
                "示例消息前",
                "示例消息后",
                "作者注释前",
                "作者注释后",
                "[系统] 插入深度 @D",
                "[用户] 插入深度 @D",
                "[AI] 插入深度 @D",
            ),
            options.map { it.second },
        )
        assertEquals(9, options.map { it.first }.toSet().size) // 键唯一
    }

    @Test
    fun `选中键锚点走位置而深度大于0走atD档`() {
        assertEquals(
            WorldBookPlacement.POSITION_AFTER_PROMPT,
            placementSelectedKey(WorldBookPlacement.POSITION_AFTER_PROMPT, 0, "system"),
        )
        // depth>0 压过锚点
        assertEquals(
            "at_depth:user",
            placementSelectedKey(WorldBookPlacement.POSITION_AFTER_PROMPT, 3, "user"),
        )
    }

    @Test
    fun `选锚点归零深度且角色不变`() {
        val (pos, depth, role) =
            placementFromSelection(WorldBookPlacement.POSITION_EM_BEFORE, WorldBookPlacement.POSITION_BEFORE_PROMPT, 3, "user")
        assertEquals(WorldBookPlacement.POSITION_EM_BEFORE, pos)
        assertEquals(0, depth)
        assertEquals("user", role)
    }

    @Test
    fun `选atD档缺省深度取酒馆默认4`() {
        val (pos, depth, role) =
            placementFromSelection("at_depth:system", WorldBookPlacement.POSITION_AFTER_PROMPT, 0, "system")
        assertEquals(WorldBookPlacement.POSITION_AFTER_PROMPT, pos) // @D 下锚点不参与语义，保持原值
        assertEquals(4, depth)
        assertEquals("system", role)
    }

    @Test
    fun `选atD档保留已有深度`() {
        val (_, depth, role) =
            placementFromSelection("at_depth:assistant", WorldBookPlacement.POSITION_BEFORE_PROMPT, 3, "system")
        assertEquals(3, depth)
        assertEquals("assistant", role)
    }

    @Test
    fun `互转往返一致`() {
        // 锚点 → 键 → 状态
        val anchorKey = placementSelectedKey(WorldBookPlacement.POSITION_AN_AFTER, 0, "system")
        val (pos, depth, _) = placementFromSelection(anchorKey, WorldBookPlacement.POSITION_AN_AFTER, 0, "system")
        assertEquals(WorldBookPlacement.POSITION_AN_AFTER to 0, pos to depth)
        // @D → 键 → 状态
        val atDepthKey = placementSelectedKey(WorldBookPlacement.POSITION_BEFORE_PROMPT, 5, "user")
        assertEquals("at_depth:user", atDepthKey)
        val (_, depth2, role2) = placementFromSelection(atDepthKey, WorldBookPlacement.POSITION_BEFORE_PROMPT, 5, "user")
        assertEquals(5 to "user", depth2 to role2)
    }
}
