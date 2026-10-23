package com.mistbell.tavern.android.extension

/**
 * 扩展事件（对标酒馆 event_types）。
 *
 * 本次最小核心只覆盖聊天主链路的五个事件；新增事件在此追加类型即可，
 * 发射点与订阅方互不影响。
 */
sealed interface TavernEvent {
    /** 用户消息已落库（sendMessage 本地保存成功后） */
    data class MessageSent(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val messageId: String,
    ) : TavernEvent

    /** AI 回复已落库（persistAssistantReply 成功后；含群聊与取消部分回复以外的成功路径） */
    data class MessageReceived(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val messageId: String,
    ) : TavernEvent

    /** LLM 请求即将发出（提示词装配完成之后） */
    data class GenerationStarted(
        val sessionId: String,
    ) : TavernEvent

    /** LLM 生成流程结束（成功/失败/取消均发射；与 GenerationStarted 严格配对） */
    data class GenerationEnded(
        val sessionId: String,
    ) : TavernEvent

    /** 当前观察的会话切换（selectCharacter / loadSession / 进入聊天页共用入口） */
    data class ChatChanged(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
    ) : TavernEvent

    /** MVU 变量更新完成（MvuExtension 在回复落库前应用 UpdateVariable patch 后发射） */
    data class VariablesUpdated(
        val sessionId: String,
        val version: Long,
    ) : TavernEvent
}
