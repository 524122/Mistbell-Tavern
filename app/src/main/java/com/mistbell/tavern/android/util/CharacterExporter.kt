package com.mistbell.tavern.android.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.data.prompt.WorldBookPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

enum class CharacterExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    JSON("JSON", "json", "application/json"),
    PNG("PNG", "png", "image/png"),
}

data class CharacterExportResult(
    val uri: Uri,
    val fileName: String,
    val location: String,
    val mimeType: String,
)

object CharacterExporter {
    private const val EXPORT_FOLDER = "LongMemoryAIChat"

    fun buildFileName(
        name: String,
        id: String,
        extension: String,
    ): String {
        val safeName =
            name
                .ifBlank { "character" }
                .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
                .trim()
                .take(28)
                .ifBlank { "character" }
        val shortId = id.take(8).ifBlank { System.currentTimeMillis().toString() }
        return "${safeName}_${shortId}_${System.currentTimeMillis()}.$extension"
    }

    fun displayLocation(fileName: String): String {
        return "下载/$EXPORT_FOLDER/$fileName"
    }

    // ---- 卡片 JSON 构建：v3 为主格式、v2 兼容（PNG 双 chunk：chara=v2、ccv3=v3，与酒馆导出策略一致）----

    private const val SPEC_V2 = "chara_card_v2"

    private const val SPEC_VERSION_V2 = "2.0"

    private const val SPEC_V3 = "chara_card_v3"

    private const val SPEC_VERSION_V3 = "3.0"

