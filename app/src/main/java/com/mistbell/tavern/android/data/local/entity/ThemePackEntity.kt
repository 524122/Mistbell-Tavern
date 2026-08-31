package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// T1 皮肤级主题包：纯数据 tokens，无代码执行
@Entity(tableName = "theme_packs")
data class ThemePackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String,
    val version: String,
    @ColumnInfo(name = "tokens_json") val tokensJson: String,
    @ColumnInfo(name = "background_file") val backgroundFile: String?,  // 可空：zip 内 assets/<名>
    @ColumnInfo(name = "created_at") val createdAt: String
)
