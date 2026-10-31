package com.mistbell.tavern.android.ui.chat.htmlui

import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天事件宿主推送链（TavernEventBus → VM 收集 → 页面推送）的流层复现与语义锁定。
 *
 * 端到端实测断裂（generate() 后页面 0 条事件，页面侧 eventOn/__tavernEmit 自测全通）：
 * 根因在旧构造的两个流层缺陷，本类以同构 JVM 管线复现——
 * 1. `stateIn(WhileSubscribed(5000))`：TavernEventBus 是 replay=0 的 SharedFlow，
 *    订阅停止窗口（无订阅者 / 5s 过期后 / 共享协程启动竞速）内发射的事件被静默丢弃且
 *    永久丢失——extraBufferCapacity 只缓冲"慢订阅者"，不为迟到的订阅者保留事件；
 * 2. 单值 StateFlow（最新一条）：generation_started/ended 毫秒级成对发射时被重组并帧，
 *    页面可能一条都收不到。
 *
 * 修复语义（常驻收集 + 有界事件队列 + 页面侧按序号游标补推）在第三个测试中锁定。
 */
class ChatEventPushChainTest {
    // ---- 根因复现 1：WhileSubscribed 停止窗口内发射 → 永久丢失 ----

    @Test
    fun `复现 WhileSubscribed 停止窗口内发射的事件永久丢失`() =
        runTest {
            val bus =
                MutableSharedFlow<String>(
                    replay = 0,
                    extraBufferCapacity = 64,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            // 与旧 htmlChatEvent 同构：stateIn + WhileSubscribed(5000)
            val sharer = bus.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), "init")

            // 订阅激活期：事件正常到达
            val phase1 = mutableListOf<String>()
            val job1 = launch { sharer.collect { phase1.add(it) } }
            runCurrent()
            bus.tryEmit("a")
            runCurrent()
            assertEquals(listOf("init", "a"), phase1)

            // 订阅者离开，5s 停止窗口（虚拟时间）走完 → 上游收集停止
            job1.cancel()
            advanceTimeBy(5_500)
            runCurrent()

            // 停止窗口内发射：事件被静默丢弃（replay=0 不为迟到订阅者保留）
            bus.tryEmit("b")
            runCurrent()

