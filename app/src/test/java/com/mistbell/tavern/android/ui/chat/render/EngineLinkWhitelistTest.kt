package com.mistbell.tavern.android.ui.chat.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 引擎链接跳转白名单单测（纯字符串逻辑）：仅 http/https 放行，intent/file/自定义
 * scheme 与空值一律拒绝——引擎内容来自第三方主题包，scheme 白名单是打开面的最后防线。
 */
class EngineLinkWhitelistTest {
    @Test
    fun `http 与 https 放行`() {
        assertTrue(isWebLink("http://example.com"))
        assertTrue(isWebLink("https://example.com/path?q=1"))
    }

    @Test
    fun `scheme 大小写不敏感`() {
        assertTrue(isWebLink("HTTPS://example.com"))
        assertTrue(isWebLink("Http://example.com"))
    }

    @Test
    fun `非 http 系 scheme 拒绝`() {
        assertFalse(isWebLink("intent://example.com#Intent;scheme=x;end"))
        assertFalse(isWebLink("file:///data/local/tmp"))
        assertFalse(isWebLink("javascript:alert(1)"))
        assertFalse(isWebLink("content://media/xx"))
        assertFalse(isWebLink("ftp://example.com"))
    }

    @Test
    fun `无 scheme 与空值拒绝`() {
        assertFalse(isWebLink("example.com"))
        assertFalse(isWebLink("://example.com"))
        assertFalse(isWebLink(""))
        assertFalse(isWebLink("   "))
        assertFalse(isWebLink(null))
    }
}
