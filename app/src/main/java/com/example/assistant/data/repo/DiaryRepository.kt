package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.DiaryDao
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import kotlinx.coroutines.flow.Flow

class DiaryRepository(private val dao: DiaryDao) {

    val books: Flow<List<DiaryBookEntity>> = dao.booksFlow()

    /** 首次启动时确保存在默认日记本（单一「日记」本） */
    suspend fun ensureSeedBooks() {
        if (dao.allBooks().isEmpty()) {
            dao.insertBook(DiaryBookEntity(name = "日记", isDefault = true))
        }
    }

    suspend fun defaultBook(): DiaryBookEntity? = dao.defaultBook()

    suspend fun addBook(name: String, isDefault: Boolean = false): Long =
        dao.insertBook(DiaryBookEntity(name = name, isDefault = isDefault))

    suspend fun deleteBook(book: DiaryBookEntity) = dao.deleteBook(book)

    fun entriesFor(bookId: Long): Flow<List<DiaryEntryEntity>> = dao.entriesFlow(bookId)

    suspend fun addEntry(
        bookId: Long,
        content: String,
        source: String = "text",
        imagePath: String? = null
    ): Long = dao.insertEntry(
        DiaryEntryEntity(bookId = bookId, content = content, source = source, imagePath = imagePath)
    )

    suspend fun entryById(id: Long): DiaryEntryEntity? = dao.entryById(id)

    /** 给条目补图/换图（相册选图后更新路径） */
    suspend fun updateEntryImage(id: Long, path: String) = dao.updateEntryImage(id, path)

    suspend fun deleteEntry(entryId: Long) = dao.deleteEntry(entryId)

    /** 取某时间区间条目（每日总结用） */
    suspend fun entriesBetween(fromMillis: Long, toMillis: Long): List<DiaryEntryEntity> =
        dao.entriesBetween(fromMillis, toMillis)
}
