package com.mistbell.tavern.android.ui.onboarding

import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启三屏（P3 第 4 项，docs/UI_MODE.md「首启与默认层」）的纯规则 JVM 单测。
 *
 * OnboardingViewModel 本体依赖 Application（AndroidViewModel）与真库，无法在 JVM 直测
 * （仓库惯例：逻辑抽零依赖件，VM 薄壳——见 ChatSetupToggleTest / UiModePromptCoordinatorTest）。
 * 这里覆盖：
 * - 三屏的推进与封顶（欢迎 → 选角色 → 连接 AI，最后一屏由「进入应用」结束）；
 * - 「稍后再选」的软性语义：没选角色就不落库（shouldInstallBuiltIns）；
 * - 引导完成的落库内容不变：onboarding_done 标记 + ui_mode = simple（全新安装默认简洁）。
 * 落库行为本体（选了角色 → 内置角色落库、幂等、不重复）见 BuiltInCharactersTest。
 */
class OnboardingFlowTest {
    // ---- 三屏推进 ----

    @Test
    fun `三屏按欢迎选角色连接AI推进`() {
        assertEquals(OnboardingFlow.STEP_CHARACTER, OnboardingFlow.next(OnboardingFlow.STEP_WELCOME))
        assertEquals(OnboardingFlow.STEP_API, OnboardingFlow.next(OnboardingFlow.STEP_CHARACTER))
        assertEquals(2, OnboardingFlow.STEP_API)
    }

    @Test
    fun `最后一屏推进封顶不再前进`() {
        assertEquals(OnboardingFlow.STEP_API, OnboardingFlow.next(OnboardingFlow.STEP_API))
    }

    // ---- 「稍后再选」：不选角色不落库 ----

    @Test
    fun `选了角色才安装内置角色`() {
        assertTrue(OnboardingFlow.shouldInstallBuiltIns("builtin_aria"))
        assertTrue(OnboardingFlow.shouldInstallBuiltIns("builtin_chengye"))
    }

    @Test
    fun `没选角色不写任何数据`() {
        assertFalse(OnboardingFlow.shouldInstallBuiltIns(null))
    }

    // ---- 引导完成的落库内容（保持 P3 第 2 项语义）----

    @Test
    fun `引导完成写onboarding_done与ui_mode_simple`() {
        val writes = OnboardingCompletion.settingsWrites()
        assertEquals(2, writes.size)
        assertEquals(ONBOARDING_DONE_KEY to "1", writes[0])
        assertEquals(SettingsRepository.KEY_UI_MODE to UiMode.SIMPLE.storedValue, writes[1])
    }
}
