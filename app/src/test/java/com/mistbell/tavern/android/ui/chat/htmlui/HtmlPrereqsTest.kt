package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPrereqsTest {
    @Test
    fun `injects inside head when head tag present`() {
        val html = "<!DOCTYPE html><html><head><title>t</title></head><body>x</body>"
        val out = HtmlPrereqs.injectWithPrereq(html, "PREREQ")
        val headEnd = out.indexOf("<head>") + "<head>".length
        val prereqAt = out.indexOf("PREREQ")
        assertEquals(headEnd + 1, prereqAt)
        assertTrue(out.indexOf("<body>") > prereqAt)
    }

    @Test
    fun `prepends when no head tag`() {
        val out = HtmlPrereqs.injectWithPrereq("<div>fragment</div>", "PREREQ")
        assertTrue(out.startsWith("PREREQ"))
        assertTrue(out.endsWith("<div>fragment</div>"))
    }

    @Test
    fun `head match is case insensitive and keeps doctype first`() {
        val html = "<!DOCTYPE html><HTML><HEAD><title>t</title></HEAD><body>x</body>"
        val out = HtmlPrereqs.injectWithPrereq(html, "PREREQ")
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        val headEnd = out.indexOf("<HEAD>") + "<HEAD>".length
        assertEquals(headEnd + 1, out.indexOf("PREREQ"))
    }

    @Test
    fun `head tag with attributes works`() {
        val out = HtmlPrereqs.injectWithPrereq("<html><head lang=\"zh\"><meta></head><body>x</body>", "PREREQ")
        val headEnd = out.indexOf("<head lang=\"zh\">") + "<head lang=\"zh\">".length
        assertEquals(headEnd + 1, out.indexOf("PREREQ"))
    }
}
