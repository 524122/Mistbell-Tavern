package com.mistbell.tavern.android.ui.chat.htmlui

import com.mistbell.tavern.android.extension.TavernEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTML 门面（TavernHelper）聊天事件推送与 generate/substituteParams 的纯函数契约
 * （JVM 可单测；与 BridgeMessageContract 同包同思路：解析/构造收敛为无 Android 依赖的纯函数）。
 *
 * 事件名映射（净室差异，见 docs/SCRIPTING.md）：酒馆助手事件名为下划线风格
 * （message_received 等），本应用内部事件（TavernEvent）为类型化密封类——映射在此收敛，
 * 订阅方（TavernHelper.eventOn）始终使用酒馆侧事件名。
 */
object HtmlFacadeContract {
    // ---- 酒馆助手事件名（白名单常量：__tavernEmit 的事件名无注入面） ----

    const val EVENT_MESSAGE_SENT = "message_sent"
    const val EVENT_MESSAGE_RECEIVED = "message_received"
    const val EVENT_GENERATION_STARTED = "generation_started"
    const val EVENT_GENERATION_ENDED = "generation_ended"
    const val EVENT_CHAT_CHANGED = "chat_changed"

    /**
     * 待推送给 HTML 页的一条聊天事件：name + 载荷 JSON 均为酒馆侧形态；
     * version 由宿主按发射序单调递增（兼作页面侧推送游标的序号）：
     * 每个页面挂载时对齐队列末尾（旧事件不重放——事件是通知而非状态），队列增长时
     * 按 version > 游标 逐条补推（重组合并多条事件也不丢，逐条各推一次）。
     */
    data class HtmlChatEvent(
        val version: Long,
        val name: String,
        val payloadJson: String,
    )

    /**
     * 宿主侧聊天事件队列上限（有界环形）：事件经常驻收集进入队列，页面挂载晚于事件发射时
     * 不重放，队列只服务"挂载期间补推最近事件"，超限丢最旧。
     */
    const val MAX_BUFFERED_CHAT_EVENTS = 64

    /**
     * TavernEvent → 待推送聊天事件（纯映射）：
     * - 仅当前会话的事件放行（其余会话不推送给本页）；
     * - VariablesUpdated 不在此列（走 mvu_variable_update_ended 专属推送，载荷为整棵变量树）；
     * - 载荷含 sessionId，消息类事件再含 messageId（与酒馆助手事件载荷形态对齐的最小集）。
     */
    fun toHtmlChatEvent(
        event: TavernEvent,
        activeSessionId: String,
        version: Long,
    ): HtmlChatEvent? {
        return mapEvent(event)
            ?.takeIf { (_, sessionId) -> sessionId == activeSessionId }
            ?.let { (name, sessionId, messageId) ->
                val payload =
                    buildJsonObject {
                        put("sessionId", sessionId)
                        if (messageId != null) put("messageId", messageId)
                    }
                HtmlChatEvent(version = version, name = name, payloadJson = payload.toString())
            }
    }

    /** TavernEvent → (酒馆事件名, sessionId, messageId)；无映射（变量更新走 MVU 专属推送）返回 null */
    private fun mapEvent(event: TavernEvent): Triple<String, String, String?>? {
        return when (event) {
            is TavernEvent.MessageSent -> Triple(EVENT_MESSAGE_SENT, event.sessionId, event.messageId)
            is TavernEvent.MessageReceived -> Triple(EVENT_MESSAGE_RECEIVED, event.sessionId, event.messageId)
            is TavernEvent.GenerationStarted -> Triple(EVENT_GENERATION_STARTED, event.sessionId, null)
            is TavernEvent.GenerationEnded -> Triple(EVENT_GENERATION_ENDED, event.sessionId, null)
            is TavernEvent.ChatChanged -> Triple(EVENT_CHAT_CHANGED, event.sessionId, null)
            is TavernEvent.VariablesUpdated -> null
        }
    }

    // __tavernEmit 调用前缀（事件名为白名单常量、payload 为 kotlinx 序列化产物，均无注入面）
    private const val EMIT_JS_PREFIX = "window.__tavernEmit && window.__tavernEmit('"

    /**
     * 生成向页面广播聊天事件的 evaluateJavascript 语句。payloadJson 必须以【JS 字符串字面量】
     * 传入（JsonPrimitive 转义包引号）——页面侧 __tavernEmit(name, payloadJson) 会对第二参
     * JSON.parse，传裸对象字面量会 SyntaxError 被吞、监听器回调永不执行
     * （MVU 推送的 jsonString() 包字符串同理）。
     */
    fun emitJs(event: HtmlChatEvent): String = "$EMIT_JS_PREFIX${event.name}', ${JsonPrimitive(event.payloadJson)})"

    // ---- generate 兼容入口的响应 JSON ----

    /** 生成已启动（桥同步返回，页面侧 JSON.parse 后得到 {started:true}） */
    fun okStarted(): String = buildJsonObject { put("started", true) }.toString()

    // ---- substituteParams 输入上限 ----

    /** substituteParams 输入长度上限：超限原样返回（不做宏替换） */
    const val MAX_SUBSTITUTE_INPUT_LENGTH = 20_000

    /**
     * substituteParams 契约（纯函数，便于单测）：输入在 [MAX_SUBSTITUTE_INPUT_LENGTH] 内
     * 才执行 [render]（宏替换）；超限原样返回——超长输入跳过宏引擎的全量扫描。
     */
    fun substituteParamsLimited(
        input: String,
        render: (String) -> String,
    ): String = if (input.length <= MAX_SUBSTITUTE_INPUT_LENGTH) render(input) else input
}
