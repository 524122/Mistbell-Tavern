@file:Suppress("MatchingDeclarationName") // 文件承载变量桥全集：快照模型 + 扁平化函数

package com.mistbell.tavern.android.ui.chat.render

/*
 * 变量源桥接（M4，THEME_FRAMEWORK_DESIGN.md §6.2）：宿主 MVU 会话 KV（ext.mvu.<sessionId> 的
 * stat_data JSON）→ 引擎绑定上下文可用的扁平化 Map<String,String>。
 *
 * 只复用既有变量链、不重写生成逻辑：JSON 解析与扁平化在此纯函数化（JVM 可单测），数据读取
 * 与版本推送仍归 MvuExtension / ChatViewModel.mvuVariablesVersion（渲染层只读，永不写变量）。
 */

/**
 * 扁平化产物快照：[version] 即 ChatViewModel.mvuVariablesVersion（VariablesUpdated 事件计数），
 * 兼作引擎 Bitmap 缓存四元组的失效键；[variables] 为点路径 → 字符串值。
 */
data class VariablesSnapshot(
    val version: Long,
    val variables: Map<String, String>,
) {
    companion object {
        /** 无会话/无数据时的空快照（版本 0） */
        val EMPTY = VariablesSnapshot(version = 0L, variables = emptyMap())
    }
}

/** 嵌套深度上限：护住递归栈（异常深嵌套的 stat_data 属脏数据，超出即丢弃该子树） */
private const val FLATTEN_MAX_DEPTH = 50

/**
 * MVU 变量扁平化（纯函数）：嵌套对象转点路径（a.b.c）、数组转下标路径（list.0）、
 * 数字/布尔转字符串、字符串原样保留、null 值位不注入（同 token 桥"未设置不注入"语义）。
 * 坏 JSON/空串/非对象根 → 空表：绑定失败语义是"未命中即空串"，不崩渲染。
 */
@Suppress("ReturnCount") // 防御性解析的守卫子句风格：每处早退即一条坏输入的丢弃点（与 MvuEngine 同型）
fun flattenMvuVariables(statDataJson: String?): Map<String, String> {
    val json = statDataJson?.trim().orEmpty()
    if (json.isEmpty()) return emptyMap()
    val root =
        try {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
        } catch (_: Exception) {
            return emptyMap()
        }
    if (root !is kotlinx.serialization.json.JsonObject) return emptyMap()
    val out = LinkedHashMap<String, String>()
    flattenInto(root, "", depth = 1, out = out)
    return out
}

private fun flattenInto(
    element: kotlinx.serialization.json.JsonElement,
    path: String,
    depth: Int,
    out: MutableMap<String, String>,
) {
    when (element) {
        is kotlinx.serialization.json.JsonObject ->
            if (depth <= FLATTEN_MAX_DEPTH) {
                element.forEach { (key, value) ->
                    flattenInto(value, if (path.isEmpty()) key else "$path.$key", depth + 1, out)
                }
            }
        is kotlinx.serialization.json.JsonArray ->
            if (depth <= FLATTEN_MAX_DEPTH) {
                element.forEachIndexed { index, value ->
                    flattenInto(value, if (path.isEmpty()) "$index" else "$path.$index", depth + 1, out)
                }
            }
        // JsonNull 是 JsonPrimitive 的子类型，必须先于 is JsonPrimitive 分支匹配——
        // null 值位不注入（同 token 桥"未设置不注入"语义）
        kotlinx.serialization.json.JsonNull -> Unit
        is kotlinx.serialization.json.JsonPrimitive -> out[path] = element.content
    }
}
