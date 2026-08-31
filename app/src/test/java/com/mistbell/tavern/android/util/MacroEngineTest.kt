package com.mistbell.tavern.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * MacroEngine 纯 JVM 单元测试（F2.1 任务 E）。
 * 覆盖：身份宏/字段宏/旧式占位、random 种子化、roll 规格边界、
 * {{#if}} 条件块、未知宏原样、注释宏、newline、null/空串入参、异常回退。
 */
class MacroEngineTest {
    /** 通用上下文：字段各不相同，便于断言替换来源。 */
    private val ctx =
        MacroContext(
            char = "爱丽丝",
            user = "小明",
            description = "银发少女",
            personality = "温柔",
            scenario = "旧书店",
            persona = "数学老师",
        )

    // ---------- 身份宏 ----------

    @Test
    fun `身份宏char与user被替换`() {
        assertEquals("爱丽丝和小明", MacroEngine.render("{{char}}和{{user}}", ctx))
        // 大小写不敏感
        assertEquals("爱丽丝和小明", MacroEngine.render("{{CHAR}}和{{USER}}", ctx))
    }

    @Test
    fun `character与bot别名同char`() {
        assertEquals("爱丽丝爱丽丝", MacroEngine.render("{{character}}{{bot}}", ctx))
    }

    // ---------- 字段宏 ----------

    @Test
    fun `字段宏替换为上下文字段`() {
        assertEquals(
            "银发少女|温柔|旧书店|数学老师",
            MacroEngine.render("{{description}}|{{personality}}|{{scenario}}|{{persona}}", ctx),
        )
    }

    @Test
    fun `字段宏旧名chardescription与charscenario`() {
        assertEquals("银发少女旧书店", MacroEngine.render("{{chardescription}}{{charscenario}}", ctx))
    }

    // ---------- 旧式大写占位符 ----------

    @Test
    fun `旧式大写占位符被替换`() {
        assertEquals("爱丽丝对小明说：爱丽丝", MacroEngine.render("<CHAR>对<USER>说：<BOT>", ctx))
    }

    // ---------- random 参数宏 ----------

    @Test
    fun `random种子化结果确定且属于选项集`() {
        val opts = listOf("苹果", "香蕉", "橘子")
        val t1 = MacroEngine.render("{{random::苹果::香蕉::橘子}}", ctx, Random(42))
        val t2 = MacroEngine.render("{{random::苹果::香蕉::橘子}}", ctx, Random(42))
        // 同种子同输入 → 结果一致
        assertEquals(t1, t2)
        // 且必须是选项之一
        assertTrue(t1 in opts)
    }

    @Test
    fun `random含空选项时原样保留`() {
        assertEquals("{{random::a::::c}}", MacroEngine.render("{{random::a::::c}}", ctx, Random(42)))
    }

    // ---------- roll 参数宏 ----------

    @Test
    fun `roll_1d6结果在1到6之间`() {
        val r = MacroEngine.render("{{roll::1d6}}", ctx, Random(42)).toInt()
        assertTrue(r in 1..6)
    }

    @Test
    fun `roll_3d6结果在3到18之间`() {
        val r = MacroEngine.render("{{roll::3d6}}", ctx, Random(42)).toInt()
        assertTrue(r in 3..18)
    }

    @Test
    fun `roll_d20缺省次数为1结果在1到20之间`() {
        val r = MacroEngine.render("{{roll::d20}}", ctx, Random(42)).toInt()
        assertTrue(r in 1..20)
    }

    @Test
    fun `roll非法规格原样保留`() {
        // 0d6 / 3d0 / abc 均不满足 N≥1 且 M≥2，原样保留
        assertEquals("{{roll::0d6}}", MacroEngine.render("{{roll::0d6}}", ctx, Random(42)))
        assertEquals("{{roll::3d0}}", MacroEngine.render("{{roll::3d0}}", ctx, Random(42)))
        assertEquals("{{roll::abc}}", MacroEngine.render("{{roll::abc}}", ctx, Random(42)))
    }

    // ---------- 条件块 ----------

    @Test
    fun `if条件宏非空时保留内容`() {
        assertEquals("名字是爱丽丝。", MacroEngine.render("名字是{{#if:char}}爱丽丝{{/if}}。", ctx))
    }

    @Test
    fun `if条件字段为空时剔除内容`() {
        val empty = ctx.copy(description = "")
        assertEquals("前后", MacroEngine.render("前{{#if:description}}中间{{/if}}后", empty))
    }

    @Test
    fun `if条件为未知宏名时视为空剔除`() {
        assertEquals("AA", MacroEngine.render("A{{#if:unknown}}BBB{{/if}}A", ctx))
    }

    @Test
    fun `if命中后内容继续参与渲染`() {
        assertEquals("爱丽丝", MacroEngine.render("{{#if:user}}{{char}}{{/if}}", ctx))
    }

    // ---------- 未知宏 / 注释 / newline ----------

    @Test
    fun `未知宏原样保留`() {
        assertEquals("{{no_such_macro}}", MacroEngine.render("{{no_such_macro}}", ctx))
    }

    @Test
    fun `注释宏被移除`() {
        assertEquals("ab", MacroEngine.render("a{{//这是注释}}b", ctx))
    }

    @Test
    fun `newline与space宏`() {
        assertEquals("a\nb c", MacroEngine.render("a{{newline}}b{{space}}c", ctx))
    }

    @Test
    fun `noop宏输出空串`() {
        assertEquals("ab", MacroEngine.render("a{{noop}}b", ctx))
    }

    // ---------- 时间日期宏（只断言格式，不依赖具体时刻） ----------

    @Test
    fun `时间日期宏输出合法格式`() {
        val date = MacroEngine.render("{{date}}", ctx)
        // yyyy-MM-dd
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(date))
        val time = MacroEngine.render("{{time}}", ctx)
        // HH:mm:ss（不含纳秒尾巴）
        assertTrue(Regex("""\d{2}:\d{2}:\d{2}""").matches(time))
        // weekday 是英文星期名
        val weekday = MacroEngine.render("{{weekday}}", ctx)
        assertTrue(weekday in listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))
    }

    // ---------- null / 空串入参 ----------

    @Test
    fun `null入参返回空串`() {
        assertEquals("", MacroEngine.render(null, ctx))
    }

    @Test
    fun `空串入参原样返回`() {
        assertEquals("", MacroEngine.render("", ctx))
    }

    // ---------- 异常回退 ----------

    @Test
    fun `畸形嵌套不崩溃返回原文`() {
        val nasty = "{{#if:char}}{{random::}}{{/if}}<CHAR>{{roll::"
        // 不抛异常；结果为原文或安全渲染后的文本，但绝不能崩溃
        val out =
            try {
                MacroEngine.render(nasty, ctx, Random(42))
            } catch (e: Exception) {
                throw AssertionError("渲染不应抛异常", e)
            }
        // 未命中条件块剔除后剩余文本中不再含条件块标记（内容安全），且不含畸形 roll 的求值
        assertFalse(out.contains("{{#if:"))
    }
}
