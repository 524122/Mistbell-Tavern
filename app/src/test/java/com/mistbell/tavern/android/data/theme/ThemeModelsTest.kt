package com.mistbell.tavern.android.data.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主题包 tokens 纯函数单元测试（T1 皮肤级主题包）。
 * 覆盖 parseHexColor / parseTokens / resolved / resolveTokens 纯函数链。
 */
class ThemeModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- parseHexColor ----

    @Test
    fun `6位hex解析正确`() {
        assertEquals(0xFF123456.toInt(), ThemeSupport.parseHexColor("#123456")!!.toArgb())
    }

    @Test
    fun `8位hex解析保留alpha`() {
        val c = ThemeSupport.parseHexColor("#80123456")!!
        assertEquals(0x80, c.toArgb() ushr 24 and 0xFF)
    }

    @Test
    fun `大写小写等价`() {
        assertEquals(
            ThemeSupport.parseHexColor("#abcdef")!!.toArgb(),
            ThemeSupport.parseHexColor("#ABCDEF")!!.toArgb()
        )
    }

    @Test
    fun `3位缩写按契约返回null`() {
        // 契约只保证 6/8 位；3 位无论支持与否此断言仅约束不崩溃，
        // 按最小契约实现返回 null
        assertNull(ThemeSupport.parseHexColor("#FFF"))
    }

    @Test
    fun `非法颜色返回null`() {
        assertNull(ThemeSupport.parseHexColor(null))
        assertNull(ThemeSupport.parseHexColor(""))
        assertNull(ThemeSupport.parseHexColor("red"))
        assertNull(ThemeSupport.parseHexColor("#"))
        assertNull(ThemeSupport.parseHexColor("#GGGGGG"))
        assertNull(ThemeSupport.parseHexColor("#12345")) // 5 位非法
        assertNull(ThemeSupport.parseHexColor("#1234567")) // 7 位非法
    }

    // ---- parseTokens ----

    @Test
    fun `合法JSON全字段解析`() {
        val s = """
            {"colors":{"primary":"#FF0000","userBubble":"#00FF00","onUserBubble":"#FFFFFF"},
             "dark":{"background":"#111111"},
             "background":"bg.png"}
        """.trimIndent()
        val t = ThemeSupport.parseTokens(s)
        assertNotNull(t)
        assertEquals("#FF0000", t!!.colors.primary)
        assertEquals("#00FF00", t.colors.userBubble)
        assertEquals("#FFFFFF", t.colors.onUserBubble)
        assertEquals("#111111", t.dark!!.background)
        assertEquals("bg.png", t.background)
    }

    @Test
    fun `空对象解析为默认值实例`() {
        val t = ThemeSupport.parseTokens("{}")
        assertNotNull(t)
        assertEquals(ThemeTokens(), t!!)
        assertNull(t.dark)
        assertNull(t.background)
    }

    @Test
    fun `坏JSON返回null`() {
        assertNull(ThemeSupport.parseTokens("not json"))
        assertNull(ThemeSupport.parseTokens(""))
        assertNull(ThemeSupport.parseTokens("{\"colors\":\"oops\"}"))
    }

    @Test
    fun `未知字段被忽略`() {
        val t = ThemeSupport.parseTokens("""{"unknownKey":123,"colors":{"nope":"x","primary":"#123456"}}""")
        assertNotNull(t)
        assertEquals("#123456", t!!.colors.primary)
    }

    // ---- resolved(isDark) ----

    @Test
    fun `浅色模式不含dark覆盖`() {
        val tokens = ThemeTokens(
            colors = ThemeColors(primary = "#FF0000", background = "#EEEEEE"),
            dark = ThemeColors(primary = "#00FF00", background = "#111111")
        )
        val light = tokens.resolved(isDark = false)
        assertEquals(0xFFFF0000.toInt(), light.primary!!.toArgb())
        assertEquals(0xFFEEEEEE.toInt(), light.background!!.toArgb())
    }

    @Test
    fun `深色模式应用dark覆盖`() {
        val tokens = ThemeTokens(
            colors = ThemeColors(primary = "#FF0000", background = "#EEEEEE"),
            dark = ThemeColors(primary = "#00FF00", background = "#111111")
        )
        val dark = tokens.resolved(isDark = true)
        assertEquals(0xFF00FF00.toInt(), dark.primary!!.toArgb())
        assertEquals(0xFF111111.toInt(), dark.background!!.toArgb())
    }

    @Test
    fun `userBubble覆盖primary且assistantBubble覆盖surface`() {
        val tokens = ThemeTokens(
            colors = ThemeColors(
                primary = "#FF0000", surface = "#0000FF",
                userBubble = "#00FF00", assistantBubble = "#FFFF00",
                onUserBubble = "#333333", onAssistantBubble = "#444444"
            )
        )
        val r = tokens.resolved(isDark = false)
        assertEquals(0xFF00FF00.toInt(), r.primary!!.toArgb())
        assertEquals(0xFFFFFF00.toInt(), r.surface!!.toArgb())
        // onPrimary = onUserBubble ?: onPrimary
        assertEquals(0xFF333333.toInt(), r.onPrimary!!.toArgb())
        // onSurface = onAssistantBubble ?: onSurface
        assertEquals(0xFF444444.toInt(), r.onSurface!!.toArgb())
    }

    @Test
    fun `非法色值字段被置null不覆盖`() {
        val tokens = ThemeTokens(colors = ThemeColors(primary = "red", userBubble = "#GGGGGG"))
        val r = tokens.resolved(isDark = false)
        // primary 与 userBubble 均非法 → 不覆盖
        assertNull(r.primary)
    }

    @Test
    fun `全空tokens的resolved全部为null`() {
        val r = ThemeTokens().resolved(isDark = true)
        assertNull(r.primary)
        assertNull(r.onPrimary)
        assertNull(r.background)
        assertNull(r.onBackground)
        assertNull(r.surface)
        assertNull(r.onSurface)
        assertNull(r.surfaceVariant)
    }

    // ---- resolveTokens ----

    private fun pack(id: String, tokens: ThemeTokens): ThemePackEntity {
        val tokensJson = json.encodeToString(tokens)
        return ThemePackEntity(
            id = id, name = id, author = "tester", version = "1.0",
            tokensJson = tokensJson, backgroundFile = null, createdAt = "2026-01-01"
        )
    }

    private fun badPack(id: String): ThemePackEntity = ThemePackEntity(
        id = id, name = id, author = "tester", version = "1.0",
        tokensJson = "{this is not valid json", backgroundFile = null, createdAt = "2026-01-01"
    )

    @Test
    fun `角色主题tokens解析失败时回落全局而非打死应用链`() {
        val packs = mapOf(
            "bad-char-theme" to badPack("bad-char-theme"),
            "global" to pack("global", ThemeTokens(colors = ThemeColors(primary = "#00FF00")))
        )
        val t = ThemeSupport.resolveTokens(
            characterThemeId = "bad-char-theme", activeThemeId = "global", packs = packs
        )
        assertEquals("坏包必须让位给全局主题", "#00FF00", t!!.colors.primary)
    }

    @Test
    fun `resolvePackId与resolveTokens判定一致`() {
        val packs = mapOf(
            "bad-char-theme" to badPack("bad-char-theme"),
            "global" to pack("global", ThemeTokens(colors = ThemeColors(primary = "#00FF00")))
        )
        assertEquals("global", ThemeSupport.resolvePackId("bad-char-theme", "global", packs))
        assertEquals("char", ThemeSupport.resolvePackId("char", "global", mapOf("char" to pack("char", ThemeTokens()))))
        assertNull(ThemeSupport.resolvePackId("missing", "also-missing", packs))
    }

    @Test
    fun `角色主题优先于全局`() {
        val packs = mapOf(
            "char-theme" to pack("char-theme", ThemeTokens(colors = ThemeColors(primary = "#FF0000"))),
            "global" to pack("global", ThemeTokens(colors = ThemeColors(primary = "#00FF00")))
        )
        val t = ThemeSupport.resolveTokens(
            characterThemeId = "char-theme", activeThemeId = "global", packs = packs
        )
        assertEquals("#FF0000", t!!.colors.primary)
    }

    @Test
    fun `无角色主题时回落全局`() {
        val packs = mapOf(
            "global" to pack("global", ThemeTokens(colors = ThemeColors(primary = "#00FF00")))
        )
        val t = ThemeSupport.resolveTokens(
            characterThemeId = "char-theme", activeThemeId = "global", packs = packs
        )
        assertEquals("#00FF00", t!!.colors.primary)
    }

    @Test
    fun `角色主题命中但包缺失时回落全局`() {
        val packs = mapOf(
            "global" to pack("global", ThemeTokens(colors = ThemeColors(primary = "#00FF00")))
        )
        // characterThemeId 指向不存在的包
        val t = ThemeSupport.resolveTokens(
            characterThemeId = "missing", activeThemeId = "global", packs = packs
        )
        assertEquals("#00FF00", t!!.colors.primary)
    }

    @Test
    fun `都没有命中返回null`() {
        val packs = emptyMap<String, ThemePackEntity>()
        assertNull(ThemeSupport.resolveTokens(null, null, packs))
        assertNull(ThemeSupport.resolveTokens("char-theme", "global", packs))
    }

    @Test
    fun `activeThemeId空白不命中`() {
        val packs = mapOf(
            "global" to pack("global", ThemeTokens(colors = ThemeColors(primary = "#00FF00")))
        )
        assertNull(ThemeSupport.resolveTokens(null, "", packs))
        assertNull(ThemeSupport.resolveTokens(null, "   ", packs))
        // 角色主题空白 → 正常回落全局（应用链设计），而非返回 null
        assertEquals(
            "#00FF00",
            ThemeSupport.resolveTokens("", "global", packs)!!.colors.primary
        )
    }
}
