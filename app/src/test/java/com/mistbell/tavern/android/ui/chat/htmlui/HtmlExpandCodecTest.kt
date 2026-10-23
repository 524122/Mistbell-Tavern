package com.mistbell.tavern.android.ui.chat.htmlui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlExpandCodecTest {
    @Test
    fun `round trip preserves set`() {
        val ids = setOf("b-2", "a-1", "c-3")
        assertEquals(ids, HtmlExpandCodec.decode(HtmlExpandCodec.encode(ids)))
    }

    @Test
    fun `encode sorts ids for stable output`() {
        assertEquals("a,b,c", HtmlExpandCodec.encode(setOf("c", "a", "b")))
    }

    @Test
    fun `decode null and blank yields empty set`() {
        assertTrue(HtmlExpandCodec.decode(null).isEmpty())
        assertTrue(HtmlExpandCodec.decode("").isEmpty())
        assertTrue(HtmlExpandCodec.decode("  ").isEmpty())
    }

    @Test
    fun `decode drops empty segments and trims`() {
        assertEquals(setOf("x", "y"), HtmlExpandCodec.decode("x,, y ,"))
    }

    @Test
    fun `encode empty set yields empty string`() {
        assertEquals("", HtmlExpandCodec.encode(emptySet()))
    }
}
