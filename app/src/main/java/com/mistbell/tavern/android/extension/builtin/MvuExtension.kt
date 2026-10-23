package com.mistbell.tavern.android.extension.builtin

import com.mistbell.tavern.android.data.mvu.MvuEngine
import com.mistbell.tavern.android.extension.ReplyHookContext
import com.mistbell.tavern.android.extension.ReplyPostProcessor
import com.mistbell.tavern.android.extension.TavernEvent
import com.mistbell.tavern.android.extension.TavernEventBus
import com.mistbell.tavern.android.extension.TavernExtension
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * MVU 扩展：AI 回复落库前应用 UpdateVariable patch 到会话级 stat_data，
 * 剥离块后回传干净文本，并发射 [TavernEvent.VariablesUpdated] 供 HTML 界面刷新。
 *
 * 存储：settings 表 `ext.mvu.<sessionId>`（stat_data JSON）+ `ext.mvu.ver.<sessionId>`（版本号）。
 * 净室自研：行为契约逆向自卡片可观察格式，不复制 MagVarUpdate 任何代码。
 */
class MvuExtension : TavernExtension, ReplyPostProcessor {
    override val id: String = "mvu"
    override val name: String = "MVU 变量更新"
    override val loadingOrder: Int = LOADING_ORDER

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun process(
        reply: String,
        context: ReplyHookContext,
    ): String {
        if (!MvuEngine.hasUpdateBlock(reply)) return reply
        val db = com.mistbell.tavern.android.TavernApplication.instance.container.database
        val statKey = statKey(context.sessionId)
        val verKey = versionKey(context.sessionId)
        val current =
            db.settingsDao().getValue(statKey)
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: JsonObject(emptyMap())

        val result = MvuEngine.process(reply, current)
        if (result.opsApplied > 0) {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(statKey, result.statData.toString()),
            )
            val version = (db.settingsDao().getValue(verKey)?.toLongOrNull() ?: 0L) + 1
            db.settingsDao().upsert(
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

        fun statKey(sessionId: String) = "ext.mvu.$sessionId"

        fun versionKey(sessionId: String) = "ext.mvu.ver.$sessionId"
    }
}
