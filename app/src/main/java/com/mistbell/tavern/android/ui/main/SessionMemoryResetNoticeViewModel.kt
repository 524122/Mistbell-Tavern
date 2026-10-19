package com.mistbell.tavern.android.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话内记忆升级清零（SessionMemoryReset）的一次性提示。
 *
 * 持续观察 settings 表的 `session_memory_reset_notice_pending`：值变为 "1" 即弹窗，
 * 用户确认后置 "0"，全生命周期只弹一次。
 *
 * 时序与语义论证：
 * - **必须观察 Flow 而不是启动时读一次**：清零任务（AppContainer 注入，后台协程）可能晚于
 *   UI 加载完成才写 "1"，一次性 getValue 会读到 null 导致本次启动漏弹。observeValue 持续收集，
 *   reset 落地后同一次启动内也会弹出。
 * - **"1" 即"确实清过"**：SessionMemoryReset 只在全部清零步骤成功、且完成标记
 *   `session_memory_reset_v1_done` 提交之后才写 pending=1；任何一步失败都不写，
 *   因此观察到的 "1" 天然意味着"历史记忆已清空"，不会出现"提示了但其实没清"的假象。
 * - **只在用户点击确认后置 "0"**：仅退出应用/旋转重组时标记保留 "1"，下次启动继续提示，
 *   保证用户一定看到这条升级说明。
 * - **防重复弹窗**：showNotice 是唯一的显示状态（Flow → stateIn 单点），对话框 itself
 *   无本地 remember 状态；确认前 Flow 重复发射 "1" 不会产生第二个对话框。
 *
 * 挂在 MainScreen 而非会话列表 ViewModel 上：会话列表的删除逻辑正由并行任务改动，
 * 这里独立成一个小 ViewModel，既不碰它的代码，也能覆盖横竖屏两种导航形态下的首页。
 */
class SessionMemoryResetNoticeViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsDao = TavernApplication.instance.container.database.settingsDao()

    /**
     * 是否弹升级提示：pending 键观察流的派生布尔（"1" → true）。
     * WhileSubscribed 与全仓 ViewModel 的订阅超时同值，对话框重组期间状态不丢。
     */
    val showNotice: StateFlow<Boolean> =
        settingsDao
            .observeValue(KEY_NOTICE_PENDING)
            .map { it == "1" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), false)

    /**
     * 用户确认：置 pending="0" 收起对话框，并写 seen="1"——
     * SessionMemoryReset 的补通知逻辑（DONE 已完成但用户未确认时重挂 pending）据此停止重挂。
     */
    fun dismissNotice() {
        viewModelScope.launch {
            settingsDao.upsert(SettingsEntity(KEY_NOTICE_PENDING, "0"))
            settingsDao.upsert(SettingsEntity(KEY_NOTICE_SEEN, "1"))
        }
    }

    companion object {
        // 与 SessionMemoryReset 写入的占位键逐字一致（该常量在其内部私有，这里不跨层引用）
        private const val KEY_NOTICE_PENDING = "session_memory_reset_notice_pending"
        private const val KEY_NOTICE_SEEN = "session_memory_reset_notice_seen"

        // stateIn 订阅超时：与全仓 ViewModel 的 WhileSubscribed(5000) 同值
        private const val SUBSCRIBE_TIMEOUT_MS = 5000L
    }
}
