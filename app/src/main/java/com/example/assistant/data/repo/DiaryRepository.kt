package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.DiaryDao
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.db.entity.DiaryEntryWithImages
import com.example.assistant.data.db.entity.DiaryImageEntity
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

    /** 条目列表（带图片列表）——日记页数据源 */
    fun entriesWithImagesFor(bookId: Long): Flow<List<DiaryEntryWithImages>> =
        dao.entriesWithImagesFlow(bookId)

    /** 关键词搜索条目（带图片列表）——日记页搜索数据源。query 需已做 LIKE 转义。 */
    fun searchEntriesWithImagesFor(bookId: Long, query: String): Flow<List<DiaryEntryWithImages>> =
        dao.searchEntriesWithImagesFlow(bookId, query)

    /**
     * 新增条目（可带多张图片路径）。
     * 图片路径列表由调用方提前存好（filesDir/diary_images 下）。
     */
    suspend fun addEntry(
        bookId: Long,
        content: String,
        source: String = "text",
        imagePaths: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ): Long {
        val entryId = dao.insertEntry(
            DiaryEntryEntity(
                bookId = bookId,
                content = content,
                source = source,
                tags = tags.joinToString(",")
            )
        )
        addImages(entryId, imagePaths)
        return entryId
    }

    /** 给条目追加多张图片（返回新记录的 id 列表） */
    suspend fun addImages(entryId: Long, paths: List<String>) {
        if (paths.isEmpty()) return
        val base = dao.imagesFor(entryId).size
        dao.insertImages(
            paths.mapIndexed { i, path -> DiaryImageEntity(entryId = entryId, path = path, position = base + i) }
        )
    }

    /** 删除某张图片记录（文件删除由调用方负责） */
    suspend fun deleteImage(id: Long) = dao.deleteImage(id)

    /** 条目的图片列表（快照，删除前取文件用） */
    suspend fun imagesFor(entryId: Long): List<DiaryImageEntity> = dao.imagesFor(entryId)

    suspend fun imageById(id: Long): DiaryImageEntity? = dao.imageById(id)

    suspend fun entryById(id: Long): DiaryEntryEntity? = dao.entryById(id)

    /** 单条编辑：更新条目文字内容 */
    suspend fun updateEntryContent(id: Long, content: String) = dao.updateContent(id, content)

    /** 单条编辑：更新条目标签（逗号分隔，空 = 未分类） */
    suspend fun updateEntryTags(id: Long, tags: List<String>) =
        dao.updateTags(id, tags.joinToString(","))

    suspend fun deleteEntry(entryId: Long) = dao.deleteEntry(entryId)

    /** 数据库里引用的全部图片路径（启动清理孤儿文件用） */
    suspend fun allImagePaths(): List<String> = dao.allImagePaths()

    /** 最近条目快照（read_diary 工具用）：新→旧，调用方在内存里过滤 */
    suspend fun latestEntries(bookId: Long, limit: Int): List<DiaryEntryEntity> =
        dao.latestEntries(bookId, limit)

    /** 取某时间区间条目（每日总结用） */
    suspend fun entriesBetween(fromMillis: Long, toMillis: Long): List<DiaryEntryEntity> =
        dao.entriesBetween(fromMillis, toMillis)
}
