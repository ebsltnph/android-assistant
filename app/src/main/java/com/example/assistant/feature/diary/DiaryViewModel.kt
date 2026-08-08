package com.example.assistant.feature.diary

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.DailySummaryGenerator
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.notification.Notifier
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.db.entity.DiaryEntryWithImages
import com.example.assistant.data.db.entity.DiaryImageEntity
import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日记页 ViewModel：日记本管理 + 条目列表（含多图）+ 新增/删除 + 记忆抽取。
 */
class DiaryViewModel(
    private val appContext: Context,
    private val diaryRepository: DiaryRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryExtractor: MemoryExtractor,
    private val summaryGenerator: DailySummaryGenerator
) : ViewModel() {

    /** 日记本列表（生活/工作等） */
    val books: StateFlow<List<DiaryBookEntity>> = diaryRepository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前选中的日记本 id */
    val selectedBookId = MutableStateFlow(0L)

    /** 搜索关键词（空 = 不搜索，显示全部条目） */
    val searchQuery = MutableStateFlow("")

    /**
     * 当前日记本条目（含图片列表，按时间倒序展示由 DAO 保证）。
     * 搜索关键词非空时走 LIKE 内容搜索（按当前本过滤），清空关键词即恢复全部列表。
     */
    val entries: StateFlow<List<DiaryEntryWithImages>> = combine(selectedBookId, searchQuery) { id, q -> id to q }
        .flatMapLatest { (id, q) ->
            if (id == 0L) flowOf(emptyList())
            else if (q.isBlank()) diaryRepository.entriesWithImagesFor(id)
            else diaryRepository.searchEntriesWithImagesFor(id, escapeLike(q.trim()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 长期记忆列表 */
    val memories: StateFlow<List<MemoryEntity>> = memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val input = MutableStateFlow("")

    /** 操作提示（保存/删除结果），显示后自动消失 */
    val message = MutableStateFlow<String?>(null)

    /**
     * 确保默认选中日记本（首次进入选中默认本）。
     * 注意：直接查库（suspend DAO），不要读 books.value——books 是 WhileSubscribed
     * 的 StateFlow，没人订阅时永远不会更新（删掉切本 UI 后已无订阅者），
     * 读缓存值会导致 selectedBookId 永远是 0、日记页空白。
     * 种子兜底：Application 的种子是异步的（启动期不阻塞主线程），用户快速进入
     * 日记页时种子可能还没完成——这里查到空就自己补种再查一次。
     */
    fun initSelectedBook() {
        if (selectedBookId.value == 0L) {
            viewModelScope.launch {
                var book = diaryRepository.defaultBook()
                if (book == null) {
                    diaryRepository.ensureSeedBooks()
                    book = diaryRepository.defaultBook()
                }
                selectedBookId.value = book?.id ?: 0L
            }
        }
    }

    fun selectBook(id: Long) {
        selectedBookId.value = id
    }

    fun setInput(text: String) {
        input.value = text
    }

    fun setSearchQuery(text: String) {
        searchQuery.value = text
    }

    /** LIKE 通配符转义：用户搜「100%」应匹配字面 %，而不是匹配所有条目 */
    private fun escapeLike(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 新增条目（文字记录），随后后台抽取长期记忆 */
    fun addEntry() {
        val text = input.value.trim()
        val bookId = selectedBookId.value
        if (text.isEmpty() || bookId == 0L) return
        input.value = ""
        viewModelScope.launch {
            diaryRepository.addEntry(bookId, text, source = "text")
            message.value = "📔 已记录"
            // 记忆抽取后台静默执行
            viewModelScope.launch {
                val facts = memoryExtractor.extract(text)
                if (facts.isNotEmpty()) memoryRepository.addFacts(facts)
            }
        }
    }

    /**
     * 删除条目：先删全部图片文件再删记录（文件删不掉也只删记录，不阻塞）。
     * 图片记录随条目级联删除（FK CASCADE），这里只清理文件。
     */
    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            val images = diaryRepository.imagesFor(id)
            images.forEach { deleteImageFile(it.path) }
            diaryRepository.deleteEntry(id)
        }
    }

    /**
     * 给条目补图（相册 Photo Picker 多选回调）：逐张读图缩放 → 存 filesDir → 追加进 DB。
     * 一次选多张全部追加（保留已有图片）。
     */
    fun addImagesToEntry(entryId: Long, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val paths = mutableListOf<String>()
            for (uri in uris) {
                val path = withContext(Dispatchers.IO) {
                    ImageUtils.readUriBitmap(appContext, uri)?.let { bmp ->
                        ImageUtils.saveToFilesDir(
                            appContext, bmp, "diary_${System.currentTimeMillis()}_${paths.size}.jpg"
                        )
                    }
                }
                if (path != null) paths.add(path)
            }
            if (paths.isNotEmpty()) {
                diaryRepository.addImages(entryId, paths)
                message.value = "📷 已添加 ${paths.size} 张图片"
            } else {
                message.value = "⚠️ 图片读取失败，请换一张试试"
            }
        }
    }

    /** 删除某张图片：删文件 + 删记录 */
    fun deleteImage(image: DiaryImageEntity) {
        viewModelScope.launch {
            deleteImageFile(image.path)
            diaryRepository.deleteImage(image.id)
            message.value = "🗑️ 图片已删除"
        }
    }

    /** 删除图片文件（失败不阻塞，忽略） */
    private fun deleteImageFile(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }

    /** 下载图片到系统相册（保存到"图片/随身助手"文件夹） */
    fun downloadImage(image: DiaryImageEntity, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ImageUtils.saveToGallery(appContext, image.path)
            }
            message.value = if (ok) "💾 已保存到系统相册" else "⚠️ 保存失败，请检查存储权限"
            onResult(ok)
        }
    }

    /** 新增日记本（重名自动跳过） */
    fun addBook(name: String) {
        val n = name.trim()
        if (n.isEmpty() || books.value.any { it.name == n }) return
        viewModelScope.launch {
            diaryRepository.addBook(n)
            message.value = "📚 已创建「$n」日记本"
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryRepository.delete(id) }
    }

    fun clearMemories() {
        viewModelScope.launch {
            memoryRepository.clearAll()
            message.value = "🧹 记忆已清空"
        }
    }

    /** 立即生成今日小结（手动触发；与 21:00 自动 Worker 共用同一逻辑） */
    fun generateTodaySummary() {
        viewModelScope.launch {
            message.value = "⏳ 正在整理今日小结…"
            val summary = summaryGenerator.generateToday()
            if (summary == null) {
                message.value = "今天还没写日记，先记几条吧"
            } else {
                Notifier.notifyDiarySummary(appContext, summary)
                message.value = "✅ 今日小结已生成，请看通知"
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
