package com.mistbell.tavern.android.util

import androidx.room.withTransaction
import com.mistbell.tavern.android.data.local.AppDatabase

/** 单卡导入的落库结果（纯值；两个调用方各自拼自己的界面文案） */
data class CharacterImportPersistOutcome(
    val characterName: String,
    /** 同名角色已存在：原地按 PC 版更新（保 id，聊天记录不断链） */
    val updatedExistingCharacter: Boolean,
    /** 同名世界书已存在：原地更新（保 id 与既有链接） */
    val worldBookUpdatedExisting: Boolean,
    val worldBookEntryCount: Int,
)

/**
 * 单张角色卡的共享落库核心（P3 导入层：角色 Tab 的直接导入与「导入」页共用一份，不复制逻辑）。
 *
 * 从 CharacterListViewModel.importCharacter 原样提取，行为不变：
 * - 世界书先行、按书名匹配——同名书原地更新（保 id、删条目重插），否则新建；
 * - 角色随后、按名匹配——同名角色经 [applyCardToExisting] 原地更新（保 id 与本地个性化），否则新建。
 */
object CharacterImportPersist {
    suspend fun persist(
        db: AppDatabase,
        result: CharacterImportResult,
    ): CharacterImportPersistOutcome {
        var characterEntity = result.character
        var bookUpdatedExisting = false
        if (result.worldBook != null) {
            val parsedBook = result.worldBook
            val existingBook =
                db.worldBookDao().getAllBooksOnce()
                    .firstOrNull { it.name.trim() == parsedBook.name.trim() }
            if (existingBook != null) {
                db.withTransaction {
                    db.worldBookDao().upsertBook(existingBook.copy(name = parsedBook.name))
                    db.worldBookDao().deleteEntriesByBookId(existingBook.id)
                    if (result.worldBookEntries.isNotEmpty()) {
                        db.worldBookDao()
                            .upsertEntries(result.worldBookEntries.map { it.copy(bookId = existingBook.id) })
                    }
                }
                characterEntity = characterEntity.copy(worldBookId = existingBook.id)
                bookUpdatedExisting = true
            } else {
                db.worldBookDao().upsertBook(parsedBook)
                if (result.worldBookEntries.isNotEmpty()) {
                    db.worldBookDao().upsertEntries(result.worldBookEntries)
                }
            }
        }

        val existingCharacter =
            db.characterDao().getAllOnce()
                .firstOrNull { it.name.trim() == characterEntity.name.trim() }
        var updatedExisting = false
        if (existingCharacter != null) {
            val merged = applyCardToExisting(existingCharacter, result, characterEntity.worldBookId)
            db.characterDao().upsert(merged)
            updatedExisting = true
        } else {
            db.characterDao().upsert(characterEntity)
        }

        return CharacterImportPersistOutcome(
            characterName = characterEntity.name,
            updatedExistingCharacter = updatedExisting,
            worldBookUpdatedExisting = bookUpdatedExisting,
            worldBookEntryCount = result.worldBookEntries.size,
        )
    }
}
