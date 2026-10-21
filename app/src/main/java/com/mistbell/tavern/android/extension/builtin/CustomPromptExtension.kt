package com.mistbell.tavern.android.extension.builtin

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.extension.PromptHookContext
import com.mistbell.tavern.android.extension.PromptInterceptor
import com.mistbell.tavern.android.extension.TavernExtension
import com.mistbell.tavern.android.util.MacroContext
import com.mistbell.tavern.android.util.MacroEngine

/**
 * 自定义提示词扩展：接通 custom_prompts 表（此前 UI/存储齐备但装配链路未读）。
 *
 * position 落点（对齐酒馆越狱/前后缀语义）：
 * - SYSTEM_START    → 插到提示词最前
 * - SYSTEM_END      → 插到最终用户消息之前
 * - USER_PREFIX     → 拼到最终用户消息内容之前
 * - USER_SUFFIX     → 拼到最终用户消息内容之后
 * - ASSISTANT_PREFIX→ 末尾追加 assistant 段（prefill）
 *
 * 契约：无启用条目时 segments 零改动（classic 提示词与接入前逐字节一致）。
 */
class CustomPromptExtension : TavernExtension, PromptInterceptor {
    override val id: String = "custom-prompts"
    override val name: String = "自定义提示词"

    // 排在内置拦截器默认位；后续扩展可用 loadingOrder 相对它前后排布
    override val loadingOrder: Int = DEFAULT_LOADING_ORDER

    override suspend fun intercept(
        segments: MutableList<PromptBuilder.Segment>,
        context: PromptHookContext,
    ) {
        val prompts = context.db.customPromptDao().getEnabled()
        if (prompts.isEmpty()) return
        applyCustomPrompts(segments, prompts, context.mctx)
    }

    companion object {
        /** 内置拦截器的默认加载顺序档位 */
        private const val DEFAULT_LOADING_ORDER = 100

        const val POSITION_SYSTEM_START = "SYSTEM_START"
        const val POSITION_SYSTEM_END = "SYSTEM_END"
        const val POSITION_USER_PREFIX = "USER_PREFIX"
        const val POSITION_USER_SUFFIX = "USER_SUFFIX"
        const val POSITION_ASSISTANT_PREFIX = "ASSISTANT_PREFIX"

        private const val SOURCE_PREFIX = "自定义提示词·"

        /**
         * 纯函数核心（JVM 单测直接覆盖，不经 Room）：
         * 调用方传入的列表预期已按 priority 排序（DAO 契约）；此处再按 isEnabled 过滤兜底。
         */
        fun applyCustomPrompts(
            segments: MutableList<PromptBuilder.Segment>,
            prompts: List<CustomPromptEntity>,
            mctx: MacroContext,
        ) {
            val enabled = prompts.filter { it.isEnabled }
            if (enabled.isEmpty()) return

            fun segmentOf(
                prompt: CustomPromptEntity,
                role: String,
            ): PromptBuilder.Segment? {
                val content = MacroEngine.render(prompt.content, mctx)
                if (content.isBlank()) return null
                return PromptBuilder.Segment(
                    message = ChatMessage(role = role, content = content),
                    source = "$SOURCE_PREFIX${prompt.name}",
                )
            }

            val byPosition = enabled.groupBy { it.position }

            // SYSTEM_START：按优先级顺序依序插到头部（插入点随已插条目后移，保持 DAO 排序）
            byPosition[POSITION_SYSTEM_START].orEmpty().forEachIndexed { index, prompt ->
                segmentOf(prompt, "system")?.let { segments.add(index, it) }
            }

            val lastUserIndex = segments.indexOfLast { it.message.role == "user" }

            // USER_PREFIX / USER_SUFFIX：直接改写最终用户消息段（用户消息恒在则必然命中）
            if (lastUserIndex >= 0) {
                val original = segments[lastUserIndex]
                val prefix =
                    byPosition[POSITION_USER_PREFIX].orEmpty()
                        .mapNotNull { segmentOf(it, "user")?.message?.content }
                        .joinToString("\n\n")
                val suffix =
                    byPosition[POSITION_USER_SUFFIX].orEmpty()
                        .mapNotNull { segmentOf(it, "user")?.message?.content }
                        .joinToString("\n\n")
                if (prefix.isNotBlank() || suffix.isNotBlank()) {
                    val content =
                        buildString {
                            if (prefix.isNotBlank()) append(prefix).append('\n')
                            append(original.message.content)
                            if (suffix.isNotBlank()) append('\n').append(suffix)
                        }
                    segments[lastUserIndex] =
                        original.copy(message = original.message.copy(content = content))
                }
            }

            // SYSTEM_END：插到最终用户消息之前；无用户消息（群聊推动语场景）时追加到末尾
            val systemEnd =
                byPosition[POSITION_SYSTEM_END].orEmpty()
                    .mapNotNull { segmentOf(it, "system") }
            if (systemEnd.isNotEmpty()) {
                if (lastUserIndex >= 0) {
                    segments.addAll(lastUserIndex, systemEnd)
                } else {
                    segments.addAll(systemEnd)
                }
            }

            // ASSISTANT_PREFIX：末尾 prefill 段
            byPosition[POSITION_ASSISTANT_PREFIX].orEmpty().forEach { prompt ->
                segmentOf(prompt, "assistant")?.let { segments.add(it) }
            }
        }
    }
}
