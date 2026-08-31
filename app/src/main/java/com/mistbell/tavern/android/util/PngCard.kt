package com.mistbell.tavern.android.util

import java.util.Base64
import java.util.zip.CRC32

/**
 * 角色卡 PNG 埋卡编解码（F2 生态互通）。
 *
 * 通行约定（SillyTavern / RisuAI / Chub 一致）：PNG 的 tEXt chunk，
 * 关键字 "chara"，值为 base64(卡片 JSON)。tEXt 文本按 Latin-1 传输，
 * base64 纯 ASCII 故无编码问题。
 *
 * 实现为纯函数（无 Android 依赖），可直接单元测试；
 * PNG 格式本身只需：8 字节签名 + [len(4BE) type(4) data(len) crc(4BE)] 块序列，
 * 故不引入 pngj 依赖（见 docs/FOUNDATION.md 参考纪律——工具件够薄就自持）。
 */
object PngCard {
    const val CHUNK_KEYWORD = "chara"

    private val PNG_SIGNATURE =
        byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )

    /** 读取指定关键字的 tEXt chunk 文本（Latin-1）；无则返回 null；非 PNG/结构损坏抛 IllegalArgumentException */
    fun readTextChunk(
        png: ByteArray,
        keyword: String = CHUNK_KEYWORD,
    ): String? {
        requireSignature(png)
        var offset = PNG_SIGNATURE.size
        var result: String? = null
        while (offset + 8 <= png.size) {
            val length = readU32(png, offset)
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            if (dataEnd + 4 > png.size) throw IllegalArgumentException("PNG chunk 结构损坏（length 越界）")
            if (type == "tEXt" && result == null) {
                val data = png.copyOfRange(dataStart, dataEnd)
                val zero = data.indexOf(0)
                if (zero > 0) {
                    val kw = String(data, 0, zero, Charsets.ISO_8859_1)
                    if (kw == keyword) {
                        result = String(data, zero + 1, data.size - zero - 1, Charsets.ISO_8859_1)
                    }
                }
            }
            if (type == "IEND") break
            offset = dataEnd + 4 // 跳过 crc
        }
        return result
    }

    /**
     * 在 IEND 前插入（或替换同关键字）tEXt chunk，返回新 PNG 字节。
     * 非 PNG / 无 IEND 抛 IllegalArgumentException。
     */
    fun insertTextChunk(
        png: ByteArray,
        keyword: String = CHUNK_KEYWORD,
        text: String,
    ): ByteArray {
        requireSignature(png)
        // tEXt 是 Latin-1 通道：中文等非 Latin-1 字符会被静默替换成 '?'——
        // 快速失败引导调用方走 base64（真实用法见 encodeCardJson）
        require(keyword.all { it.code <= 0xFF } && text.all { it.code <= 0xFF }) {
            "tEXt 仅支持 Latin-1/ASCII 文本；卡片 JSON 请先 PngCard.encodeCardJson 转 base64"
        }
        // 先剔除同关键字的既有 tEXt（重复导入/再导出时保持单块）
        val cleaned = removeAllTextChunks(png, keyword)
        // 找到 IEND 起始偏移
        var offset = PNG_SIGNATURE.size
        var iendOffset = -1
        while (offset + 8 <= cleaned.size) {
            val length = readU32(cleaned, offset)
            val type = String(cleaned, offset + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") {
                iendOffset = offset
                break
            }
            offset += 8 + length + 4
        }
        require(iendOffset >= 0) { "PNG 缺少 IEND 结束块" }

        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val payload = text.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(kw.size + 1 + payload.size)
        System.arraycopy(kw, 0, data, 0, kw.size)
        data[kw.size] = 0
        System.arraycopy(payload, 0, data, kw.size + 1, payload.size)

        val chunk = ByteArray(12 + data.size)
        writeU32(chunk, 0, data.size)
        System.arraycopy("tEXt".toByteArray(Charsets.US_ASCII), 0, chunk, 4, 4)
        System.arraycopy(data, 0, chunk, 8, data.size)
        val crc =
            CRC32().apply {
                update(chunk, 4, 4 + data.size) // type + data
            }
        writeU32(chunk, 8 + data.size, crc.value.toInt())

        val out = ByteArray(cleaned.size + chunk.size)
        // 复用范围: [0, iendOffset) 原样 + 插入 chunk + IEND 及其后内容原样
        System.arraycopy(cleaned, 0, out, 0, iendOffset)
        System.arraycopy(chunk, 0, out, iendOffset, chunk.size)
        System.arraycopy(cleaned, iendOffset, out, iendOffset + chunk.size, cleaned.size - iendOffset)
        return out
    }

    /** base64 解码卡片 JSON（容错：仅清理空白字符后重试一次），失败返回 null */
    fun decodeCardJson(base64Text: String): String? =
        try {
            Base64.getDecoder().decode(base64Text).toString(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getDecoder().decode(base64Text.replace(Regex("\\s"), "")).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

    /** base64 编码（标准无换行，全生态可读） */
    fun encodeCardJson(json: String): String = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    // ---- 内部 ----

    private fun removeAllTextChunks(
        png: ByteArray,
        keyword: String,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(png, 0, PNG_SIGNATURE.size)
        var offset = PNG_SIGNATURE.size
        while (offset + 8 <= png.size) {
            val length = readU32(png, offset)
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            val chunkEnd = offset + 8 + length + 4
            if (type == "tEXt") {
                val data = png.copyOfRange(offset + 8, offset + 8 + length)
                val zero = data.indexOf(0)
                val kw = if (zero > 0) String(data, 0, zero, Charsets.ISO_8859_1) else ""
                if (kw != keyword) out.write(png, offset, chunkEnd - offset) // 保留无关 tEXt
            } else {
                out.write(png, offset, chunkEnd - offset)
            }
            if (type == "IEND") break
            offset = chunkEnd
        }
        return out.toByteArray()
    }

    private fun requireSignature(png: ByteArray) {
        require(png.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.withIndex().all { (i, b) -> png[i] == b }) {
            "不是有效的 PNG 文件"
        }
    }

    private fun readU32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeU32(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
