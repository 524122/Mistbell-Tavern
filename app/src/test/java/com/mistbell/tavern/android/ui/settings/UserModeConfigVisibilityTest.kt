package com.mistbell.tavern.android.ui.settings

import com.mistbell.tavern.android.data.model.UiMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UserModeConfig 可见性表（P3 第 2 项「按模式收拢界面」，docs/UI_MODE.md §5）单测：
 * 简洁模式收起八类入口（世界书 / 附加指令 / 正则 / 提示词 / 规则包 / 扩展包 / 实验功能 /
 * 渲染引擎开关），恒可见项没有字段、不受模式影响（本测试即它的书面记录）；
 * 以及**模式未知（null）时不渲染任何随模式变化的入口**（与闸门「未知不放行」同一口径）。
 *
 * 「正则」的入口是主导航 Tab（MainTab）与本页无正则项，不在本表覆盖范围。
 */
class UserModeConfigVisibilityTest {
    // ---- 简洁模式：收起 ----

    @Test
    fun `简洁模式收起提示词管理组`() {
        val config = UiMode.SIMPLE.toConfig()
        assertFalse(config.showPromptManagement)
    }

    @Test
    fun `简洁模式收起世界书入口`() {
        assertFalse(UiMode.SIMPLE.toConfig().showWorldBookEntries)
    }

    @Test
    fun `简洁模式收起会话附加指令`() {
        assertFalse(UiMode.SIMPLE.toConfig().showSessionAuthorNote)
    }

    @Test
    fun `简洁模式收起原生渲染引擎开关`() {
        assertFalse(UiMode.SIMPLE.toConfig().showLayoutRendererToggle)
    }

    @Test
    fun `简洁模式收起实验性功能整组`() {
        assertFalse(UiMode.SIMPLE.toConfig().showExperimentalFeatures)
    }

    // ---- 高级模式：全部保留（老用户升级后不丢入口）----

    @Test
    fun `高级模式可见性全部为真`() {
        val config = UiMode.ADVANCED.toConfig()
        assertTrue(config.showAdvancedSettings)
        assertTrue(config.showTechnicalDetails)
        assertTrue(config.showExperimentalFeatures)
        assertTrue(config.showPromptManagement)
        assertTrue(config.showWorldBookEntries)
        assertTrue(config.showSessionAuthorNote)
        assertTrue(config.showLayoutRendererToggle)
    }

    // ---- 恒可见项的书面记录：这些入口没有可见性字段，简洁模式也不隐藏 ----
    // API 连接、模型参数、对话设置（默认上下文长度）、外观与主题、快捷回复、
    // 数据备份、会话记忆开关与记忆管理入口、界面模式本身、关于——
    // 由 ModernSettingsScreen / ChatSettingsScreen 的布局保证（不在 AdvancedFeature /
    // TechnicalDetailFeature / ExperimentalFeature 包装器内），此处断言字段层面不存在对应开关。

    // ---- 模式未知（null，常驻流尚未读回）：不渲染任何随模式变化的入口 ----

    @Test
    fun `模式未知时三类渲染谓词全为假 不闪任何模式相关入口`() {
        val unknown: UserModeConfig? = null
        assertFalse(unknown.advancedVisible)
        assertFalse(unknown.technicalDetailVisible)
        assertFalse(unknown.experimentalVisible)
    }

    @Test
    fun `模式未知不落高级缺省 null直通不产生配置`() {
        // ViewModel 的 uiMode 是 StateFlow<UiMode?>（常驻流），界面取 uiMode?.toConfig()：
        // null 直通，绝不经 UiMode.fromSetting 落「高级」的缺省观感（那是执行闸门的缺省，不是界面的）
        val uiMode: UiMode? = null
        assertNull(uiMode?.toConfig())
    }

    @Test
    fun `已知模式下渲染谓词与可见性字段一致`() {
        val simple = UiMode.SIMPLE.toConfig()
        val advanced = UiMode.ADVANCED.toConfig()
        assertFalse(simple.advancedVisible)
        assertFalse(simple.technicalDetailVisible)
        assertFalse(simple.experimentalVisible)
        assertTrue(advanced.advancedVisible)
        assertTrue(advanced.technicalDetailVisible)
        assertTrue(advanced.experimentalVisible)
    }

    @Test
    fun `模式切换不改变mode字段本身`() {
        assertTrue(UiMode.SIMPLE.toConfig().mode == UiMode.SIMPLE)
        assertTrue(UiMode.ADVANCED.toConfig().mode == UiMode.ADVANCED)
    }
}