    /**
     * 私有核心：按 [spec]/[specVersion] 产出 SillyTavern 兼容卡片 JSON，data 主体与字段顺序完全一致
     * （v2/v3 共用同一份内容，仅 spec 头不同——与酒馆「同一份 JSON 写 chara/ccv3 双 chunk」策略一致）。
     * 只输出生态标准字段，不携带 avatarData/themeId 等 app 私有字段，保持卡片干净。
     * [bookName]/[bookEntries] 非空时附带 character_book（世界书导出闭环，与导入映射互逆）。
     * character.data 为 null 时只输出根级基础字段，CCv3 透传字段全部省略。
     */
    private fun buildCardJson(
        character: Character,
        bookName: String?,
        bookEntries: List<WorldBookEntryEntity>,
        spec: String,
        specVersion: String,
    ): String {
        val data = character.data
        val card =
            buildJsonObject {
                put("spec", spec)
                put("spec_version", specVersion)
                putJsonObject("data") {
                    put("name", character.name)
                    put("description", character.description)
                    put("personality", character.personality)
                    put("scenario", character.scenario)
                    put("first_mes", character.firstMes)
                    put("mes_example", character.mesExample)
                    put("creator_notes", data?.creatorNotes ?: "")
                    put("system_prompt", data?.systemPrompt ?: "")
                    put("post_history_instructions", data?.postHistoryInstructions ?: "")
                    putJsonArray("alternate_greetings") {
                        data?.alternateGreetings?.forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("tags") {
                        data?.tags?.forEach { add(JsonPrimitive(it)) }
                    }
                    put("creator", data?.creator ?: "")
                    put("character_version", data?.characterVersion ?: "1.0")
                    // CCv3 透传字段：仅非默认值时输出；v2 读卡器按规范保留 data 内未知键，不受影响
                    val nickname = data?.nickname
                    if (!nickname.isNullOrBlank()) put("nickname", nickname)
                    val creatorNotesMultilingual = data?.creatorNotesMultilingual
                    if (creatorNotesMultilingual != null) put("creator_notes_multilingual", creatorNotesMultilingual)
                    val source = data?.source
                    if (source != null) put("source", source)
                    val groupOnlyGreetings = data?.groupOnlyGreetings
                    if (!groupOnlyGreetings.isNullOrEmpty()) {
                        putJsonArray("group_only_greetings") {
                            groupOnlyGreetings.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    val assets = data?.assets
                    if (assets != null) put("assets", assets)
                    // 生态命名空间透传保真：保留原样，可为 null 时直接省略该键
                    val extensions = data?.extensions
                    if (extensions != null) put("extensions", extensions)
                    // 世界书：随卡导出（v2/v3 spec 均为 character_book），条目位置/深度与导入映射互逆
                    if (bookName != null) {
                        put("character_book", buildCharacterBookJson(bookName, bookEntries))
                    }
                }
            }
        val json = Json { prettyPrint = true }
        return json.encodeToString(JsonObject.serializer(), card)
    }

    /**
     * v2 兼容格式（chara_card_v2/2.0）：行为与历史版本完全一致，供既有测试与旧读卡器使用。
     * 当前导出主格式是 v3（见 [buildV3Json]）；PNG 导出走 chara/ccv3 双 chunk（chara 仍写 v2 兼容）。
     */
    fun buildV2Json(
        character: Character,
        bookName: String? = null,
        bookEntries: List<WorldBookEntryEntity> = emptyList(),
    ): String = buildCardJson(character, bookName, bookEntries, SPEC_V2, SPEC_VERSION_V2)

    /**
     * v3 主格式（chara_card_v3/3.0）：JSON 卡导出与 PNG ccv3 chunk 的载体。
     * data 内附带 CCv3 透传字段（nickname/creator_notes_multilingual/source/group_only_greetings/assets，
     * 仅非默认值时输出）；v2/v1 读卡器按 data 包裹与根字段兼容读取，不受未知键影响。
     */
    fun buildV3Json(
        character: Character,
        bookName: String? = null,
        bookEntries: List<WorldBookEntryEntity> = emptyList(),
    ): String = buildCardJson(character, bookName, bookEntries, SPEC_V3, SPEC_VERSION_V3)

    /**
     * character_book 块（CCv2/v3 规范形态，与酒馆卡导出的真实结构一致）：
     * entries 为数组；字段名用规范名 keys/secondary_keys/enabled/insertion_order，
     * position 是字符串锚点 before_char/after_char，数字位置/概率/深度/角色写进
     * extensions.position/.probability/.depth/.role——这是酒馆读卡内嵌书的真实路径
     * （world-info.js convertCharacterBook）。
     */
    private fun buildCharacterBookJson(
        bookName: String,
        bookEntries: List<WorldBookEntryEntity>,
    ): JsonObject =
        buildJsonObject {
            put("name", bookName)
            put("description", "")
            put("scan_depth", ST_DEFAULT_SCAN_DEPTH)
            put("token_budget", ST_DEFAULT_TOKEN_BUDGET)
            put("recursive_scanning", false)
            putJsonArray("entries") {
                bookEntries.forEachIndexed { index, e ->
                    addJsonObject {
                        put("id", index)
                        putJsonArray("keys") {
                            e.toDomain().key.forEach { add(JsonPrimitive(it)) }
                        }
                        putJsonArray("secondary_keys") {}
                        put("comment", e.comment)
                        put("content", e.content)
                        put("constant", e.constant)
                        put("selective", false)
                        put("insertion_order", e.order)
                        put("enabled", !e.disable)
                        // 字符串锚点只有规范定义的两档；精确锚点在 extensions.position（0-6）
                        val specPosition =
                            if (e.insertPosition == WorldBookPlacement.POSITION_AFTER_PROMPT) {
                                "after_char"
                            } else {
                                "before_char"
                            }
                        put("position", specPosition)
                        put("use_regex", false)
                        putJsonObject("extensions") {
                            put("position", StInterop.toStPosition(e.insertPosition, e.depth))
                            put("exclude_recursion", false)
                            put("prevent_recursion", false)
                            put("display_index", index)
                            // 应用 0-1 → 酒馆 0-100 百分数
                            put("probability", (e.probability * 100).roundToInt())
                            put("useProbability", true)
                            put("depth", if (e.depth > 0) e.depth else ST_DEFAULT_DEPTH)
                            put("role", StInterop.toStRole(e.depthRole))
                            put("selectiveLogic", 0)
                        }
                    }
                }
            }
        }

    // 生态默认值：与酒馆导出对齐（scan_depth/token_budget/depth 为 ST 缺省）
    private const val ST_DEFAULT_SCAN_DEPTH = 4

    private const val ST_DEFAULT_TOKEN_BUDGET = 500

    private const val ST_DEFAULT_DEPTH = 4

    fun exportToJson(
        context: Context,
        character: Character,
        fileName: String = buildFileName(character.name, character.id, CharacterExportFormat.JSON.extension),
        bookName: String? = null,
        bookEntries: List<WorldBookEntryEntity> = emptyList(),
    ): CharacterExportResult? {
        return try {
            saveBytes(
                context = context,
                fileName = fileName,
                mimeType = CharacterExportFormat.JSON.mimeType,
                // JSON 卡以 v3 为主格式；v2/v1 读卡器按 data 包裹与根字段兼容读取
                bytes = buildV3Json(character, bookName, bookEntries).toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun exportToPng(
        context: Context,
        character: Character,
        fileName: String = buildFileName(character.name, character.id, CharacterExportFormat.PNG.extension),
        bookName: String? = null,
        bookEntries: List<WorldBookEntryEntity> = emptyList(),
    ): CharacterExportResult? {
        return try {
            val bitmap = renderCharacterBitmap(context, character)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()

            // 双 chunk 埋卡（顺序与酒馆一致：chara 在前、ccv3 在后）：同一份卡片 JSON 仅 spec 头不同，
            // chara=v2 兼容旧读卡器、ccv3=v3 主格式；insertTextChunk 先清旧卡块，两次插入互不冲突
            val basePng = output.toByteArray()
            val v2Png =
                PngCard.insertTextChunk(
                    basePng,
                    PngCard.CHUNK_KEYWORD,
                    PngCard.encodeCardJson(buildV2Json(character, bookName, bookEntries)),
                )
            val pngBytes =
                PngCard.insertTextChunk(
                    v2Png,
                    PngCard.CHUNK_KEYWORD_V3,
                    PngCard.encodeCardJson(buildV3Json(character, bookName, bookEntries)),
                )

            saveBytes(
                context = context,
                fileName = fileName,
                mimeType = CharacterExportFormat.PNG.mimeType,
                bytes = pngBytes,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveBytes(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): CharacterExportResult? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER",
                    )
                }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            CharacterExportResult(uri, fileName, displayLocation(fileName), mimeType)
        } else {
            val dir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    EXPORT_FOLDER,
                )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            CharacterExportResult(Uri.fromFile(file), fileName, displayLocation(fileName), mimeType)
        }
    }

    private fun renderCharacterBitmap(
        context: Context,
        character: Character,
    ): Bitmap {
        val density = context.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).roundToInt()

        val width = 1080
        val pagePadding = dp(40)
        val cardPadding = dp(34)
        val avatarSize = dp(112)
        val contentWidth = width - pagePadding * 2 - cardPadding * 2
        val textStart = cardPadding + avatarSize + dp(24)
        val textWidth = contentWidth - avatarSize - dp(24)

        val titlePaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(24, 24, 27)
                textSize = dp(27).toFloat()
                isFakeBoldText = true
            }
        val sectionPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(82, 82, 91)
                textSize = dp(14).toFloat()
                isFakeBoldText = true
            }
        val bodyPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(63, 63, 70)
                textSize = dp(16).toFloat()
            }

        fun layout(
            text: String,
            paint: TextPaint,
            maxWidth: Int,
        ): StaticLayout {
            val safeText = text.ifBlank { " " }
            return StaticLayout.Builder
                .obtain(safeText, 0, safeText.length, paint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(dp(3).toFloat(), 1f)
                .setIncludePad(false)
                .build()
        }

        val titleLayout = layout(character.name.ifBlank { "未命名角色" }, titlePaint, textWidth)
        val descLayout = layout(character.description, bodyPaint, textWidth)
        val personalityLayout = layout(character.personality, bodyPaint, contentWidth)
        val firstMesLayout = layout(character.firstMes, bodyPaint, contentWidth)

        val headerHeight = maxOf(avatarSize, titleLayout.height + dp(12) + descLayout.height)
        val personalityHeight = if (character.personality.isBlank()) 0 else dp(26) + personalityLayout.height + dp(22)
        val firstMesHeight = if (character.firstMes.isBlank()) 0 else dp(26) + firstMesLayout.height
        val cardHeight = cardPadding * 2 + headerHeight + dp(28) + personalityHeight + firstMesHeight
        val bitmapHeight = pagePadding * 2 + cardHeight

        val bitmap = Bitmap.createBitmap(width, bitmapHeight.coerceAtLeast(dp(420)), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 244, 248) }
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = parseColor(character.color, Color.rgb(103, 80, 164)) }

