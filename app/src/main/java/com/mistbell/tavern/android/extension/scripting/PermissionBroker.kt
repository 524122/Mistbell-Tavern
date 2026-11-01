package com.mistbell.tavern.android.extension.scripting

import com.mistbell.tavern.android.data.extensionpack.PermissionCatalog

/**
 * 扩展包权限代理（P3，EXTENSION_PLATFORM_DESIGN.md §4 的「注入侧裁剪」层）。
 *
 * 职责：把 manifest.permissions 的授权集合映射为该包 `tavern` 宿主对象上**实际存在**的
 * 方法清单——宿主对象按此清单逐方法构造（VS Code 命名空间供给模式）：未授权的方法在
 * 对象上不存在（`typeof tavern.setChatMessages === 'undefined'`，调用即 TypeError），
 * 而不是存在但运行时报权限错。
 *
 * 纯函数（JVM 单测锁定）：任何权限到能力面的扩充都必须显式改本表并补测；
 * `network:<host>` 不是 tavern 方法，由 HtmlUiSandbox 的 CSP/拦截白名单消费（见
 * [networkHosts]），在此刻意不出现在方法清单里。
 */
object PermissionBroker {
    /** 无需声明即可用的方法（诊断日志，无能力面） */
    const val ALWAYS_AVAILABLE = "log"

    /**
     * 固定权限 → tavern 方法清单（与 PermissionCatalog 的固定权限一一对应；
     * 顺序即宿主对象的方法装配顺序，测试锁定）。
     */
    private val PERMISSION_METHODS: List<Pair<String, List<String>>> =
        listOf(
            "prompt:inject" to listOf("onPrompt"),
            "reply:process" to listOf("onReply"),
            "events:subscribe" to listOf("on"),
            "variables" to listOf("getVar", "setVar", "getVariables", "insertOrAssignVariables"),
            "messages:read" to listOf("getChatMessages", "getLastMessageId"),
            "messages:write" to listOf("setChatMessages", "createChatMessage", "deleteChatMessages"),
            "generation:control" to listOf("triggerGenerate", "stopGeneration"),
        )

    /**
     * 授权方法清单（纯函数）：log 恒在首位；未授权能力的方法名绝不出现。
     * 未知权限（理论上有 PermissionCatalog 导入校验兜底，此处防御性忽略）与
     * network:<host> 动态权限不产生任何方法；重复声明去重。
     */
    fun grantedMethods(permissions: Collection<String>): List<String> =
        (listOf(ALWAYS_AVAILABLE) + PERMISSION_METHODS.filter { it.first in permissions }.flatMap { it.second })
            .distinct()

    /** network:<host> 动态权限的 host 提取（网络白名单数据驱动消费；非 tavern 方法面） */
    fun networkHosts(permissions: Collection<String>): List<String> =
        permissions
            .filter { it.startsWith(PermissionCatalog.NETWORK_PREFIX) }
            .map { it.removePrefix(PermissionCatalog.NETWORK_PREFIX).trim() }
            .filter { it.isNotBlank() }
            .distinct()
}
