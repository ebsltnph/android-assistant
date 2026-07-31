package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.DiaryDao
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import kotlinx.coroutines.flow.Flow

class DiaryRepository(private val dao: DiaryDao) {

    val books: Flow<List<DiaryBookEntity>> = dao.booksFlow()

    /** 首次启动时确保存在默认日记本（生活/工作） */
    suspend fun ensureSeedBooks() {
        if (dao.allBooks().isEmpty()) {
            dao.insertBook(DiaryBookEntity(name = "生活", isDefault = true))
            dao.insertBook(DiaryBookEntity(name = "工作", isDefault = false))
        }
    }

    suspend fun defaultBook(): DiaryBookEntity? = dao.defaultBook()

    suspend fun addBook(name: String, isDefault: Boolean = false): Long =
        dao.insertBook(DiaryBookEntity(name = name, isDefault = isDefault))

    suspend fun deleteBook(book: DiaryBookEntity) = dao.deleteBook(book)

    fun entriesFor(bookId: Long): Flow<List<DiaryEntryEntity>> = dao.entriesFlow(bookId)

    suspend fun addEntry(bookId: Long, content: String, source: String = "text"): Long =
        dao.insertEntry(DiaryEntryEntity(bookId = bookId, content = content, source = source))

    suspend fun deleteEntry(entryId: Long) = dao.deleteEntry(entryId)

    /** 取某时间区间条目（每日总结用） */
    suspend fun entriesBetween(fromMillis: Long, toMillis: Long): List<DiaryEntryEntity> =
        dao.entriesBetween(fromMillis, toMillis)
}