            // 重新订阅：只看到旧值 "a"，"b" 永久丢失——页面侧表现为"0 条事件到达"
            val phase2 = mutableListOf<String>()
            val job2 = launch { sharer.collect { phase2.add(it) } }
            runCurrent()
            assertEquals(listOf("a"), phase2)
            job2.cancel()
        }

    // ---- 根因复现 2：TavernEventBus 无订阅者时发射静默丢失；常驻收集者激活后恢复 ----

    @Test
    fun `复现 TavernEventBus 无订阅者发射静默丢失 常驻收集者激活后恢复接收`() =
        runTest {
            // 无任何订阅者：emit 成功但事件无接收方（生成链路 emit 均不报错，页面侧无从得知）
            val lost = launch { TavernEventBus.emit(TavernEvent.GenerationStarted("s-lost")) }
            lost.join()
            runCurrent()

            // 常驻收集者（与修复后 ChatViewModel init 同构）激活后，后续事件按序到达
            val received = mutableListOf<TavernEvent>()
            val job = launch { TavernEventBus.events.collect { received.add(it) } }
            runCurrent()
            TavernEventBus.emit(TavernEvent.GenerationStarted("s-live"))
            TavernEventBus.emit(TavernEvent.GenerationEnded("s-live"))
            runCurrent()
            job.cancel()

            assertEquals(2, received.size)
            assertTrue(received[0] is TavernEvent.GenerationStarted)
            assertTrue(received[1] is TavernEvent.GenerationEnded)
        }

    // ---- 修复语义锁定：常驻收集 + 有界队列（逐条缓冲、过滤、版本严格递增） ----

    @Test
    fun `常驻收集链路 快速成对发射逐条入队 过滤其他会话与变量事件 版本严格递增`() =
        runTest {
            val buffered = mutableListOf<HtmlFacadeContract.HtmlChatEvent>()
            var version = 0L
            val activeSession = "s-active"

            // 修复后构造：与 VM 同生命周期的常驻收集（无 WhileSubscribed 停止窗口），
            // 命中的事件逐条追加进队列（单值 StateFlow 的并帧问题不复存在）
            val job =
                launch {
                    TavernEventBus.events.collect { event ->
                        version += 1
                        HtmlFacadeContract
                            .toHtmlChatEvent(event, activeSession, version)
                            ?.let { buffered.add(it) }
                    }
                }
            runCurrent()

            // 模拟 generate 失败路径：started/ended 毫秒级成对发射，
            // 并混入其他会话事件与变量更新事件（后者走 MVU 专属推送）
            TavernEventBus.emit(TavernEvent.GenerationStarted(activeSession))
            TavernEventBus.emit(TavernEvent.GenerationEnded(activeSession))
            TavernEventBus.emit(TavernEvent.GenerationStarted("s-other"))
            TavernEventBus.emit(TavernEvent.VariablesUpdated(activeSession, 1))
            runCurrent()
            job.cancel()

            // 成对事件逐条入队（不被并帧吞掉）、其他会话与变量事件被过滤、版本严格递增
            assertEquals(
                listOf(
                    HtmlFacadeContract.EVENT_GENERATION_STARTED,
                    HtmlFacadeContract.EVENT_GENERATION_ENDED,
                ),
                buffered.map { it.name },
            )
            assertEquals(listOf(1L, 2L), buffered.map { it.version })
            assertTrue(buffered.all { it.payloadJson.contains(activeSession) })
        }

    // ---- 页面侧游标语义（纯数据层模拟）：挂载对齐不重放、增量补推不重复 ----

    @Test
    fun `页面侧序号游标 挂载对齐到队列末尾 队列增长时只补推新事件`() {
        // 挂载时队列已有 3 条：游标对齐末尾，不重放旧事件（事件是通知而非状态）
        val existing =
            listOf(
                HtmlFacadeContract.HtmlChatEvent(1, "message_sent", "{}"),
                HtmlFacadeContract.HtmlChatEvent(2, "message_received", "{}"),
                HtmlFacadeContract.HtmlChatEvent(5, "generation_ended", "{}"),
            )
        var lastPushedSeq = existing.lastOrNull()?.version ?: Long.MIN_VALUE
        val pushedAtMount = simulateSurfaceDrain(existing, lastPushedSeq) { lastPushedSeq = it }
        assertTrue(pushedAtMount.isEmpty())

        // 队列追加 2 条（第 4 条序号 6、第 5 条序号 7）：只补推新事件，按序各推一次
        val grown =
            existing +
                HtmlFacadeContract.HtmlChatEvent(6, "generation_started", "{}") +
                HtmlFacadeContract.HtmlChatEvent(7, "generation_ended", "{}")
        val pushedAfterGrow = simulateSurfaceDrain(grown, lastPushedSeq) { lastPushedSeq = it }
        assertEquals(listOf("generation_started", "generation_ended"), pushedAfterGrow.map { it.name })

        // 同一队列再次观察（重组合并未变）：游标已对齐，零重复推送
        val pushedAgain = simulateSurfaceDrain(grown, lastPushedSeq) { lastPushedSeq = it }
        assertTrue(pushedAgain.isEmpty())
    }

    /** 页面侧推送的纯数据模拟：把 seq 大于游标的条目逐条"推送"，返回推送列表（对齐 evaluateJavascript 循环）。 */
    private fun simulateSurfaceDrain(
        events: List<HtmlFacadeContract.HtmlChatEvent>,
        lastPushedSeq: Long,
        advance: (Long) -> Unit,
    ): List<HtmlFacadeContract.HtmlChatEvent> {
        val pushed = mutableListOf<HtmlFacadeContract.HtmlChatEvent>()
        events.forEach { event ->
            if (event.version > lastPushedSeq) {
                pushed.add(event)
                advance(event.version)
            }
        }
        return pushed
    }
}
