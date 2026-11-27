package com.mistbell.tavern.android.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.model.UiModeStartupInputs
import com.mistbell.tavern.android.data.model.UiModeStartupResolution
import com.mistbell.tavern.android.data.model.resolveStartupUiMode
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.data.repository.UiModeEcosystemProbe
import com.mistbell.tavern.android.ui.onboarding.ONBOARDING_DONE_KEY
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 升级首启模式选择弹层的判定与落库（P3 第 2 项，docs/UI_MODE.md §5）。
 *
 * 本类保持零 Android 依赖，挂在主导航层判定逻辑可 JVM 单测；[UiModePromptViewModel]
 * 是薄壳（真库读写），两者共用同一份语义：
 * - 全新安装（引导未完成）：不弹窗，把 SIMPLE 落库（兜底——正常路径是引导完成时写，见
 *   OnboardingViewModel.markDone；两处写的是同一个值，判定竞态下殊途同归）；
 * - 升级用户首启：`pendingPrompt` 给出预选项，确认写所选、关掉写预选项，两者都写一次性标记；
 * - 已有显式 ui_mode / 已弹过：不写库、不弹窗。
 */
class UiModePromptCoordinator(
    /** 读三个 settings 标记 + 生态信号（首次判定消费一次） */
    private val inputs: suspend () -> UiModeStartupInputs,
    /** ui_mode 落库 */
    private val writeMode: suspend (UiMode) -> Unit,
    /** 一次性标记落库 */
    private val markPrompted: suspend () -> Unit,
) {
    private val _pendingPrompt = MutableStateFlow<UiMode?>(null)

    /** 非空 = 弹模式选择，值为预选项（默认选中）；确认与关掉都经 [settle] 清空 */
    val pendingPrompt: StateFlow<UiMode?> = _pendingPrompt.asStateFlow()

    /** 启动判定（主导航层创建时一次；「只弹一次」由 ui_mode_prompted 落库保证，跨进程成立） */
    suspend fun resolve() {
        when (val resolution = resolveStartupUiMode(inputs())) {
            is UiModeStartupResolution.Keep -> Unit
            // 全新安装兜底：不弹窗直接落简洁（与引导完成时写的值一致，幂等）
            UiModeStartupResolution.FreshInstallSimple -> writeMode(UiMode.SIMPLE)
            is UiModeStartupResolution.Prompt -> _pendingPrompt.value = resolution.preselect
        }
    }

    /**
     * 结束弹层（确认与关掉共用）：落库 ui_mode 并写一次性标记。
     * 确认传所选模式；关掉传预选项——「关掉 = 保持预选项」，绝不悄悄变成简洁。
     */
    suspend fun settle(mode: UiMode) {
        writeMode(mode)
        markPrompted()
        _pendingPrompt.value = null
    }
}

/**
 * 主导航层的模式选择弹层 ViewModel（形态照 SessionMemoryResetNoticeViewModel：
 * 小 VM 挂 MainScreen，覆盖横竖屏两种导航形态的首页）。
 *
 * 判定在进程内一次性执行（init）：ui_mode / onboarding_done 都没有启动期后台写入者与它竞态
 * （唯一的写入者是引导完成标记，落库后才会进入主导航层；该竞态的兜底结果与意图一致，
 * 见 UiModePromptCoordinator KDoc），读一次即可，不需要常驻观察。
 */
class UiModePromptViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository()

    // AndroidViewModel 自带 getApplication()：直接取宿主应用的容器，不新增 instance 全局读取（计数棘轮）
    private val db = getApplication<TavernApplication>().container.database

    private val coordinator =
        UiModePromptCoordinator(
            inputs = {
                val settingsDao = db.settingsDao()
                UiModeStartupInputs(
                    explicitModeRaw = settingsDao.getValue(SettingsRepository.KEY_UI_MODE),
                    onboardingDone = settingsDao.getValue(ONBOARDING_DONE_KEY) == "1",
                    promptAlreadyShown = settingsDao.getValue(SettingsRepository.KEY_UI_MODE_PROMPTED) == "1",
                    signals = UiModeEcosystemProbe.signals(db),
                )
            },
            writeMode = { repo.setUiMode(it) },
            markPrompted = { repo.markUiModePrompted() },
        )

    /** 非空 = 弹模式选择，值为预选项 */
    val pendingPrompt: StateFlow<UiMode?> = coordinator.pendingPrompt

    init {
        viewModelScope.launch { coordinator.resolve() }
    }

    /** 确认（写所选）或关掉（写预选项）共用出口 */
    fun settle(mode: UiMode) {
        viewModelScope.launch { coordinator.settle(mode) }
    }
}
