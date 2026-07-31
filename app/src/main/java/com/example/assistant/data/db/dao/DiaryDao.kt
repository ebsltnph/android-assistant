package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {

    // ---- 日记本 ----
    @Query("SELECT * FROM diary_books ORDER BY isDefault DESC, id ASC")
    fun booksFlow(): Flow<List<DiaryBookEntity>>

    @Query("SELECT * FROM diary_books ORDER BY isDefault DESC, id ASC")
    suspend fun allBooks(): List<DiaryBookEntity>

    @Query("SELECT * FROM diary_books WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultBook(): DiaryBookEntity?

    @Insert
    suspend fun insertBook(book: DiaryBookEntity): Long

    @Insert
    fun insertBookSync(book: DiaryBookEntity): Long // 供数据库种子使用

    @Delete
    suspend fun deleteBook(book: DiaryBookEntity)

    // ---- 日记条目 ----
    @Query("SELECT * FROM diary_entries WHERE bookId = :bookId ORDER BY createdAtEpochMillis DESC")
    fun entriesFlow(bookId: Long): Flow<List<DiaryEntryEntity>>

    @Insert
    suspend fun insertEntry(entry: DiaryEntryEntity): Long

    @Query("DELETE FROM diary_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    /** 取某时间区间内的所有条目（按时间正序，供每日总结等使用） */
    @Query(
        "SELECT * FROM diary_entries WHERE createdAtEpochMillis >= :from AND createdAtEpochMillis < :to ORDER BY createdAtEpochMillis ASC"
    )
    suspend fun entriesBetween(from: Long, to: Long): List<DiaryEntryEntity>
}
