package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 扩展包实体（P1 管理平面）：manifest 元数据 + 权限启停状态落库；
 * 内容文件（manifest.json/JS 制品）落 filesDir/extensions/<id>/（主题包目录模型，
 * 规则包的 content_json 单字段模型不适合多文件制品——EXTENSION_PLATFORM_DESIGN.md §3）。
 *
 * P1 无运行时消费方（预注入/宿主钩子是 P2）：本表只驱动管理页列表与启停持久化。
 */
@Entity(tableName = "extension_packs")
data class ExtensionPackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String,
    val version: String,
    /** 包级加载顺序（对齐酒馆 loading_order）：多包启用时按此升序注入/应用（P2 消费） */
    @ColumnInfo(name = "loading_order") val loadingOrder: Int = 100,
    /** 权限声明 JSON 数组（manifest.permissions 原样，PermissionCatalog 可解析回中文元数据） */
    @ColumnInfo(name = "permissions_json") val permissionsJson: String = "[]",
    /** provides 提供者声明 JSON 数组（manifest.provides 原样；同名互斥语义见 ExtensionPackStore） */
    @ColumnInfo(name = "provides_json") val providesJson: String = "[]",
    /** manifest.webviewPreload 是否声明（文件本体在 filesDir，表里只留布尔供列表徽标） */
    @ColumnInfo(name = "has_preload") val hasPreload: Boolean = false,
    /** manifest.hostHooks 是否声明 */
    @ColumnInfo(name = "has_hooks") val hasHooks: Boolean = false,
    /**
     * 启用开关：仅 [permissionsConfirmed] 为 true 时允许置 true（仓库层强制，
     * 覆盖安装权限集扩大未再确认时强制回 false）。跨重启持久（Room）。
     */
    val enabled: Boolean = false,
    /**
     * 权限清单是否已经用户确认（导入确认对话框「信任并导入」置位；覆盖安装权限集
     * 扩大且未再确认则复位）。false 时 UI 禁用启停开关并显示「未授权」。
     */
    @ColumnInfo(name = "permissions_confirmed") val permissionsConfirmed: Boolean = false,
    /**
     * 官方内置包标记：随产品发布由启动种子落库（is_builtin=1 且初始 enabled/confirmed=true——
     * 官方包随产品发布等同用户已同意；第三方包绝不走此路径，恒为 false）。
     * 用户可停用/删除内置包：删除后下次启动重播种（它是产品功能），停用态保留。
     */
    @ColumnInfo(name = "is_builtin") val isBuiltin: Boolean = false,
    /** zip 字节 SHA-256 十六进制：覆盖安装比对（同 id 同 hash 仅刷新元数据，不重解压） */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "installed_at") val installedAt: String,
)
