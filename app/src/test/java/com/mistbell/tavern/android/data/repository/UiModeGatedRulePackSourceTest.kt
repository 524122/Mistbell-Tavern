package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.rulepack.RulePackContent
import com.mistbell.tavern.android.data.rulepack.RulePackPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 规则包源模式闸（P3 执行边界）单测：简洁模式发空表、切回高级恢复、
 * 委托的 null（首次加载未完成）在高级模式原样透传（awaitPacks 冷启动等待语义不破坏）、
 * 模式未知（null）时一律发射 null 先不放行。
 */
class UiModeGatedRulePackSourceTest {
    private val mode = MutableStateFlow<UiMode?>(UiMode.ADVANCED)
    private val delegatePacks = MutableStateFlow<List<EnabledRulePack>?>(null)

    private val source =
        UiModeGatedRulePackSource(
            delegate =
                object : RulePackSource {
                    override val packs: kotlinx.coroutines.flow.StateFlow<List<EnabledRulePack>?> = delegatePacks
                },
            mode = mode,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun packOf(id: String) =
        EnabledRulePack(
            id = id,
            name = "包$id",
            loadingOrder = 100,
            content = RulePackContent(prompts = listOf(RulePackPrompt(name = "p", content = "内容$id"))),
        )

    @Test
    fun `简洁模式发空表 切回高级恢复`() {
        delegatePacks.value = listOf(packOf("a"), packOf("b"))
        assertEquals(listOf("a", "b"), source.packs.value?.map { it.id })

        mode.value = UiMode.SIMPLE
        assertEquals(emptyList<EnabledRulePack>(), source.packs.value)

        mode.value = UiMode.ADVANCED
        assertEquals(listOf("a", "b"), source.packs.value?.map { it.id })
    }

    @Test
    fun `高级模式下委托未加载完成时透传 null`() {
        // 首次加载未完成：awaitPacks 靠 null 等待，不得被闸门吞成空表
        delegatePacks.value = null
        assertNull(source.packs.value)

        delegatePacks.value = listOf(packOf("a"))
        assertEquals(listOf("a"), source.packs.value?.map { it.id })
    }

    @Test
    fun `简洁模式下委托未加载完成时按空表发射`() {
        delegatePacks.value = null
        mode.value = UiMode.SIMPLE
        assertEquals(emptyList<EnabledRulePack>(), source.packs.value)
    }

    @Test
    fun `简洁模式下装载完成的包在切回简洁时清空`() {
        mode.value = UiMode.SIMPLE
        delegatePacks.value = listOf(packOf("a"))
        assertEquals(emptyList<EnabledRulePack>(), source.packs.value)
    }

    @Test
    fun `模式未知时即使委托已有包也发射 null 等待`() {
        delegatePacks.value = listOf(packOf("a"))
        mode.value = null
        assertNull("模式未知应发射 null（awaitPacks 等待而非按空表放行）", source.packs.value)

        // 数据库读回模式后立即按真实模式放行/拦截
        mode.value = UiMode.SIMPLE
        assertEquals(emptyList<EnabledRulePack>(), source.packs.value)
        mode.value = UiMode.ADVANCED
        assertEquals(listOf("a"), source.packs.value?.map { it.id })
    }
}
