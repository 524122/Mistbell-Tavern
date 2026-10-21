package com.mistbell.tavern.android.extension

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 扩展注册表（object 单例，与 MacroEngine 风格一致）。
 *
 * 用单例而非经 AppContainer 注入：PromptBuilder / ChatRepository 均为 object 或轻构造，
 * 注入会牵动全部调用点；内置扩展在应用启动时注册一次，此后钩子链路全局可见。
 *
 * 线程安全：CopyOnWriteArrayList——注册极少发生（启动期），读取极频繁（每轮生成）。
 */
object ExtensionRegistry {
    private val extensions = CopyOnWriteArrayList<TavernExtension>()

    /** 全部已注册扩展（按 loadingOrder 升序） */
    val all: List<TavernExtension> get() = extensions.toList()

    /** 已注册的生成前拦截器视图（按 loadingOrder 升序） */
    val promptInterceptors: List<PromptInterceptor>
        get() = extensions.filterIsInstance<PromptInterceptor>()

    /** 已注册的回复后处理器视图（按 loadingOrder 升序） */
    val replyPostProcessors: List<ReplyPostProcessor>
        get() = extensions.filterIsInstance<ReplyPostProcessor>()

    /** 已注册的宏提供器视图（按 loadingOrder 升序） */
    val macroProviders: List<MacroProvider>
        get() = extensions.filterIsInstance<MacroProvider>()

    /** 已注册的斜杠命令视图（按 loadingOrder 升序） */
    val slashCommands: List<SlashCommandHandler>
        get() = extensions.filterIsInstance<SlashCommandHandler>()

    /** 按命令名或别名查找斜杠命令（大小写不敏感） */
    fun findSlashCommand(nameOrAlias: String): SlashCommandHandler? {
        val key = nameOrAlias.lowercase()
        return slashCommands.firstOrNull {
            it.name.lowercase() == key || it.aliases.any { alias -> alias.lowercase() == key }
        }
    }

    /**
     * 注册扩展：同 id 重复注册直接忽略（幂等，启动重入安全）；
     * 插入后保持 loadingOrder 升序，并回调 [TavernExtension.activate]。
     */
    fun register(extension: TavernExtension) {
        if (extensions.any { it.id == extension.id }) return
        val index = extensions.indexOfFirst { it.loadingOrder > extension.loadingOrder }
        if (index < 0) extensions.add(extension) else extensions.add(index, extension)
        extension.activate()
    }

    fun unregister(extensionId: String) {
        extensions.removeAll { it.id == extensionId }
    }

    /** 测试用：清空注册表，避免用例间串染。 */
    internal fun resetForTesting() {
        extensions.clear()
    }
}
