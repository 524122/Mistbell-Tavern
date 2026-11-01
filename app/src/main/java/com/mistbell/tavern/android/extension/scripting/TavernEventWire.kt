package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.extension.TavernEvent

/**
 * TavernEvent → JS 事件名/载荷 JSON 的统一映射（规则包与扩展包两个脚本运行时共用）。
 *
 * 事件名白名单即 [TavernHostApi.SUPPORTED_EVENTS]（六事件）；载荷为最小集
 * `{sessionId, characterId?}`（variables:updated 另带 version），手工拼 JSON
 * 避免经 Rhino 对象映射（ScriptRuntimeManager 先例）。
 */
internal object TavernEventWire {
    fun nameOf(event: TavernEvent): String =
        when (event) {
            is TavernEvent.MessageSent -> "message:sent"
            is TavernEvent.MessageReceived -> "message:received"
            is TavernEvent.GenerationStarted -> "generation:started"
            is TavernEvent.GenerationEnded -> "generation:ended"
            is TavernEvent.ChatChanged -> "chat:changed"
            is TavernEvent.VariablesUpdated -> "variables:updated"
        }

    fun payloadOf(event: TavernEvent): String =
        when (event) {
            is TavernEvent.MessageSent -> payload(event.sessionId)
            is TavernEvent.MessageReceived -> payload(event.sessionId)
            is TavernEvent.GenerationStarted -> payload(event.sessionId)
            is TavernEvent.GenerationEnded -> payload(event.sessionId)
            is TavernEvent.ChatChanged -> payload(event.sessionId, event.characterId)
            is TavernEvent.VariablesUpdated ->
                "{\"sessionId\":${ScriptRuntimeManager.jsonString(event.sessionId)},\"version\":${event.version}}"
        }

    private fun payload(
        sessionId: String,
        characterId: String? = null,
    ): String =
        buildString {
            append("{\"sessionId\":").append(ScriptRuntimeManager.jsonString(sessionId))
            if (characterId != null) {
                append(",\"characterId\":").append(ScriptRuntimeManager.jsonString(characterId))
            }
            append("}")
        }
}
