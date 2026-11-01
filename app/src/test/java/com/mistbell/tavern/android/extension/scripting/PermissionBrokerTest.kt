package com.mistbell.tavern.android.extension.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PermissionBroker 纯函数（P3）：授权集合 → tavern 方法清单映射、network host 提取。
 * 契约：未授权方法绝不出现在清单里（宿主对象按清单装配，未授权即对象上不存在）。
 */
class PermissionBrokerTest {
    @Test
    fun `七个固定权限映射到完整方法清单 log 恒在首位`() {
        val granted =
            PermissionBroker.grantedMethods(
                listOf(
                    "prompt:inject",
                    "reply:process",
                    "events:subscribe",
                    "variables",
                    "messages:read",
                    "messages:write",
                    "generation:control",
                ),
            )
        assertEquals(
            listOf(
                "log",
                "onPrompt",
                "onReply",
                "on",
                "getVar",
                "setVar",
                "getVariables",
                "insertOrAssignVariables",
                "getChatMessages",
                "getLastMessageId",
                "setChatMessages",
                "createChatMessage",
                "deleteChatMessages",
                "triggerGenerate",
                "stopGeneration",
            ),
            granted,
        )
    }

    @Test
    fun `无任何授权时仅 log 可用`() {
        assertEquals(listOf("log"), PermissionBroker.grantedMethods(emptyList()))
    }

    @Test
    fun `逐权限只开出自己的方法`() {
        assertEquals(listOf("log", "onPrompt"), PermissionBroker.grantedMethods(listOf("prompt:inject")))
        assertEquals(listOf("log", "onReply"), PermissionBroker.grantedMethods(listOf("reply:process")))
        assertEquals(listOf("log", "on"), PermissionBroker.grantedMethods(listOf("events:subscribe")))
        assertEquals(
            listOf("log", "getChatMessages", "getLastMessageId"),
            PermissionBroker.grantedMethods(listOf("messages:read")),
        )
        assertEquals(
            listOf("log", "setChatMessages", "createChatMessage", "deleteChatMessages"),
            PermissionBroker.grantedMethods(listOf("messages:write")),
        )
        assertEquals(
            listOf("log", "triggerGenerate", "stopGeneration"),
            PermissionBroker.grantedMethods(listOf("generation:control")),
        )
        assertEquals(
            listOf("log", "getVar", "setVar", "getVariables", "insertOrAssignVariables"),
            PermissionBroker.grantedMethods(listOf("variables")),
        )
    }

    @Test
    fun `未授权方法不出现在清单里`() {
        val granted = PermissionBroker.grantedMethods(listOf("messages:read", "events:subscribe"))
        listOf(
            "setChatMessages",
            "createChatMessage",
            "deleteChatMessages",
            "triggerGenerate",
            "stopGeneration",
            "getVar",
            "setVar",
            "getVariables",
            "insertOrAssignVariables",
            "onPrompt",
            "onReply",
        ).forEach { method ->
            assertTrue("未授权的 $method 不得出现在方法清单", method !in granted)
        }
    }

    @Test
    fun `未知权限与 network 动态权限不产生方法 重复声明去重`() {
        val granted =
            PermissionBroker.grantedMethods(
                listOf("network:example.com", "not-a-permission", "messages:read", "messages:read"),
            )
        assertEquals(listOf("log", "getChatMessages", "getLastMessageId"), granted)
    }

    @Test
    fun `networkHosts 提取 host 并去重 非法空白过滤`() {
        val hosts =
            PermissionBroker.networkHosts(
                listOf(
                    "network:api.example.com",
                    "prompt:inject",
                    "network:cdn.example.com",
                    "network:api.example.com",
                    "network:",
                    "network:  ",
                ),
            )
        assertEquals(listOf("api.example.com", "cdn.example.com"), hosts)
    }

    @Test
    fun `networkHosts 对无 network 声明返回空`() {
        assertTrue(PermissionBroker.networkHosts(listOf("prompt:inject")).isEmpty())
    }
}
