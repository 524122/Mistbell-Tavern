package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 全局快捷回复（用户自定义）：聊天输入区上方的可点击按钮。
 * action：send（直接发送）/ insert（填入输入框）/ command（斜杠命令分发）。
 */
@Entity(tableName = "quick_replies")
data class QuickReplyEntity(
    @PrimaryKey val id: String,
    val label: String,
    val action: String,
    val payload: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)