        canvas.drawColor(bgPaint.color)
        val cardRect =
            RectF(
                pagePadding.toFloat(),
                pagePadding.toFloat(),
                (width - pagePadding).toFloat(),
                (pagePadding + cardHeight).toFloat(),
            )
        canvas.drawRoundRect(cardRect, dp(28).toFloat(), dp(28).toFloat(), cardPaint)

        val avatarLeft = pagePadding + cardPadding
        val avatarTop = pagePadding + cardPadding
        val avatarRect =
            RectF(
                avatarLeft.toFloat(),
                avatarTop.toFloat(),
                (avatarLeft + avatarSize).toFloat(),
                (avatarTop + avatarSize).toFloat(),
            )

        val avatarBitmap = ImageUtils.dataUriToBitmap(character.avatarData)
        if (avatarBitmap != null) {
            val scaled = Bitmap.createScaledBitmap(avatarBitmap, avatarSize, avatarSize, true)
            canvas.save()
            canvas.clipPath(
                Path().apply {
                    addRoundRect(
                        avatarRect,
                        avatarSize / 2f,
                        avatarSize / 2f,
                        Path.Direction.CW,
                    )
                },
            )
            canvas.drawBitmap(scaled, avatarLeft.toFloat(), avatarTop.toFloat(), null)
            canvas.restore()
            scaled.recycle()
        } else {
            canvas.drawOval(avatarRect, avatarPaint)
            val initialPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = dp(46).toFloat()
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
            val centerY = avatarRect.centerY() - (initialPaint.descent() + initialPaint.ascent()) / 2
            canvas.drawText(character.name.take(1).ifBlank { "?" }, avatarRect.centerX(), centerY, initialPaint)
        }

