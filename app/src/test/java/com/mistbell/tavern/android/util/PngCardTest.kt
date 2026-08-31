package com.mistbell.tavern.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * PngCard 纯函数单元测试（F2 生态互通）。
 * 测试内构造最小合法 PNG（签名 + tEXt + IEND），CRC 用 CRC32 现算，不依赖图片资源。
 */
class PngCardTest {

    /** PNG 8 字节文件签名 */
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** 按格式拼一个 chunk：len(4BE) + type(4) + data(len) + crc(4BE) */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) {
            out.write(v ushr 24 and 0xFF)
            out.write(v ushr 16 and 0xFF)
            out.write(v ushr 8 and 0xFF)
            out.write(v and 0xFF)
        }
        u32(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        u32(crc.value.toInt())
        return out.toByteArray()
    }

    /** 构造最小 PNG：签名 + 可选 tEXt + IEND */
    private fun minimalPng(textChunk: Pair<String, String>? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        if (textChunk != null) {
            val (kw, value) = textChunk
            val data = kw.toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0) +
                value.toByteArray(Charsets.ISO_8859_1)
            out.write(chunk("tEXt", data))
        }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    @Test
    fun `插入后能读回原文`() {
        val png = minimalPng()
        val withChunk = PngCard.insertTextChunk(png, "chara", "hello card")
        assertEquals("hello card", PngCard.readTextChunk(withChunk, "chara"))
    }

    @Test
    fun `roundtrip经过base64编解码后一致`() {
        val json = """{"spec":"chara_card_v2","data":{"name":"测试"}}"""
        val encoded = PngCard.encodeCardJson(json)
        val png = PngCard.insertTextChunk(minimalPng(), PngCard.CHUNK_KEYWORD, encoded)
        val decoded = PngCard.decodeCardJson(PngCard.readTextChunk(png)!!)
        assertEquals(json, decoded)
    }

    @Test
    fun `重复插入同关键字只保留一块且为新值`() {
        // tEXt 是 Latin-1 通道，载荷用 base64（真实用法；中文原文会被编码拒绝）
        val old = PngCard.encodeCardJson("""{"v":"旧值"}""")
        val new = PngCard.encodeCardJson("""{"v":"新值"}""")
        val png = PngCard.insertTextChunk(minimalPng(), "chara", old)
        val replaced = PngCard.insertTextChunk(png, "chara", new)
        assertEquals(new, PngCard.readTextChunk(replaced, "chara"))
        // 全 chunk 扫一遍，统计 "chara" 关键字出现次数，替换语义必须只有一块
        var count = 0
        var offset = signature.size
        while (offset + 8 <= replaced.size) {
            val length = ((replaced[offset].toInt() and 0xFF) shl 24) or
                (replaced[offset + 1].toInt() and 0xFF shl 16) or
                (replaced[offset + 2].toInt() and 0xFF shl 8) or
                (replaced[offset + 3].toInt() and 0xFF)
            val type = String(replaced, offset + 4, 4, Charsets.US_ASCII)
            if (type == "tEXt") {
                val data = replaced.copyOfRange(offset + 8, offset + 8 + length)
                val zero = data.indexOf(0)
                if (String(data, 0, zero, Charsets.ISO_8859_1) == "chara") count++
            }
            if (type == "IEND") break
            offset += 8 + length + 4
        }
        assertEquals(1, count)
    }

    @Test
    fun `无关关键字的其他tEXt块不被影响`() {
        val withOther = PngCard.insertTextChunk(minimalPng(), "other", "keep")
        val cardJson = """{"name":"卡片"}"""
        val png = PngCard.insertTextChunk(withOther, "chara", PngCard.encodeCardJson(cardJson))
        assertEquals("keep", PngCard.readTextChunk(png, "other"))
        assertEquals(cardJson, PngCard.decodeCardJson(PngCard.readTextChunk(png, "chara")!!))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `坏签名抛IllegalArgumentException`() {
        val bad = ByteArray(16) { 0 }
        PngCard.readTextChunk(bad)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `无IEND的PNG插入抛IllegalArgumentException`() {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(chunk("tEXt", "kw".toByteArray() + byteArrayOf(0) + "v".toByteArray()))
        PngCard.insertTextChunk(out.toByteArray(), "chara", "x")
    }

    @Test
    fun `无关键字tEXt时返回null`() {
        val png = minimalPng("chara" to "存在")
        assertNull(PngCard.readTextChunk(png, "ccv3"))
        val empty = minimalPng()
        assertNull(PngCard.readTextChunk(empty))
    }

    @Test
    fun `base64解码失败返回null`() {
        assertNull(PngCard.decodeCardJson("不是base64!!!"))
    }
}
