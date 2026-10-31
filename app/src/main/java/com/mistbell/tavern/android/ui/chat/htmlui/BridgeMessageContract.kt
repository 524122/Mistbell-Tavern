package com.mistbell.tavern.android.ui.chat.htmlui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 酒馆助手消息写 API 的负载解析与响应构造（纯函数，JVM 可单测）。
 *
 * 契约说明（净室差异，见 docs/SCRIPTING.md）：酒馆助手按楼层序号寻址；本应用
 * 消息列表是"最新 200 条窗口 + 上滚补页"，全局序号在长会话中不稳定，故一律按
 * 消息 id 寻址——[id] 由 getChatMessages 返回，与 getLastMessageId() 配套。
 *
 * 上限防护：正文 20000 字符、批量 50 条、id 长度 64；超限整单拒绝并说明原因
 * （部分写入会让页面侧状态与预期错位，宁可整单失败让脚本重试）。
 */
object BridgeMessageContract {
    private const val MAX_CONTENT_LENGTH = 20_000
    private const val MAX_BATCH = 50
    private const val MAX_ID_LENGTH = 64
    private val roles = setOf("user", "assistant")
    private val json = Json { ignoreUnknownKeys = true }

    data class MessagePatch(
        val id: String,
        val content: String,
        val thinking: String? = null,
    )

    data class NewMessage(
        val role: String,
        val content: String,
    )

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>

        data class Err(val reason: String) : Parsed<Nothing>
    }

    /** setChatMessages 负载：[{id, content, thinking?}]（thinking 传 null 表示不改） */
    fun parsePatches(payload: String): Parsed<List<MessagePatch>> =
        runCatchingParse(payload) { element ->
            val array = element.jsonArray
            if (array.size > MAX_BATCH) return@runCatchingParse Parsed.Err("批量上限 $MAX_BATCH 条，收到 ${array.size}")
            val patches = mutableListOf<MessagePatch>()
            for (item in array) {
                val obj = item as? JsonObject ?: return@runCatchingParse Parsed.Err("条目须为对象：$item")
                val id = obj.stringField("id")
                if (id.isNullOrBlank() || id.length > MAX_ID_LENGTH) {
                    return@runCatchingParse Parsed.Err("id 非法（须为 1..$MAX_ID_LENGTH 字符）")
                }
                val content = obj.stringField("content") ?: return@runCatchingParse Parsed.Err("content 缺失")
                if (content.length > MAX_CONTENT_LENGTH) {
                    return@runCatchingParse Parsed.Err("content 超长（>${MAX_CONTENT_LENGTH}）")
                }
                val thinking = obj.stringFieldOrNull("thinking")?.takeIf { it.length <= MAX_CONTENT_LENGTH }
                patches += MessagePatch(id = id, content = content, thinking = thinking)
            }
            Parsed.Ok(patches)
        }

    /** createChatMessage 负载：{role, content}（role 白名单 user/assistant） */
    fun parseNewMessage(payload: String): Parsed<NewMessage> =
        runCatchingParse(payload) { element ->
            val obj = element.jsonObject
            val role = obj.stringField("role") ?: return@runCatchingParse Parsed.Err("role 缺失")
            if (role !in roles) return@runCatchingParse Parsed.Err("role 仅支持 $roles")
            val content = obj.stringField("content") ?: return@runCatchingParse Parsed.Err("content 缺失")
            if (content.length > MAX_CONTENT_LENGTH) {
                return@runCatchingParse Parsed.Err("content 超长（>$MAX_CONTENT_LENGTH）")
            }
            Parsed.Ok(NewMessage(role = role, content = content))
        }

    /** deleteChatMessages 负载：[id, ...] */
    fun parseIds(payload: String): Parsed<List<String>> =
        runCatchingParse(payload) { element ->
            val array = element.jsonArray
            if (array.size > MAX_BATCH) return@runCatchingParse Parsed.Err("批量上限 $MAX_BATCH 条，收到 ${array.size}")
            val ids = mutableListOf<String>()
            for (item in array) {
                val id = (item as? JsonPrimitive)?.contentOrNull
                if (id.isNullOrBlank() || id.length > MAX_ID_LENGTH) {
                    return@runCatchingParse Parsed.Err("id 非法：$item")
                }
                ids += id
            }
            Parsed.Ok(ids)
        }

    // ---- 响应构造（桥同步返回的 JSON 字符串） ----

    fun okUpdated(count: Int): String = JsonObject(mapOf("updated" to JsonPrimitive(count))).toString()

    fun okCreated(id: String): String = JsonObject(mapOf("id" to JsonPrimitive(id))).toString()

    fun okDeleted(count: Int): String = JsonObject(mapOf("deleted" to JsonPrimitive(count))).toString()

    fun error(reason: String): String = JsonObject(mapOf("error" to JsonPrimitive(reason))).toString()

    private inline fun <T> runCatchingParse(
        payload: String,
        parse: (kotlinx.serialization.json.JsonElement) -> Parsed<T>,
    ): Parsed<T> =
        runCatching { parse(json.parseToJsonElement(payload)) }
            .getOrElse { Parsed.Err("负载不是合法 JSON：${it.message}") }

    private fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringFieldOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