        var y = (pagePadding + cardPadding).toFloat()
        canvas.save()
        canvas.translate((pagePadding + textStart).toFloat(), y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + dp(12)

        canvas.save()
        canvas.translate((pagePadding + textStart).toFloat(), y)
        descLayout.draw(canvas)
        canvas.restore()

        y = (pagePadding + cardPadding + headerHeight + dp(28)).toFloat()
        if (character.personality.isNotBlank()) {
            canvas.save()
            canvas.translate((pagePadding + cardPadding).toFloat(), y)
            layout("性格", sectionPaint, contentWidth).draw(canvas)
            canvas.restore()
            y += dp(26)
            canvas.save()
            canvas.translate((pagePadding + cardPadding).toFloat(), y)
            personalityLayout.draw(canvas)
            canvas.restore()
            y += personalityLayout.height + dp(22)
        }
        if (character.firstMes.isNotBlank()) {
            canvas.save()
            canvas.translate((pagePadding + cardPadding).toFloat(), y)
            layout("开场白", sectionPaint, contentWidth).draw(canvas)
            canvas.restore()
            y += dp(26)
            canvas.save()
            canvas.translate((pagePadding + cardPadding).toFloat(), y)
            firstMesLayout.draw(canvas)
            canvas.restore()
        }

        return bitmap
    }

    private fun parseColor(
        value: String,
        fallback: Int,
    ): Int {
        return try {
            android.graphics.Color.parseColor(value)
        } catch (_: Exception) {
            fallback
        }
    }
}
