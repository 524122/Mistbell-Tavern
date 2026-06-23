package com.mistbell.tavern.android.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

enum class CharacterExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    JSON("JSON", "json", "application/json"),
    PNG("PNG", "png", "image/png")
}

data class CharacterExportResult(
    val uri: Uri,
    val fileName: String,
    val location: String,
    val mimeType: String
)

object CharacterExporter {
    private const val EXPORT_FOLDER = "LongMemoryAIChat"

    fun buildFileName(name: String, id: String, extension: String): String {
        val safeName = name
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

    fun exportToJson(
        context: Context,
        character: Character,
        fileName: String = buildFileName(character.name, character.id, CharacterExportFormat.JSON.extension)
    ): CharacterExportResult? {
        return try {
            val json = Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }
            saveBytes(
                context = context,
                fileName = fileName,
                mimeType = CharacterExportFormat.JSON.mimeType,
                bytes = json.encodeToString(character).toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun exportToPng(
        context: Context,
        character: Character,
        fileName: String = buildFileName(character.name, character.id, CharacterExportFormat.PNG.extension)
    ): CharacterExportResult? {
        return try {
            val bitmap = renderCharacterBitmap(context, character)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()

            saveBytes(
                context = context,
                fileName = fileName,
                mimeType = CharacterExportFormat.PNG.mimeType,
                bytes = output.toByteArray()
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
        bytes: ByteArray
    ): CharacterExportResult? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            CharacterExportResult(uri, fileName, displayLocation(fileName), mimeType)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                EXPORT_FOLDER
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            CharacterExportResult(Uri.fromFile(file), fileName, displayLocation(fileName), mimeType)
        }
    }

    private fun renderCharacterBitmap(context: Context, character: Character): Bitmap {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val width = 1080
        val pagePadding = dp(40)
        val cardPadding = dp(34)
        val avatarSize = dp(112)
        val contentWidth = width - pagePadding * 2 - cardPadding * 2
        val textStart = cardPadding + avatarSize + dp(24)
        val textWidth = contentWidth - avatarSize - dp(24)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 27)
            textSize = dp(27).toFloat()
            isFakeBoldText = true
        }
        val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 82, 91)
            textSize = dp(14).toFloat()
            isFakeBoldText = true
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(63, 63, 70)
            textSize = dp(16).toFloat()
        }

        fun layout(text: String, paint: TextPaint, maxWidth: Int): StaticLayout {
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
        val cardRect = RectF(
            pagePadding.toFloat(),
            pagePadding.toFloat(),
            (width - pagePadding).toFloat(),
            (pagePadding + cardHeight).toFloat()
        )
        canvas.drawRoundRect(cardRect, dp(28).toFloat(), dp(28).toFloat(), cardPaint)

        val avatarLeft = pagePadding + cardPadding
        val avatarTop = pagePadding + cardPadding
        val avatarRect = RectF(
            avatarLeft.toFloat(),
            avatarTop.toFloat(),
            (avatarLeft + avatarSize).toFloat(),
            (avatarTop + avatarSize).toFloat()
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
                        Path.Direction.CW
                    )
                }
            )
            canvas.drawBitmap(scaled, avatarLeft.toFloat(), avatarTop.toFloat(), null)
            canvas.restore()
            scaled.recycle()
        } else {
            canvas.drawOval(avatarRect, avatarPaint)
            val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private fun parseColor(value: String, fallback: Int): Int {
        return try {
            android.graphics.Color.parseColor(value)
        } catch (_: Exception) {
            fallback
        }
    }
}
