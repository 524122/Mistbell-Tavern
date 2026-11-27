package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mistbell.tavern.android.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 独立世界书导入的结果（文案与既有界面逐字节一致，两个调用方各自选提示通道） */
sealed interface WorldBookImportOutcome {
    /** 成功：书名 + 条目数（既有文案「成功导入世界书：{name}（{count} 条条目）」的数据源） */
    data class Success(
        val bookName: String,
        val entryCount: Int,
    ) : WorldBookImportOutcome

    /** 解析失败（既有文案「导入失败：无法解析世界书文件」） */
    data object ParseFailed : WorldBookImportOutcome

    /** 读文件或落库失败（既有文案「导入失败: {reason}」） */
    data class IoFailed(val reason: String) : WorldBookImportOutcome
}

/**
 * 独立世界书 JSON 的共享导入核心（P3 导入层：世界书 Tab 与「导入」页共用一份，不复制逻辑）。
 *
 * 从 WorldBookListScreen 的导入分支原样提取，行为不变：
 * SAF 选 JSON → 读文本 → 兜底书名取文件显示名去后缀 → [WorldBookParser] 解析 → 落库。
 */
object WorldBookImporter {
    /** 读文件 / 解析 / 落库的任意失败都折算成 IoFailed 结果（与既有界面行为一致，不崩溃） */
    @Suppress("TooGenericExceptionCaught")
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        db: AppDatabase,
    ): WorldBookImportOutcome =
        withContext(Dispatchers.IO) {
            try {
                val jsonString =
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: return@withContext WorldBookImportOutcome.ParseFailed
                // 兜底书名：取文件显示名去后缀，取不到用固定文案
                val fallbackName =
                    queryDisplayName(context, uri)
                        ?.substringBeforeLast('.')
                        ?.takeIf { it.isNotBlank() }
                        ?: "导入的世界书"
                val parsed =
                    WorldBookParser.parse(jsonString, fallbackName)
                        ?: return@withContext WorldBookImportOutcome.ParseFailed
                val (book, entries) = parsed
                db.worldBookDao().upsertBook(book)
                if (entries.isNotEmpty()) {
                    db.worldBookDao().upsertEntries(entries)
                }
                WorldBookImportOutcome.Success(book.name, entries.size)
            } catch (e: Exception) {
                android.util.Log.e("WorldBookImport", "导入世界书失败", e)
                WorldBookImportOutcome.IoFailed(e.message ?: "未知错误")
            }
        }

    /** 查询 SAF 文件的显示名（书名兜底；导入页与世界书 Tab 共用），失败返回 null */
    fun queryDisplayName(
        context: Context,
        uri: Uri,
    ): String? =
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
}
