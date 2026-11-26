package com.mistbell.tavern.android.data.mvu

import com.mistbell.tavern.android.data.local.dao.SettingsDao

/**
 * MVU 会话变量的 settings 表键名生成与清理（stat_data 与版本号两个键）。
 *
 * 收拢到 data 层小对象：删除/清空会话的清理路径（ChatRepository、ChatListViewModel）需要
 * 同款键名做删除，而 data.repository 不得反向依赖 extension——MvuExtension 改为引用此处，
 * 依赖方向保持单一（extension → data）。
 */
object MvuVariableKeys {
    /** stat_data JSON 键：`ext.mvu.<sessionId>` */
    fun statKey(sessionId: String) = "ext.mvu.$sessionId"

    /** 版本号键：`ext.mvu.ver.<sessionId>` */
    fun versionKey(sessionId: String) = "ext.mvu.ver.$sessionId"

    /** 删除一个会话的两个 MVU 变量键（删除/清空会话时调用），避免已删会话的变量残留库中 */
    suspend fun deleteSessionKeys(
        dao: SettingsDao,
        sessionId: String,
    ) {
        dao.deleteByKey(statKey(sessionId))
        dao.deleteByKey(versionKey(sessionId))
    }
}
