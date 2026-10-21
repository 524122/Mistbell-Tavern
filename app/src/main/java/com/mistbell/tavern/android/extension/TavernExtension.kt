package com.mistbell.tavern.android.extension

import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.util.MacroContext

/**
 * 扩展基接口（对标酒馆 manifest + hooks.activate）。
 *
 * 扩展是实现本接口的普通 Kotlin 类，按需再实现各钩子接口
 * （[PromptInterceptor] 等），由 [ExtensionRegistry] 统一注册与分发。
 */
interface TavernExtension {
    /** 唯一 ID（小写，注册表按此去重） */
    val id: String

    /** 显示名 */
    val name: String

    /** 加载顺序（对标酒馆 loading_order）：钩子按此升序执行 */
    val loadingOrder: Int

    /** 注册后回调（对标 hooks.activate） */
    fun activate() {}
}

/** Prompt 钩子上下文：拦截器可读会话身份、宏上下文与数据库。 */
data class PromptHookContext(
    val ownerId: String,
    val characterId: String,
    val sessionId: String,
    val mctx: MacroContext,
    /** 惰性取库：不经 db 的拦截器（如规则包纯数据注入）在 JVM 单测中可安全构造本上下文 */
    val getDb: () -> AppDatabase,
)

/**
 * 生成前钩子（对标酒馆 generate_interceptor + setExtensionPrompt）：
 * 在提示词装配完成后、发送前执行，可增删改 [PromptBuilder.Segment]。
 * 改动同时反映到真实请求与「查看提示词」预览（同一装配路径）。
 */
interface PromptInterceptor {
    suspend fun intercept(
        segments: MutableList<PromptBuilder.Segment>,
        context: PromptHookContext,
    )
}

// ---- 预留钩子接口（本次不接线，后续迭代接入） ----

/** 回复后处理（对标酒馆 regex 扩展的输出替换）：回复落库前逐链处理。 */
interface ReplyPostProcessor {
    suspend fun process(
        reply: String,
        context: ReplyHookContext,
    ): String
}

data class ReplyHookContext(
    val ownerId: String,
    val characterId: String,
    val sessionId: String,
)

/** 宏提供器（对标酒馆 macros.register）：解析未知宏名，未命中返回 null。 */
interface MacroProvider {
    fun resolve(
        name: String,
        ctx: MacroContext,
    ): String?
}

/** 斜杠命令（对标酒馆 SlashCommand）：输入框 `/name args` 分发执行。 */
interface SlashCommandHandler {
    val name: String
    val aliases: List<String> get() = emptyList()
    val description: String

    /** 执行命令，返回展示给用户的文本结果。 */
    suspend fun execute(
        args: String,
        ctx: SlashCommandContext,
    ): String
}

data class SlashCommandContext(
    val ownerId: String,
    val characterId: String,
    val sessionId: String,
)
