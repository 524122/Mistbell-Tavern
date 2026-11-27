package com.mistbell.tavern.android.ui.main

import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.model.UiModeEcosystemSignals
import com.mistbell.tavern.android.data.model.UiModeStartupInputs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 升级首启模式选择弹层的判定与落库（P3 第 2 项，docs/UI_MODE.md §5）单测。
 * 替身是内存 settings map：inputs 读它，writeMode / markPrompted 写它——
 * 「只弹一次」依赖标记真实回写，替身必须与生产同语义（写库后同 map 再读）。
 */
class UiModePromptCoordinatorTest {
    companion object {
        private const val KEY_UI_MODE = "ui_mode"
        private const val KEY_PROMPTED = "ui_mode_prompted"
        private const val KEY_ONBOARDING = "onboarding_done"
    }

    /** 内存 settings 表 + 生态信号旋钮：与生产键值语义一一对应 */
    private class FakeStore {
        val settings = linkedMapOf<String, String>()
        var signals: UiModeEcosystemSignals = UiModeEcosystemSignals.NONE

        fun readInputs(): UiModeStartupInputs =
            UiModeStartupInputs(
                explicitModeRaw = settings[KEY_UI_MODE],
                onboardingDone = settings[KEY_ONBOARDING] == "1",
                promptAlreadyShown = settings[KEY_PROMPTED] == "1",
                signals = signals,
            )
    }

    private lateinit var store: FakeStore
    private lateinit var coordinator: UiModePromptCoordinator

    @Before
    fun setUp() {
        store = FakeStore()
        coordinator =
            UiModePromptCoordinator(
                inputs = { store.readInputs() },
                writeMode = { mode -> store.settings[KEY_UI_MODE] = mode.storedValue },
                markPrompted = { store.settings[KEY_PROMPTED] = "1" },
            )
    }

    private fun upgradedUser(signals: UiModeEcosystemSignals = UiModeEcosystemSignals.NONE) {
        store.settings[KEY_ONBOARDING] = "1"
        store.signals = signals
    }

    /** 信号构造（四参具名，测试里一行放不下全字段） */
    private fun signals(
        regex: Boolean = false,
        packs: Boolean = false,
        html: Boolean = false,
        cards: Boolean = false,
    ): UiModeEcosystemSignals =
        UiModeEcosystemSignals(
            hasRegexRules = regex,
            hasEnabledPacks = packs,
            hasHtmlUiEnabled = html,
            hasImportedTavernCard = cards,
        )

    // ---- 升级首启 ----

    @Test
    fun `升级首启弹窗 无信号预选简洁`() {
        runBlocking {
            upgradedUser()
            coordinator.resolve()
            assertEquals(UiMode.SIMPLE, coordinator.pendingPrompt.value)
        }
    }

    @Test
    fun `升级首启有生态信号预选高级`() {
        runBlocking {
            upgradedUser(signals(regex = true))
            coordinator.resolve()
            assertEquals(UiMode.ADVANCED, coordinator.pendingPrompt.value)
        }
    }

    @Test
    fun `确认写所选并写标记`() {
        runBlocking {
            upgradedUser()
            coordinator.resolve()
            // 用户把预选的简洁改成了高级再点「完成」
            coordinator.settle(UiMode.ADVANCED)
            assertEquals("advanced", store.settings[KEY_UI_MODE])
            assertEquals("1", store.settings[KEY_PROMPTED])
            assertNull(coordinator.pendingPrompt.value)
        }
    }

    @Test
    fun `关掉等于保持预选项 即使预选是简洁`() {
        runBlocking {
            upgradedUser() // 无信号 → 预选简洁
            coordinator.resolve()
            // 点外部 / 返回：落库预选项 simple（而不是第 1 项的缺省 advanced），并写标记
            coordinator.settle(coordinator.pendingPrompt.value!!)
            assertEquals("simple", store.settings[KEY_UI_MODE])
            assertEquals("1", store.settings[KEY_PROMPTED])
            assertNull(coordinator.pendingPrompt.value)
        }
    }

    @Test
    fun `预选高级时关掉保持高级`() {
        runBlocking {
            upgradedUser(signals(cards = true))
            coordinator.resolve()
            coordinator.settle(coordinator.pendingPrompt.value!!)
            assertEquals("advanced", store.settings[KEY_UI_MODE])
            assertEquals("1", store.settings[KEY_PROMPTED])
        }
    }

    @Test
    fun `只弹一次 settle后再判定不再弹`() {
        runBlocking {
            upgradedUser()
            coordinator.resolve()
            coordinator.settle(UiMode.SIMPLE)
            // 同一次会话内（或下次启动重新 resolve，inputs 反映已落库的标记）：不再弹
            coordinator.resolve()
            assertNull(coordinator.pendingPrompt.value)
            assertEquals("simple", store.settings[KEY_UI_MODE])
        }
    }

    // ---- 全新安装 ----

    @Test
    fun `全新安装不弹窗直接落简洁且不写标记`() {
        runBlocking {
            // 引导未完成（标记缺失）；即使角色已导入（信号为真）也不弹
            store.signals = signals(cards = true)
            coordinator.resolve()
            assertNull(coordinator.pendingPrompt.value)
            assertEquals("simple", store.settings[KEY_UI_MODE])
            assertNull(store.settings[KEY_PROMPTED])
        }
    }

    // ---- 显式设置过 / 引导完成但弹过 ----

    @Test
    fun `显式设置过不弹窗不写库`() {
        runBlocking {
            upgradedUser()
            store.settings[KEY_UI_MODE] = "advanced"
            coordinator.resolve()
            assertNull(coordinator.pendingPrompt.value)
            // Keep 分支不落库不写标记
            assertNull(store.settings[KEY_PROMPTED])
        }
    }

    @Test
    fun `弹过但值缺失时保守按高级不再弹`() {
        runBlocking {
            upgradedUser()
            store.settings[KEY_PROMPTED] = "1"
            coordinator.resolve()
            assertNull(coordinator.pendingPrompt.value)
            assertNull(store.settings[KEY_UI_MODE])
        }
    }

    @Test
    fun `pendingPrompt是可收集的StateFlow`() {
        runBlocking {
            upgradedUser()
            coordinator.resolve()
            assertEquals(UiMode.SIMPLE, coordinator.pendingPrompt.first())
        }
    }
}
