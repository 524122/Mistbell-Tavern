package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlUiSandboxTest {
    @Test
    fun `passive extensions are allowed`() {
        listOf(
            "https://cdn.example.com/a.png",
            "https://cdn.example.com/font.woff2?v=6.4",
            "https://cdn.example.com/all.min.css",
            "https://cdn.example.com/bgm.wav#t=3",
            "https://cdn.example.com/bgm.MP3",
            "https://cdn.example.com/clip.webm",
        ).forEach { url ->
            assertTrue("$url should be allowed", HtmlUiSandbox.shouldAllowHttps(url, null))
        }
    }

    @Test
    fun `extensionless stylesheet allowed via Accept header`() {
        val url = "https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600&display=swap"
        assertTrue(HtmlUiSandbox.shouldAllowHttps(url, "text/css,*/*;q=0.1"))
    }

    @Test
    fun `extensionless url without css accept is blocked`() {
        val url = "https://fonts.googleapis.com/css2?family=Noto+Serif+SC"
        assertFalse(HtmlUiSandbox.shouldAllowHttps(url, "*/*"))
        assertFalse(HtmlUiSandbox.shouldAllowHttps(url, null))
    }

    @Test
    fun `scripts and api endpoints are blocked`() {
        listOf(
            "https://cdn.example.com/bundle.js",
            "https://cdn.example.com/worker.js?v=1",
            "https://api.example.com/graphql",
            "https://cdn.example.com/data.json",
            "https://cdn.example.com/page.html",
        ).forEach { url ->
            assertFalse("$url should be blocked", HtmlUiSandbox.shouldAllowHttps(url, "*/*"))
        }
    }

    @Test
    fun `query and fragment do not confuse extension parsing`() {
        assertTrue(HtmlUiSandbox.shouldAllowHttps("https://a.b/c.PNg?x=.js", null))
        assertFalse(HtmlUiSandbox.shouldAllowHttps("https://a.b/c.js?x=.png", null))
    }
}
