package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 全局正则脚本实体：跨角色生效的正则规则，对标酒馆 regex 扩展的 global script
 * （SCRIPT_TYPES.GLOBAL，先于角色卡 scoped script 执行）。
 *
 * 字段与卡级 regex_scripts 的酒馆格式一一对齐；placement 与 trimStrings 以 JSON
 * 数组文本存储，与卡内格式同构，后续导出可直接复用同一序列化。
 */
@Entity(tableName = "global_regex_rules")
data class GlobalRegexRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "find_regex") val findRegex: String,
    @ColumnInfo(name = "replace_string") val replaceString: String,
    val disabled: Boolean = false,
    /** 酒馆 placement 数组 JSON（1=用户输入 2=AI 输出），如 "[1,2]" */
    @ColumnInfo(name = "placement_json") val placementJson: String = "[2]",
    /** trimStrings 数组 JSON（替换前从捕获组中剔除的子串） */
    @ColumnInfo(name = "trim_strings_json") val trimStringsJson: String = "[]",
    @ColumnInfo(name = "markdown_only") val markdownOnly: Boolean = false,
    @ColumnInfo(name = "prompt_only") val promptOnly: Boolean = false,
    @ColumnInfo(name = "run_on_edit") val runOnEdit: Boolean = false,
    /** null=不限深度；minDepth 有效下界 -1，maxDepth 有效下界 0（对齐酒馆语义） */
    @ColumnInfo(name = "min_depth") val minDepth: Int? = null,
    @ColumnInfo(name = "max_depth") val maxDepth: Int? = null,
    /** 列表顺序即执行顺序（升序） */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
)
