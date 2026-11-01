package com.mistbell.tavern.android.data.extensionpack

/**
 * 扩展包权限目录（P1 管理平面）。
 *
 * 为什么是严格白名单：扩展的宿主能力面（提示词注入/消息读写/生成控制/网络等）远大于规则包，
 * 静默放行未知权限会让「安装时确认」形同虚设——manifest 声明了目录外的权限即导入解析失败，
 * 给出明确原因（EXTENSION_PLATFORM_DESIGN.md §4）。
 *
 * 两种形态：
 * - 固定权限：`prompt:inject` 等 7 个具名权限，逐条登记中文展示名与说明；
 * - 动态权限：`network:<host>`，host 必须是合法 hostname（小写字母/数字/点/连字符，
 *   不允许通配符、端口、下划线——P1 从严，CSP connect-src 只接受精确域）。
 *
 * 纯 JVM 实现，解析器与仓库在导入期调用；确认对话框与详情页共用 [Entry] 的中文元数据。
 */
object PermissionCatalog {
    const val NETWORK_PREFIX = "network:"

    /**
     * hostname 形态（P1 从严）：点分标签，每标签以小写字母/数字起止、段内允许连字符；
     * 禁止空标签、通配符 `*`、端口 `:`、下划线、大写、首尾点。
     */
    private val HOST_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)*$")

    /** 单条权限的展示形态：原始声明值 + 中文名 + 说明（确认对话框与详情展示共用） */
    data class Entry(
        val raw: String,
        val displayName: String,
        val description: String,
    )

    /** 固定权限白名单：新增权限必须在此登记，否则 manifest 声明即被拒 */
    private val fixed: Map<String, Entry> =
        listOf(
            Entry("prompt:inject", "提示词注入", "扩展的宿主钩子可在生成前向提示词注入内容"),
            Entry("reply:process", "回复处理", "扩展的宿主钩子可在回复落库前处理回复正文"),
            Entry("events:subscribe", "事件订阅", "扩展可订阅聊天事件（消息收发、生成开始/结束、变量更新等）"),
            Entry("messages:read", "消息读取", "扩展可读取当前会话的消息内容"),
            Entry("messages:write", "消息写入", "扩展可新增、修改、删除会话消息"),
            Entry("generation:control", "生成控制", "扩展可触发生成或停止当前生成"),
            Entry("variables", "变量读写", "扩展可读写会话变量与 MVU 变量树"),
        ).associateBy(Entry::raw)

    /** 解析单条权限声明：目录外（含非法 network host）返回 null */
    fun resolve(raw: String): Entry? = fixed[raw] ?: parseNetwork(raw)

    fun isKnown(raw: String): Boolean = resolve(raw) != null

    /**
     * 解析整份权限清单并附中文元数据；任何一条目录外即抛 IllegalArgumentException
     * （中文消息直接进导入失败提示）。参数化消息前缀以便解析器/仓库复用。
     */
    fun resolveAll(
        raws: List<String>,
        invalidPrefix: String = "扩展包非法",
    ): List<Entry> =
        raws.map { raw ->
            resolve(raw)
                ?: throw IllegalArgumentException(
                    "$invalidPrefix：manifest.permissions 声明了未知权限「$raw」" +
                        "（合法形态：固定权限 prompt:inject/reply:process/events:subscribe/" +
                        "messages:read/messages:write/generation:control/variables，" +
                        "或 network:<host> 动态权限，host 为小写字母/数字/点/连字符、不带端口与通配符）",
                )
        }

    /** network:<host> 动态形态解析：host 不合法视为未知权限（导入解析失败） */
    private fun parseNetwork(raw: String): Entry? =
        raw
            .takeIf { it.startsWith(NETWORK_PREFIX) }
            ?.removePrefix(NETWORK_PREFIX)
            ?.takeIf { HOST_PATTERN.matches(it) }
            ?.let { host ->
                Entry(
                    raw = raw,
                    displayName = "网络访问：$host",
                    description = "扩展的页面可连接网络主机 $host（WebView CSP connect-src 放行该域）",
                )
            }
}
