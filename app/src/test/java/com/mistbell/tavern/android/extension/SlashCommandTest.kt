package com.mistbell.tavern.android.extension

import com.mistbell.tavern.android.extension.slash.SlashCommandDispatcher
import com.mistbell.tavern.android.extension.slash.SlashCommandParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandTest {
    private val ctx = SlashCommandContext("owner", "char", "session")

    @After
    fun tearDown() {
        ExtensionRegistry.resetForTesting()
    }

    // ---- 解析器 ----

    @Test
    fun `解析命令名与参数`() {
        val parsed = SlashCommandParser.parse("/note 你好 世界")
        assertEquals("note", parsed?.name)
        assertEquals("你好 世界", parsed?.args)
    }

    @Test
    fun `命令名大小写归一`() {
        assertEquals("help", SlashCommandParser.parse("/HELP")?.name)
    }

    @Test
    fun `非命令文本不解析`() {
        assertNull(SlashCommandParser.parse("普通消息"))
        assertNull(SlashCommandParser.parse("// 注释式文本"))
        assertNull(SlashCommandParser.parse("/"))
        assertFalse(SlashCommandParser.isSlashCommand("hello /world"))
    }

    @Test
    fun `前导空白容忍`() {
        assertTrue(SlashCommandParser.isSlashCommand("  /help"))
        assertEquals("help", SlashCommandParser.parse("  /help")?.name)
    }

    // ---- 分发器 ----

    private fun fakeCommand(
        cmdName: String,
        alias: String? = null,
    ) = object : TavernExtension, SlashCommandHandler {
        override val id = "fake-$cmdName"
        override val name = cmdName
        override val loadingOrder = 10
        override val aliases = listOfNotNull(alias)
        override val description = "测试命令"

        override suspend fun execute(
            args: String,
            ctx: SlashCommandContext,
        ): String = "执行了 $cmdName：$args"
    }

    @Test
    fun `分发到已注册命令并返回结果`() =
        runBlocking {
            ExtensionRegistry.register(fakeCommand("echo"))
            assertEquals("执行了 echo：你好", SlashCommandDispatcher.dispatch("/echo 你好", ctx))
        }

    @Test
    fun `别名命中同一命令`() =
        runBlocking {
            ExtensionRegistry.register(fakeCommand("note", alias = "an"))
            assertEquals("执行了 note：x", SlashCommandDispatcher.dispatch("/an x", ctx))
        }

    @Test
    fun `未知命令返回提示`() =
        runBlocking {
            val result = SlashCommandDispatcher.dispatch("/nope", ctx)
            assertTrue(result.contains("未知命令"))
            assertTrue(result.contains("/nope"))
        }

    @Test
    fun `命令抛异常转为失败提示`() =
        runBlocking {
            ExtensionRegistry.register(
                object : TavernExtension, SlashCommandHandler {
                    override val id = "boom"
                    override val name = "boom"
                    override val loadingOrder = 10
                    override val description = "会炸"

                    override suspend fun execute(
                        args: String,
                        ctx: SlashCommandContext,
                    ): String = error("炸了")
                },
            )
            assertTrue(SlashCommandDispatcher.dispatch("/boom", ctx).contains("执行失败"))
        }

    @Test
    fun `help 命令列出已注册命令`() =
        runBlocking {
            ExtensionRegistry.register(com.mistbell.tavern.android.extension.builtin.HelpCommand())
            ExtensionRegistry.register(fakeCommand("echo"))
            val result = SlashCommandDispatcher.dispatch("/help", ctx)
            assertTrue(result.contains("/help"))
            assertTrue(result.contains("/echo"))
            assertTrue(result.contains("测试命令"))
        }
}
