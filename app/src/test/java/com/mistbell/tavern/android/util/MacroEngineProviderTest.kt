package com.mistbell.tavern.android.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** MacroEngine 自定义宏提供器（registerMacroProvider）的 JVM 单测。 */
class MacroEngineProviderTest {
    private val ctx = MacroContext(char = "蒋珞", user = "阿伟")

    @After
    fun tearDown() {
        MacroEngine.resetMacroProvidersForTesting()
    }

    @Test
    fun `提供器解析内置表未命中的宏`() {
        MacroEngine.registerMacroProvider { name, _ ->
            if (name == "mood") "开心" else null
        }
        assertEquals("蒋珞今天很开心", MacroEngine.render("{{char}}今天很{{mood}}", ctx))
    }

    @Test
    fun `提供器可读取宏上下文`() {
        MacroEngine.registerMacroProvider { name, c ->
            if (name == "greet") "你好${c.user}" else null
        }
        assertEquals("你好阿伟", MacroEngine.render("{{greet}}", ctx))
    }

    @Test
    fun `全部未命中时未知宏原样保留`() {
        MacroEngine.registerMacroProvider { _, _ -> null }
        assertEquals("{{unknown}}", MacroEngine.render("{{unknown}}", ctx))
    }

    @Test
    fun `按注册序采用首个非空结果`() {
        MacroEngine.registerMacroProvider { _, _ -> null }
        MacroEngine.registerMacroProvider { name, _ -> if (name == "x") "第二" else null }
        MacroEngine.registerMacroProvider { name, _ -> if (name == "x") "第三" else null }
        assertEquals("第二", MacroEngine.render("{{x}}", ctx))
    }

    @Test
    fun `注销后不再参与解析`() {
        val provider: (String, MacroContext) -> String? = { name, _ ->
            if (name == "temp") "值" else null
        }
        MacroEngine.registerMacroProvider(provider)
        MacroEngine.unregisterMacroProvider(provider)
        assertEquals("{{temp}}", MacroEngine.render("{{temp}}", ctx))
    }

    @Test
    fun `内置宏优先于提供器`() {
        MacroEngine.registerMacroProvider { name, _ ->
            if (name == "char") "被覆盖" else null
        }
        assertEquals("蒋珞", MacroEngine.render("{{char}}", ctx))
    }
}
