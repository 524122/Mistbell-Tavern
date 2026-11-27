package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.model.enabledCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 规则包源的模式闸（P3「简洁模式是执行边界」，docs/UI_MODE.md §闸门）。
 *
 * 装饰 [RulePackSource]：规则包能力关闭时发射空表，其余原样透传。挂在 AppContainer 的
 * [com.mistbell.tavern.android.extension.scripting.ScriptRuntimeManager.source] 与
 * RulePackExtension 上之后，脚本加载、提示词块、输入输出正则、包宏、脚本注册的动态
 * 斜杠命令全部顺着既有路径被闸住——脚本卸载走 ScriptRuntimeManager.reconcile 的现有
 * unload（注销沙箱注册与动态斜杠命令），所以从高级切回简洁是响应式的：模式 Flow 一变，
 * 这里的发射值变化，订阅处重新 reconcile 即卸载。
 *
 * null 有两处语义，都表示「等待，不放行也不误判为无包」：
 * - 委托的 packs 为 null（首次加载未完成）：高级模式下透传 null，awaitPacks 的冷启动
 *   等待语义不受影响；
 * - 模式为 null（启动后数据库读回 ui_mode 之前的几毫秒）：发射 null——**模式未知时一律
 *   先不放行**，宁可多等一次数据库读，也绝不让脚本在未知模式下加载执行。
 * 简洁模式发射空表（非 null），钩子按「无规则包」继续。
 *
 * 管理页（RulePackManagerViewModel 读 RulePackRepository/DAO）与快捷回复
 * （QuickReplyRepository 读原始 rulePackCache）不经此闸——闸门只覆盖执行链路。
 *
 * @param delegate 原始启用包缓存（AppContainer 的 rulePackCache；测试注入假实现）
 * @param mode 界面模式（AppContainer 的进程级 StateFlow，**可空**：null=模式未知；测试注入 MutableStateFlow）
 * @param scope 常驻收集 scope（进程生命周期，与缓存语义一致）
 */
class UiModeGatedRulePackSource(
    delegate: RulePackSource,
    mode: StateFlow<UiMode?>,
    scope: CoroutineScope,
) : RulePackSource {
    override val packs: StateFlow<List<EnabledRulePack>?> =
        combine(delegate.packs, mode) { packs, current ->
            when {
                current == null -> null
                enabledCapabilities(current).rulePacksAllowed -> packs
                else -> emptyList()
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)
}
