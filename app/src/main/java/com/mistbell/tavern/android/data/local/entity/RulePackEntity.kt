package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 规则包实体：声明式扩展包（提示词块/输出正则/宏，zip 导入），纯数据、无代码执行。
 *
 * 包内容整体序列化存 [contentJson]（RulePackContent），不落文件——包内无资源文件，
 * 与主题包（有背景图）不同。
 */
@Entity(tableName = "rule_packs")
data class RulePackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String,
    val version: String,
    /** 包级加载顺序（对齐酒馆 loading_order）：多包同启用时按此升序应用 */
    @ColumnInfo(name = "loading_order") val loadingOrder: Int = 100,
    /** 启用开关：禁用即所有规则不生效，即时生效无需重启 */
    val enabled: Boolean = true,
    /** RulePackContent 序列化 JSON（prompts/regex/macros 三节） */
    @ColumnInfo(name = "content_json") val contentJson: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
