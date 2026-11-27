package com.mistbell.tavern.android.ui.chat

import com.mistbell.tavern.android.data.repository.InjectedMemoriesEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一轮 AI 回复**实际注入**的会话记忆清单（「记忆可见 v0」，docs/ROADMAP.md P2 第 4 项）。
 *
 * 数据与发送时那一次提示词装配同源（ChatRepository 的 InjectedMemoriesEvent），**只进内存、
 * 不落库**：进程重启后旧消息不再显示标记，这是 v0 的已知限制；切换会话同样清空——
 * 旧会话的清单不会在回来时复活。
 */
data class InjectedMemories(
    /** 本轮注入的结构化记忆 id（字符串化的 structured_memory.id），「记得 N 件」的 N 就是它 */
    val structuredMemoryIds: List<String>,
    /** 本轮回顾的历史消息 id（只读展示，与结构化记忆是两类信息，不影响标记） */
    val recallMessageIds: List<String>,
)

/**
 * 「记得 N 件」的 N：本轮注入的结构化记忆条数。
 * 没有清单或没有结构化记忆时返回 null——召回的历史行再多也不构成标记（规划原文语义）。
 */
internal fun injectedMemoryBadgeCount(injected: InjectedMemories?): Int? {
    val ids = injected?.structuredMemoryIds ?: return null
    return ids.size.takeIf { it > 0 }
}

/**
 * 按消息 id 记账的内存映射（assistant 消息 id → [InjectedMemories]）。
 *
 * 写入只认**当前会话**的事件：生成结束后的迟到事件若落在本页已切走的会话上，直接丢弃，
 * 防止账本串会话；会话切换即清空（与提示词预览的清理同语义）。忘记一条结构化记忆时把它
 * 从**所有**消息的清单里移除——它下一轮不会再被注入，标记不该继续谎报。
 */
internal class InjectedMemoriesStore {
    private val _state = MutableStateFlow<Map<String, InjectedMemories>>(emptyMap())
    val state: StateFlow<Map<String, InjectedMemories>> = _state.asStateFlow()

    /** 当前会话 id（ChatViewModel 随 _activeSessionId 推送）：既是过滤条件也是清空信号 */
    private var currentSessionId: String = ""

    /** 会话切换：清空账本并记录新会话（空串 = 无会话，所有事件都被忽略） */
    fun onSessionChanged(sessionId: String) {
        currentSessionId = sessionId
        _state.value = emptyMap()
    }

    /** 收到一轮注入事件：仅当前会话、assistant 消息 id 有效时入账（同 id 重发按新内容覆盖） */
    fun onEvent(event: InjectedMemoriesEvent) {
        if (event.sessionId.isBlank() || event.sessionId != currentSessionId) return
        if (event.assistantMessageId.isBlank()) return
        val entry =
            InjectedMemories(
                structuredMemoryIds = event.structuredMemoryIds,
                recallMessageIds = event.recallMessageIds,
            )
        _state.value = _state.value + (event.assistantMessageId to entry)
    }

    /** 忘记一条结构化记忆：从所有消息的注入清单里移除（召回历史行是消息 id，不在此列） */
    fun forgetMemory(memoryId: String) {
        _state.value =
            _state.value.mapValues { (_, injected) ->
                injected.copy(structuredMemoryIds = injected.structuredMemoryIds - memoryId)
            }
    }
}
