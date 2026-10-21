package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.QuickReplyEntity
import com.mistbell.tavern.android.data.rulepack.RulePackQuickReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 快捷回复聚合：全局用户自定义（quick_replies 表）+ 启用规则包的 quickreplies 节。
 * 顺序：全局在前（用户自己的优先），规则包在后（按包 loadingOrder，缓存已排好序）。
 */
class QuickReplyRepository {
    private val db get() = TavernApplication.instance.container.database

    /** UI 消费的统一视图（全局 + 启用规则包），来源标签供 UI 区分展示 */
    data class ActiveQuickReply(
        val id: String,
        val label: String,
        val action: String,
        val payload: String,
        /** 来源：null=全局自定义，否则为规则包名 */
        val packName: String? = null,
    )

    fun observeGlobal(): Flow<List<QuickReplyEntity>> = db.quickReplyDao().observeAll()

    fun observeActive(): Flow<List<ActiveQuickReply>> =
        combine(
            db.quickReplyDao().observeAll(),
            TavernApplication.instance.container.rulePackCache.packs,
        ) { global, packs ->
            aggregate(global, packs.orEmpty())
        }

    suspend fun upsert(item: QuickReplyEntity) =
        withContext(Dispatchers.IO) {
            db.quickReplyDao().upsert(item)
        }

    suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            db.quickReplyDao().deleteById(id)
        }

    fun newId(): String = UUID.randomUUID().toString()

    companion object {
        /** 纯函数聚合核心（JVM 单测直测）：全局在前、规则包在后，禁用条目不出现 */
        fun aggregate(
            global: List<QuickReplyEntity>,
            enabledPacks: List<EnabledRulePack>,
        ): List<ActiveQuickReply> {
            val globalItems =
                global.map {
                    ActiveQuickReply(it.id, it.label, it.action, it.payload)
                }
            val packItems =
                enabledPacks.flatMap { pack ->
                    pack.content.quickReplies
                        .filter { it.enabled }
                        .map { it.toActive(pack) }
                }
            return globalItems + packItems
        }

        private fun RulePackQuickReply.toActive(pack: EnabledRulePack) =
            ActiveQuickReply(
                id = "${pack.id}:$label",
                label = label,
                action = action.lowercase(),
                payload = payload,
                packName = pack.name,
            )
    }
}
