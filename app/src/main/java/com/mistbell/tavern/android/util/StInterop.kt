package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.prompt.WorldBookPlacement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * SillyTavern 世界书字段 ↔ 应用内字段的映射（纯函数，卡解析 / 独立书解析 / 导出三处共用）。
 *
 * 语义基准（实测酒馆发行版 data/default-user 真实文件 + world-info.js / characters.js 源码）：
 * - 数字 position 是酒馆 world_info_position 枚举：0=角色定义前 1=角色定义后 2/3=作者注释顶/底
 *   4=按深度注入（atDepth，配合 depth/role 生效）5/6=示例消息顶/底 7=outlet（应用未支持）；
 * - 酒馆 UI 给每个条目都写 depth 默认值（常见 4/5），仅 position=4 时生效——其余 position 下
 *   depth 是残留值，不得当作 @D（实测 Eldoria.json：position=0、depth=4 的条目按锚点注入）；
 * - 卡内嵌 character_book（CCv2/v3）条目的 position 是字符串 before_char/after_char，
 *   数字位置/概率/深度/角色在 extensions.position/.probability/.depth/.role；
 * - probability 为 0-100 百分数（配 useProbability 开关）；应用内统一为 0-1 小数。
 */
internal object StInterop {
    /** 酒馆 world_info_position 枚举值（world-info.js） */
    const val ST_POS_BEFORE = 0

    const val ST_POS_AFTER = 1

    const val ST_POS_AN_TOP = 2

    const val ST_POS_AN_BOTTOM = 3

    const val ST_POS_AT_DEPTH = 4

    const val ST_POS_EM_TOP = 5

    const val ST_POS_EM_BOTTOM = 6

    /** 生态导入的 @D 深度上限（与编辑页 0-10 一致） */
    private const val MAX_DEPTH = 10

    /** 酒馆 role（extension_prompt_roles）：0=系统 1=用户 2=角色 */
    fun mapDepthRole(
        role: Int?,
        warnings: MutableSet<String>,
    ): String =
        when (role) {
            null, 0 -> WorldBookPlacement.DEPTH_ROLE_SYSTEM
            1 -> WorldBookPlacement.DEPTH_ROLE_USER
            2 -> WorldBookPlacement.DEPTH_ROLE_ASSISTANT
            else -> {
                warnings.add("未知深度角色 $role，已按系统处理")
                WorldBookPlacement.DEPTH_ROLE_SYSTEM
            }
        }

    /**
     * 酒馆数字 position → 应用 (insertPosition, depth)。
     * 仅 position=atDepth 进入 @D 模式并取 depth（缺省/0 钳为 1，>10 钳为 10）；
     * 其余 position 忽略 depth（酒馆 UI 残留默认值）。
     */
    fun mapPosition(
        stPosition: Int?,
        depth: Int?,
        warnings: MutableSet<String>,
    ): Pair<String, Int> =
        when (stPosition) {
            null, ST_POS_BEFORE -> WorldBookPlacement.POSITION_BEFORE_PROMPT to 0
            ST_POS_AFTER -> WorldBookPlacement.POSITION_AFTER_PROMPT to 0
            ST_POS_AN_TOP -> WorldBookPlacement.POSITION_AN_BEFORE to 0
            ST_POS_AN_BOTTOM -> WorldBookPlacement.POSITION_AN_AFTER to 0
            ST_POS_AT_DEPTH -> WorldBookPlacement.POSITION_BEFORE_PROMPT to atDepth(depth, warnings)
            ST_POS_EM_TOP -> WorldBookPlacement.POSITION_EM_BEFORE to 0
            ST_POS_EM_BOTTOM -> WorldBookPlacement.POSITION_EM_AFTER to 0
            else -> {
                warnings.add("未知插入位置 $stPosition，已按角色定义前处理")
                WorldBookPlacement.POSITION_BEFORE_PROMPT to 0
            }
        }

    /** CCv2/v3 卡内嵌书的字符串 position（before_char/after_char）；未知值返回 null 由调用方兜底 */
    fun mapStringPosition(position: String?): Pair<String, Int>? =
        when (position) {
            "before_char" -> WorldBookPlacement.POSITION_BEFORE_PROMPT to 0
            "after_char" -> WorldBookPlacement.POSITION_AFTER_PROMPT to 0
            else -> null
        }

