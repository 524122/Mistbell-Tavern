package com.mistbell.tavern.android.extension

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRegistryTest {
    @After
    fun tearDown() {
        ExtensionRegistry.resetForTesting()
    }

    private fun ext(
        id: String,
        order: Int,
    ) = object : TavernExtension {
        override val id = id
        override val name = id
        override val loadingOrder = order
    }

    @Test
    fun `注册后按 loadingOrder 升序排列`() {
        ExtensionRegistry.register(ext("c", 30))
        ExtensionRegistry.register(ext("a", 10))
        ExtensionRegistry.register(ext("b", 20))
        assertEquals(listOf("a", "b", "c"), ExtensionRegistry.all.map { it.id })
    }

    @Test
    fun `同 id 重复注册被忽略（幂等）`() {
        ExtensionRegistry.register(ext("dup", 10))
        ExtensionRegistry.register(ext("dup", 99))
        assertEquals(1, ExtensionRegistry.all.size)
        assertEquals(10, ExtensionRegistry.all.first().loadingOrder)
    }

    @Test
    fun `promptInterceptors 只含实现了 PromptInterceptor 的扩展`() {
        val interceptor =
            object : TavernExtension, PromptInterceptor {
                override val id = "interceptor"
                override val name = "interceptor"
                override val loadingOrder = 10

                override suspend fun intercept(
                    segments: MutableList<com.mistbell.tavern.android.data.prompt.PromptBuilder.Segment>,
                    context: PromptHookContext,
                ) = Unit
            }
        ExtensionRegistry.register(ext("plain", 5))
        ExtensionRegistry.register(interceptor)
        assertEquals(listOf("interceptor"), ExtensionRegistry.promptInterceptors.map { (it as TavernExtension).id })
    }

    @Test
    fun `unregister 按 id 移除`() {
        ExtensionRegistry.register(ext("x", 1))
        ExtensionRegistry.unregister("x")
        assertTrue(ExtensionRegistry.all.isEmpty())
    }
}
