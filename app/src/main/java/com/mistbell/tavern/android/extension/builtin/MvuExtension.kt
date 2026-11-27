package com.mistbell.tavern.android.extension.builtin

import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.model.enabledCapabilities
import com.mistbell.tavern.android.data.mvu.MvuEngine
import com.mistbell.tavern.android.data.mvu.MvuVariableKeys
import com.mistbell.tavern.android.extension.ReplyHookContext
import com.mistbell.tavern.android.extension.ReplyPostProcessor
import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import com.mistbell.tavern.android.extension.TavernExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * MVU 扩展：AI 回复落库前应用 UpdateVariable patch 到会话级 stat_data，
 * 剥离块后回传干净文本，并发射 [TavernEvent.VariablesUpdated] 供 HTML 界面刷新。
 *
 * 存储：settings 表 `ext.mvu.<sessionId>`（stat_data JSON）+ `ext.mvu.ver.<sessionId>`（版本号）。
 * 净室自研：行为契约逆向自卡片可观察格式，不复制 MagVarUpdate 任何代码。
 *
 * P3 执行边界（docs/UI_MODE.md）：[MvuEngine.process] 照常调用并返回它的 cleanedText——
 * 不新写剥离器，也绝不在 process 之前直接返回原文（块会落库并送给模型）；简洁模式只
 * 跳过「写库 + 发事件」，且连读取也不做（每次 process 时取模式，切换后下一条回复即生效）。
 * 模式流不给确定值（启动头几毫秒模式未知）时 `first()` 挂起等待——绝不按「未知即高级」放行。
 */
class MvuExtension(
    /**
     * 界面模式流（元素非空；缺省恒为高级）：默认实现读 AppContainer 的进程级
     * uiMode.filterNotNull()；测试注入 flowOf(固定值)。
     */
    private val modeProvider: () -> Flow<UiMode> = { flowOf(UiMode.ADVANCED) },
    /** settings 表读写口（默认走生产库；测试注入内存假件） */
    private val settingsDaoProvider: () -> SettingsDao = {
        com.mistbell.tavern.android.TavernApplication.instance.container.database.settingsDao()
    },
) : TavernExtension, ReplyPostProcessor {
    override val id: String = "mvu"
    override val name: String = "MVU 变量更新"
    override val loadingOrder: Int = LOADING_ORDER

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun process(
        reply: String,
        context: ReplyHookContext,
    ): String {
        if (!MvuEngine.hasUpdateBlock(reply)) return reply
        val updatesAllowed = enabledCapabilities(modeProvider().first()).mvuVariableUpdates
        val dao = settingsDaoProvider()
        val statKey = statKey(context.sessionId)
        val verKey = versionKey(context.sessionId)
        val current =
            if (updatesAllowed) {
                dao.getValue(statKey)
                    ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    ?: JsonObject(emptyMap())
            } else {
                JsonObject(emptyMap())
            }

        val result = MvuEngine.process(reply, current)
        if (updatesAllowed && result.opsApplied > 0) {
            dao.upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(statKey, result.statData.toString()),
            )
            val version = (dao.getValue(verKey)?.toLongOrNull() ?: 0L) + 1
            dao.upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(verKey, version.toString()),
            )
            TavernEventBus.emit(TavernEvent.VariablesUpdated(context.sessionId, version))
        }
        result.warnings.forEach { android.util.Log.w("Mvu", it) }
        return result.cleanedText
    }

    companion object {
        /** 排在回复后处理链的中段（规则包 200 之后）：变量更新先看原始回复 */
        private const val LOADING_ORDER = 400

        // 键名生成收拢到 data 层（ChatRepository 删除/清空会话时按同款键名清理，
        // 且 data.repository 不得反向依赖 extension），此处委托保持既有调用方兼容
        fun statKey(sessionId: String) = MvuVariableKeys.statKey(sessionId)

        fun versionKey(sessionId: String) = MvuVariableKeys.versionKey(sessionId)
    }
}
