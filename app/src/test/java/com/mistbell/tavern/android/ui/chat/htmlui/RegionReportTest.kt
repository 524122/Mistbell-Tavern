package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionReportTest {
    @Test
    fun `parses quoted evaluateJavascript result`() {
        val raw =
            "\"{\\\"vw\\\":411,\\\"regions\\\":[[10.5,20,110.5,70],[0,300,60,360]],\\\"fullscreen\\\":false}\""
        val report = parseRegionReport(raw)!!
        assertEquals(411f, report.viewportWidthCssPx, 0.001f)
        assertFalse(report.fullscreen)
        assertEquals(2, report.rects.size)
        assertEquals(CssRect(10.5f, 20f, 110.5f, 70f), report.rects[0])
        assertTrue(report.rects[0].contains(50f, 40f))
        assertFalse(report.rects[0].contains(200f, 40f))
    }

    @Test
    fun `parses fullscreen report`() {
        val report = parseRegionReport("{\"vw\":400,\"regions\":[],\"fullscreen\":true}")!!
        assertTrue(report.fullscreen)
        assertTrue(report.rects.isEmpty())
    }

    @Test
    fun `null and invalid input yield null`() {
        assertNull(parseRegionReport(null))
        assertNull(parseRegionReport("null"))
        assertNull(parseRegionReport(""))
        assertNull(parseRegionReport("{\"regions\":[]}"))
    }

    @Test
    fun `negative coordinates parse`() {
        val report = parseRegionReport("{\"vw\":400,\"regions\":[[-10,-20,50,60]],\"fullscreen\":false}")!!
        assertEquals(CssRect(-10f, -20f, 50f, 60f), report.rects.single())
    }
}
