package com.mistbell.tavern.android.data.mvu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * MVU-lite：净室实现的变量更新引擎（行为兼容自卡片可观察的格式契约）。
 *
 * 格式契约（从苍玄界卡 mvu_update 世界书条目实测）：
 * <UpdateVariable><Analysis>…</Analysis><JSONPatch>[
 *   { "op": "replace", "path": "/主角状态/修为/当前境界", "value": "筑基六层" },
 *   { "op": "delta",   "path": "/主角状态/修为/进度百分比", "value": 5 },
 *   { "op": "remove",  "path": "/道友录/某角色" }
 * ]</JSONPatch></UpdateVariable>
 *
 * 语义：UpdateVariable 块从展示/落库文本剥离；patch 应用到会话级 stat_data。
 * 坏块/坏 JSON/坏路径单条跳过并告警，绝不影响回复落库。
 */
@Suppress("ReturnCount") // 防御性解析的守卫子句风格：每处早退即一条坏输入的丢弃点
object MvuEngine {
    private val UPDATE_BLOCK = Regex("<UpdateVariable>[\\s\\S]*?</UpdateVariable>")
    private val JSON_PATCH = Regex("<JSONPatch>([\\s\\S]*?)</JSONPatch>")

    private val json = Json { ignoreUnknownKeys = true }

    data class PatchOp(
        val op: String,
        val path: List<String>,
        val value: JsonElement?,
    )

    /** 处理结果：剥离后的文本 + 新 stat_data + 应用条数 + 告警 */
    data class Result(
        val cleanedText: String,
        val statData: JsonObject,
        val opsApplied: Int,
        val warnings: List<String>,
    )

    fun hasUpdateBlock(text: String): Boolean = text.contains("<UpdateVariable>")

    /**
     * 处理回复文本：剥离全部 UpdateVariable 块，应用合法 patch。
     * 无块时 cleanedText 与原文逐字节一致、statData 原样返回（零改动路径）。
     */
    fun process(
        text: String,
        statData: JsonObject,
    ): Result {
        val blocks = UPDATE_BLOCK.findAll(text).toList()
        if (blocks.isEmpty()) return Result(text, statData, 0, emptyList())

        val warnings = mutableListOf<String>()
        val tree = statData.toMutableTree()
        var applied = 0

        blocks.forEach { block ->
            parseBlock(block.value, warnings).forEach { op ->
                if (applyOp(tree, op)) applied++ else warnings.add("patch 失败：${op.op} /${op.path.joinToString("/")}")
            }
        }

        return Result(UPDATE_BLOCK.replace(text, "").trim(), tree.toJsonObject(), applied, warnings)
    }

    // ---- 可变 JSON 树（JsonObject 不可变，拆为自有可变壳） ----

    private class MObj {
        val map = LinkedHashMap<String, Any?>()
    }

    private fun JsonObject.toMutableTree(): MObj {
        val tree = MObj()
        forEach { (key, value) ->
            tree.map[key] = if (value is JsonObject) value.toMutableTree() else value
        }
        return tree
    }

    private fun MObj.toJsonObject(): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        this.map.forEach { (key, value) ->
            map[key] =
                when (value) {
                    is MObj -> value.toJsonObject()
                    is JsonElement -> value
                    else -> JsonNull
                }
        }
        return JsonObject(map)
    }

    // ---- patch 解析 ----

    private fun parseBlock(
        blockText: String,
        warnings: MutableList<String>,
    ): List<PatchOp> {
        val patchText = JSON_PATCH.find(blockText)?.groupValues?.get(1)?.trim()
        if (patchText.isNullOrBlank()) {
            warnings.add("UpdateVariable 块缺 JSONPatch 段，已跳过")
            return emptyList()
        }
        if (patchText.length > MAX_PATCH_BYTES) {
            warnings.add("JSONPatch 超 64KB 上限，已跳过")
            return emptyList()
        }
        val array =
            try {
                json.parseToJsonElement(patchText) as? kotlinx.serialization.json.JsonArray
            } catch (e: kotlinx.serialization.SerializationException) {
                warnings.add("JSONPatch 解析失败（已跳过）：${e.message}")
                return emptyList()
            }
        if (array == null) {
            warnings.add("JSONPatch 不是数组，已跳过")
            return emptyList()
        }
        if (array.size > MAX_OPS) {
            warnings.add("JSONPatch 操作数超 $MAX_OPS 上限，已跳过")
            return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val op = obj["op"].stringOrNull() ?: return@mapNotNull null
            val path = obj["path"].stringOrNull()?.let { parsePath(it) } ?: return@mapNotNull null
            PatchOp(op = op.lowercase(), path = path, value = obj["value"])
        }
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** 路径解析：`/a/b/c` → [a,b,c]；禁空段/../协议字符/__proto__。 */
    private fun parsePath(raw: String): List<String>? {
        if (!raw.startsWith("/")) return null
        val segments = raw.removePrefix("/").split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." || it == "__proto__" || it.contains(':') }) return null
        return segments
    }

    // ---- op 应用 ----

    private fun applyOp(
        tree: MObj,
        op: PatchOp,
    ): Boolean {
        val parent = resolveParent(tree, op.path, create = op.op != "remove") ?: return false
        val key = op.path.last()
        return when (op.op) {
            "replace" -> {
                parent.map[key] = op.value ?: JsonNull
                true
            }
            "delta" -> {
                val existing = parent.map[key]
                // delta 语义：键不存在按 0 起步；存在但非数值则跳过（不拿文本硬改数字）
                if (existing != null && (existing as? JsonPrimitive)?.doubleOrNull == null) return false
                val current = (existing as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val delta = (op.value as? JsonPrimitive)?.doubleOrNull ?: return false
                parent.map[key] = JsonPrimitive(current + delta)
                true
            }
            "remove" -> {
                parent.map.remove(key)
                true
            }
            else -> false
        }
    }

    /** 解析路径父节点；create=true 时自动建缺失的中间层（replace/delta 语义），remove 不建。 */
    private fun resolveParent(
        root: MObj,
        path: List<String>,
        create: Boolean,
    ): MObj? {
        var current = root
        for (segment in path.dropLast(1)) {
            when (val next = current.map[segment]) {
                is MObj -> current = next
                is JsonObject -> {
                    val converted = next.toMutableTree()
                    current.map[segment] = converted
                    current = converted
                }
                null -> {
                    if (!create) return null
                    val created = MObj()
                    current.map[segment] = created
                    current = created
                }
                else -> return null
            }
        }
        return current
    }

    private const val MAX_PATCH_BYTES = 64 * 1024
    private const val MAX_OPS = 200
}