    /**
     * 应用 insertPosition/depth → 酒馆数字 position（导出方向，与 [mapPosition] 互逆）。
     */
    fun toStPosition(
        insertPosition: String,
        depth: Int,
    ): Int =
        when {
            depth > 0 -> ST_POS_AT_DEPTH
            insertPosition == WorldBookPlacement.POSITION_AFTER_PROMPT -> ST_POS_AFTER
            insertPosition == WorldBookPlacement.POSITION_AN_BEFORE -> ST_POS_AN_TOP
            insertPosition == WorldBookPlacement.POSITION_AN_AFTER -> ST_POS_AN_BOTTOM
            insertPosition == WorldBookPlacement.POSITION_EM_BEFORE -> ST_POS_EM_TOP
            insertPosition == WorldBookPlacement.POSITION_EM_AFTER -> ST_POS_EM_BOTTOM
            else -> ST_POS_BEFORE
        }

    /** 应用 depthRole → 酒馆 role 整数（导出方向，与 [mapDepthRole] 互逆） */
    fun toStRole(depthRole: String): Int =
        when (depthRole) {
            WorldBookPlacement.DEPTH_ROLE_USER -> 1
            WorldBookPlacement.DEPTH_ROLE_ASSISTANT -> 2
            else -> 0
        }

    /** 生态百分数制换算基数（酒馆 probability 为 0-100） */
    private const val PROB_PERCENT = 100.0

    /**
     * probability（0-100 百分数）/useProbability → 应用 0-1 概率。
     * useProbability=false 不掷骰（恒触发）；值 ≤1 按小数原样保留（兼容旧导出与应用内值），
     * >1 按百分数换算；缺省 1.0（必触发）。
     */
    fun normalizeProbability(
        raw: Double?,
        useProbability: Boolean?,
    ): Double =
        when {
            useProbability == false -> 1.0
            raw == null -> 1.0
            raw <= 1.0 -> raw.coerceIn(0.0, 1.0)
            else -> (raw / PROB_PERCENT).coerceIn(0.0, 1.0)
        }

    /**
     * 从条目（含 extensions）解析应用内位置字段：
     * extensions.position（酒馆数字）优先，其次条目顶层 position（数字按酒馆枚举、
     * 字符串按 CCv2/v3 规范），最后兜底角色定义前。
     */
    fun resolvePlacement(
        entry: JsonObject,
        warnings: MutableSet<String>,
    ): ResolvedPlacement {
        val ext = entry["extensions"] as? JsonObject

        // extensions.* 优先于条目顶层同名字段
        fun extOrTop(key: String): JsonPrimitive? = (ext?.get(key) ?: entry[key]) as? JsonPrimitive

        val extPosition = ext?.get("position")?.jsonPrimitive?.intOrNull
        val topLevel = entry["position"] as? JsonPrimitive
        val (insertPosition, depth) =
            when {
                extPosition != null -> mapPosition(extPosition, depthOf(ext, entry), warnings)
                topLevel?.isString == true ->
                    mapStringPosition(topLevel.content)
                        ?: run {
                            warnings.add("未知插入位置 ${topLevel.content}，已按角色定义前处理")
                            WorldBookPlacement.POSITION_BEFORE_PROMPT to 0
                        }
                else -> mapPosition(topLevel?.intOrNull, depthOf(ext, entry), warnings)
            }
        val probability =
            normalizeProbability(extOrTop("probability")?.doubleOrNull, extOrTop("useProbability")?.booleanOrNull)
        val depthRole = mapDepthRole(extOrTop("role")?.intOrNull, warnings)
        return ResolvedPlacement(insertPosition, depth, probability, depthRole)
    }

    private fun depthOf(
        ext: JsonObject?,
        entry: JsonObject,
    ): Int? = (ext?.get("depth") ?: entry["depth"])?.let { (it as? JsonPrimitive)?.intOrNull }

    private fun atDepth(
        depth: Int?,
        warnings: MutableSet<String>,
    ): Int {
        if (depth != null && depth > MAX_DEPTH) {
            warnings.add("深度超过 $MAX_DEPTH，已钳制为 $MAX_DEPTH")
        }
        return (depth ?: 0).coerceIn(0, MAX_DEPTH).takeIf { it > 0 } ?: 1
    }

    /** 条目位置解析结果（应用内字段） */
    data class ResolvedPlacement(
        val insertPosition: String,
        val depth: Int,
        val probability: Double,
        val depthRole: String,
    )
}
