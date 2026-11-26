package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// T1 皮肤级主题包：纯数据 tokens，无代码执行；M4/M6 起支持 layout 能力（template.html/style.css）
@Entity(tableName = "theme_packs")
@Serializable
data class ThemePackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String,
    val version: String,
    @ColumnInfo(name = "tokens_json") val tokensJson: String,
    // 可空：zip 内 assets/<名>
    @ColumnInfo(name = "background_file") val backgroundFile: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    // 能力声明（manifest.capabilities）：cosmetic = 存量换肤包；layout = 携带 template.html/style.css 走渲染引擎
    @ColumnInfo(name = "capabilities", defaultValue = "cosmetic") val capabilities: String = "cosmetic",
    // 目标模板版本（manifest.templateVersion）：v0 冻结为 1，不支持的版本引擎拒绝启用
    @ColumnInfo(name = "template_version", defaultValue = "1") val templateVersion: Int = 1,
)
