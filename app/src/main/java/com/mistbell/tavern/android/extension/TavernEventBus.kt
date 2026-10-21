package com.mistbell.tavern.android.extension

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 扩展事件总线（对标酒馆 eventSource）。
 *
 * SharedFlow 无缓冲挂起语义：extraBufferCapacity 兜底发射方，溢出丢最旧——
 * 事件是通知而非数据，丢失旧事件优于阻塞聊天链路。
 */
object TavernEventBus {
    private const val BUFFER_CAPACITY = 64

    private val _events =
        MutableSharedFlow<TavernEvent>(
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    /** 订阅入口：`TavernEventBus.events.collect { ... }` */
    val events: SharedFlow<TavernEvent> = _events.asSharedFlow()

    suspend fun emit(event: TavernEvent) {
        _events.emit(event)
    }

    /** 非挂起发射（供不便挂起的回调点）；缓冲满时丢弃而非失败。 */
    fun tryEmit(event: TavernEvent) {
        _events.tryEmit(event)
    }
}
