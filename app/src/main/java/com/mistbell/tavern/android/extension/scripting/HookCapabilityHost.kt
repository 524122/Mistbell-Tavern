package com.mistbell.tavern.android.extension.scripting

/**
 * 扩展宿主钩子（hostHooks）可消费的会话能力面（P3）：消息读/写、生成控制、MVU 变量树。
 *
 * 为什么不直接依赖 HtmlBridgeHost：本包（extension.scripting）被 ui 层引用
 * （HtmlPrereqs 用 ScriptRuntimeManager.jsonString），反向只引 UI 纯数据契约会让依赖
 * 打结；这里收窄为钩子实际需要的最小方法集，方法签名与 [com.mistbell.tavern.android.ui.chat.htmlui.HtmlBridgeHost]
 * 完全同形——ChatViewModel 的 htmlBridgeHost 同时实现两个接口（零重复实现），
 * 既有上限契约（BridgeMessageContract 的 20000 字符/50 条/id 长度）全部原样沿用。
 *
 * 生命周期语义：宿主是**当前聊天页**的受控出口。聊天页不在时（无会话）读侧降级为
 * 空数据、写侧降级为契约错误 JSON——与 HtmlBridgeHost 各默认降级一一对应；
 * 钩子链路（onPrompt/onReply/事件）只在生成/会话活跃期被调用，正常情况宿主恒在场。
 */
interface HookCapabilityHost {
    /** 最近 limit 条消息的 JSON 数组字符串（[{id, role, content}]，只读） */
    fun getChatMessagesJson(limit: Int): String

    /** 最新一条消息 id；空串 = 无消息 */
    fun getLastMessageId(): String

    /** setChatMessages([{id, content, thinking?}]) → {updated:N} 或 {error:...} */
    fun setChatMessagesJson(payloadJson: String): String

    /** createChatMessage({role, content}) → {id:...} 或 {error:...} */
    fun createChatMessageJson(payloadJson: String): String

    /** deleteChatMessages([id,...]) → {deleted:N} 或 {error:...} */
    fun deleteChatMessagesJson(idsJson: String): String

    /** 当前会话 MVU stat_data（JSON 对象字符串，无则 "{}"） */
    fun getMvuVariablesJson(): String

    /** 顶层键浅合并写入 stat_data 并广播变量更新 */
    fun insertMvuVariablesJson(json: String)

    /** generate 兼容入口：{started:true} 或 {error:...}（走 launchGeneration 状态机） */
    fun triggerGenerateJson(): String

    /** 停止当前生成（与输入框停止同一通道）；无生成任务时空操作 */
    fun stopGeneration()
}

/**
 * 当前聊天页能力宿主的进程级挂载点（P3）。
 *
 * 扩展钩子运行时是应用级单例，而消息/生成/MVU 出口活在 ChatViewModel（每聊天页一个）；
 * 页面 init 挂载、onCleared 摘除（与 TavernEventBus 的对象单例先例同型）。
 * null = 无聊天页在场，[ExtensionHookApi] 据此走降级出口。
 */
object ActiveChatHookBridge {
    @Volatile
    var host: HookCapabilityHost? = null
}
