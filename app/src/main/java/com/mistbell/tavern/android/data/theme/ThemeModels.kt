package com.mistbell.tavern.android.data.theme

import androidx.compose.ui.graphics.Color
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 主题包颜色 tokens，全部可空：空 = 不覆盖默认 */
@Serializable
data class ThemeColors(
    val primary: String? = null,
    val background: String? = null,
    val surface: String? = null,
    val surfaceVariant: String? = null,
    val userBubble: String? = null,
    val assistantBubble: String? = null,
    val onUserBubble: String? = null,
    val onAssistantBubble: String? = null,
    val onBackground: String? = null,
    val onSurface: String? = null
)

/** 主题包 tokens（theme.json 内容） */
@Serializable
data class ThemeTokens(
    val colors: ThemeColors = ThemeColors(),
    val dark: ThemeColors? = null,      // 深色模式覆盖，缺省同色
    val background: String? = null      // 背景图文件名（zip 内 assets/ 下）
)

/** resolved 结果：仅 Compose Color，空 = 不覆盖 */
data class ParsedThemeColors(
    val primary: Color?,
    val onPrimary: Color?,
    val background: Color?,
    val onBackground: Color?,
    val surface: Color?,
    val onSurface: Color?,
    val surfaceVariant: Color?
)

/** 主题辅助：颜色解析、tokens 解析、应用链解析（纯函数） */
object ThemeSupport {

    /** "#RRGGBB"/"#AARRGGBB" → Color，非法/空返回 null */
    fun parseHexColor(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        val body = hex.removePrefix("#").trim()
        return when (body.length) {
            6 -> {
                // RRGGBB 补 FF alpha 前缀，toLongOrNull(16) 防溢出
                body.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) }
            }
            8 -> body.toLongOrNull(16)?.let { Color(it.toInt()) }
            else -> null
        }
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 容错解析 theme.json，失败返回 null */
    fun parseTokens(json: String): ThemeTokens? = try {
        lenientJson.decodeFromString<ThemeTokens>(json)
    } catch (_: Exception) {
        null
    }

    /**
     * 应用链（纯函数）：会话主题 → 角色主题 → 全局主题 → null；
     * 任一层"包不存在或 tokens 解析失败（坏包）"时回落下一层而非打死整条链。
     */
    fun resolveTokens(
        sessionThemeId: String?,
        characterThemeId: String?,
        activeThemeId: String?,
        packs: Map<String, ThemePackEntity>
    ): ThemeTokens? {
        val sessionId = sessionThemeId?.trim().orEmpty()
        if (sessionId.isNotEmpty()) {
            packs[sessionId]?.let { parseTokens(it.tokensJson)?.let { tokens -> return tokens } }
        }
        val charId = characterThemeId?.trim().orEmpty()
        if (charId.isNotEmpty()) {
            packs[charId]?.let { parseTokens(it.tokensJson)?.let { tokens -> return tokens } }
        }
        val globalId = activeThemeId?.trim().orEmpty()
        if (globalId.isNotEmpty()) {
            packs[globalId]?.let { parseTokens(it.tokensJson)?.let { tokens -> return tokens } }
        }
        return null
    }

    /** 应用链命中的包 id（背景图等制品消费用）：会话 → 角色 → 全局 → null；判定标准与 resolveTokens 完全一致（包存在且 tokens 可解析） */
    fun resolvePackId(
        sessionThemeId: String?,
        characterThemeId: String?,
        activeThemeId: String?,
        packs: Map<String, ThemePackEntity>
    ): String? {
        val sessionId = sessionThemeId?.trim().orEmpty()
        if (sessionId.isNotEmpty()) {
            packs[sessionId]?.let { if (parseTokens(it.tokensJson) != null) return sessionId }
        }
        val charId = characterThemeId?.trim().orEmpty()
        if (charId.isNotEmpty()) {
            packs[charId]?.let { if (parseTokens(it.tokensJson) != null) return charId }
        }
        val globalId = activeThemeId?.trim().orEmpty()
        if (globalId.isNotEmpty()) {
            packs[globalId]?.let { if (parseTokens(it.tokensJson) != null) return globalId }
        }
        return null
    }
}

/**
 * 合并解析为 Compose 颜色：
 * dark 非空时用 dark 的非空字段覆盖 colors，再计算 effective 值：
 * primary = userBubble ?: primary；surface = assistantBubble ?: surface；
 * onPrimary = onUserBubble ?: onPrimary（原 null）；onSurface = onAssistantBubble ?: onSurface（原 null）
 */
fun ThemeTokens.resolved(isDark: Boolean): ParsedThemeColors {
    val base = if (isDark && dark != null) {
        colors.copy(
            primary = dark.primary ?: colors.primary,
            background = dark.background ?: colors.background,
            surface = dark.surface ?: colors.surface,
            surfaceVariant = dark.surfaceVariant ?: colors.surfaceVariant,
            userBubble = dark.userBubble ?: colors.userBubble,
            assistantBubble = dark.assistantBubble ?: colors.assistantBubble,
            onUserBubble = dark.onUserBubble ?: colors.onUserBubble,
            onAssistantBubble = dark.onAssistantBubble ?: colors.onAssistantBubble,
            onBackground = dark.onBackground ?: colors.onBackground,
            onSurface = dark.onSurface ?: colors.onSurface
        )
    } else colors

    return ParsedThemeColors(
        primary = ThemeSupport.parseHexColor(base.userBubble) ?: ThemeSupport.parseHexColor(base.primary),
        onPrimary = ThemeSupport.parseHexColor(base.onUserBubble),
        background = ThemeSupport.parseHexColor(base.background),
        onBackground = ThemeSupport.parseHexColor(base.onBackground),
        surface = ThemeSupport.parseHexColor(base.assistantBubble) ?: ThemeSupport.parseHexColor(base.surface),
        onSurface = ThemeSupport.parseHexColor(base.onAssistantBubble),
        surfaceVariant = ThemeSupport.parseHexColor(base.surfaceVariant)
    )
}
