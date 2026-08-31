package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldBookDao {
    @Query("SELECT * FROM world_books")
    fun getAll(): Flow<List<WorldBookEntity>>

    @Query("SELECT * FROM world_books WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorldBookEntity?

    @Query("SELECT * FROM world_book_entries WHERE book_id = :bookId ORDER BY [order]")
    fun getEntries(bookId: String): Flow<List<WorldBookEntryEntity>>

    @Query("SELECT * FROM world_book_entries WHERE book_id = :bookId ORDER BY [order]")
    suspend fun getEntriesList(bookId: String): List<WorldBookEntryEntity>

    @Query("SELECT * FROM world_book_entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: String): WorldBookEntryEntity?

    @Upsert
    suspend fun upsertBook(book: WorldBookEntity)

    @Upsert
    suspend fun upsertEntries(entries: List<WorldBookEntryEntity>)

    @Query("DELETE FROM world_books")
    suspend fun deleteAllBooks()

    @Query("DELETE FROM world_books WHERE id = :id")
    suspend fun deleteBookById(id: String)

    @Query("DELETE FROM world_book_entries")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM world_book_entries WHERE id = :id")
    suspend fun deleteEntryById(id: String)

    @Query("DELETE FROM world_book_entries WHERE book_id = :bookId")
    suspend fun deleteEntriesByBookId(bookId: String)

    @Transaction
    suspend fun replaceAll(
        books: List<WorldBookEntity>,
        allEntries: Map<String, List<WorldBookEntryEntity>>,
    ) {
        deleteAllBooks()
        deleteAllEntries()
        books.forEach { upsertBook(it) }
        allEntries.values.flatten().let { if (it.isNotEmpty()) upsertEntries(it) }
    }
}
