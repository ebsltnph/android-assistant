package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.db.entity.DiaryEntryWithImages
import com.example.assistant.data.db.entity.DiaryImageEntity
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
    /** 条目列表（带图片，@Relation 查询）——日记页唯一数据源 */
    @Transaction
    @Query("SELECT * FROM diary_entries WHERE bookId = :bookId ORDER BY createdAtEpochMillis DESC")
    fun entriesWithImagesFlow(bookId: Long): Flow<List<DiaryEntryWithImages>>

    /** 关键词搜索条目（带图片，@Relation 查询）——日记页搜索数据源。
     *  query 需已做 LIKE 通配符转义（ViewModel escapeLike），故 SQL 侧加 ESCAPE '\' 匹配字面值。 */
    @Transaction
    @Query(
        "SELECT * FROM diary_entries WHERE bookId = :bookId AND content LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY createdAtEpochMillis DESC"
    )
    fun searchEntriesWithImagesFlow(bookId: Long, query: String): Flow<List<DiaryEntryWithImages>>

    @Insert
    suspend fun insertEntry(entry: DiaryEntryEntity): Long

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun entryById(id: Long): DiaryEntryEntity?

    /** 单条编辑：更新文字内容（图片等其他字段不变） */
    @Query("UPDATE diary_entries SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    /** 单条编辑：更新标签（逗号分隔，空 = 未分类） */
    @Query("UPDATE diary_entries SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: Long, tags: String)

    @Query("DELETE FROM diary_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    /** 取某时间区间内的所有条目（按时间正序，供每日总结等使用） */
    @Query(
        "SELECT * FROM diary_entries WHERE createdAtEpochMillis >= :from AND createdAtEpochMillis < :to ORDER BY createdAtEpochMillis ASC"
    )
    suspend fun entriesBetween(from: Long, to: Long): List<DiaryEntryEntity>

    // ---- 条目图片（DB v6，一条目多张） ----
    @Query("SELECT * FROM diary_images WHERE entryId = :entryId ORDER BY position ASC, id ASC")
    suspend fun imagesFor(entryId: Long): List<DiaryImageEntity>

    @Query("SELECT * FROM diary_images WHERE id = :id")
    suspend fun imageById(id: Long): DiaryImageEntity?

    @Insert
    suspend fun insertImages(images: List<DiaryImageEntity>)

    @Query("DELETE FROM diary_images WHERE id = :id")
    suspend fun deleteImage(id: Long)

    /** 数据库里引用的全部图片路径（启动时清理孤儿文件用） */
    @Query("SELECT path FROM diary_images")
    suspend fun allImagePaths(): List<String>

    // ---- 备份/恢复用（全量快照 + 清空 + 批量插入） ----

    @Query("SELECT * FROM diary_entries ORDER BY id ASC")
    suspend fun allEntries(): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_images ORDER BY id ASC")
    suspend fun allImages(): List<DiaryImageEntity>

    @Insert
    suspend fun insertAllBooks(books: List<DiaryBookEntity>)

    @Insert
    suspend fun insertAllEntries(entries: List<DiaryEntryEntity>)

    @Query("DELETE FROM diary_images")
    suspend fun clearAllImages()

    @Query("DELETE FROM diary_entries")
    suspend fun clearAllEntries()

    @Query("DELETE FROM diary_books")
    suspend fun clearAllBooks()
}
