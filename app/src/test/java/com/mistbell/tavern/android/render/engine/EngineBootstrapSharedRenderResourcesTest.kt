package com.mistbell.tavern.android.render.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎共享渲染资源的 JVM 契约（skia 渲染本体不可 JVM 单测，这里只钉可纯 JVM 观察的部分）：
 * 1) 共享位图缓存是进程级单例——多次获取返回同一实例（跨渲染复用，不再每渲染新建 24MB 缓存）；
 * 2) 未引导（JVM 下 EngineBootstrap.init 未被调用）时 textMeasurer 保持 null = fallback 语义；
 * 3) 渲染调度器串行：并发提交多个任务，任意时刻在跑的至多一个（limitedParallelism(1)）——
 *    这是共享 skia 对象与缓存不被并发访问的前提。
 */
class EngineBootstrapSharedRenderResourcesTest {
    @Test
    fun `共享位图缓存跨渲染复用_多次获取返回同一实例`() {
        assertSame(EngineBootstrap.bitmapCache, EngineBootstrap.bitmapCache)
    }

    @Test
    fun `未引导时共享文本测量器保持null走fallback语义`() {
        // JVM 测试进程从未调用 EngineBootstrap.init：fontCollection 为 null → 测量器不构建
        assertNull(EngineBootstrap.fontCollection)
        assertNull(EngineBootstrap.textMeasurer)
    }

    @Test
    fun `渲染调度器串行_并发提交任务最大并发度为1`() =
        runBlocking {
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            // 每个任务进临界区后**不挂起地**占住执行线程几毫秒：若调度器不串行，
            // 其余任务会并发进入抬高峰值；delay 类挂起会让出线程，测不出执行串行性
            val jobs =
                (1..8).map {
                    launch(EngineBootstrap.renderDispatcher) {
                        val now = inFlight.incrementAndGet()
                        maxInFlight.updateAndGet { current -> maxOf(current, now) }
                        val deadline = System.nanoTime() + 5_000_000L
                        while (System.nanoTime() < deadline) {
                            // 自旋占住线程（非挂起），串行调度器期间不会放行下一个任务
                        }
                        inFlight.decrementAndGet()
                    }
                }
            jobs.forEach { it.join() }
            assertEquals("任意时刻至多一个任务在执行", 1, maxInFlight.get())
        }
}
