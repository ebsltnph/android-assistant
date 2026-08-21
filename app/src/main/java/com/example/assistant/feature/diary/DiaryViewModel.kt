package com.example.assistant.feature.diary

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.DailySummaryGenerator
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.agent.PeriodSummaryGenerator
import com.example.assistant.core.notification.Notifier
import com.example.assistant.core.vision.ImageUtils
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.db.entity.DiaryEntryWithImages
import com.example.assistant.data.db.entity.DiaryImageEntity
import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.db.entity.PeriodSummaryEntity
import com.example.assistant.data.db.entity.tagList
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.MemoryRepository
import com.example.assistant.data.repo.SummaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val summaryGenerator: DailySummaryGenerator,
    private val periodSummaryGenerator: PeriodSummaryGenerator,
    private val summaryRepository: SummaryRepository
) : ViewModel() {

    /** 日记本列表（生活/工作等） */
    val books: StateFlow<List<DiaryBookEntity>> = diaryRepository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前选中的日记本 id */
    val selectedBookId = MutableStateFlow(0L)

    /** 搜索关键词（空 = 不搜索，显示全部条目） */
    val searchQuery = MutableStateFlow("")

    /** 标签筛选：选中的标签（空集合 = 不过滤标签；多个标签按“且”同时匹配） */
    val selectedFilterTags = MutableStateFlow<Set<String>>(emptySet())

    /** 「未分类」筛选：只看没有标签的日记（它不是标签，而是一种隐式筛选） */
    val untaggedOnly = MutableStateFlow(false)

    /**
     * 当前日记本条目（含图片列表，按时间倒序展示由 DAO 保证）。
     * 搜索关键词非空时走 LIKE 内容搜索（按当前本过滤），清空关键词即恢复全部列表。
     * 标签筛选在 Kotlin 侧做：个人日记量级足够，且支持任意多个标签“且”匹配、
     * 以及「未分类」这个非标签筛选，Room 硬拼动态 SQL 反而不划算。
     */
    val entries: StateFlow<List<DiaryEntryWithImages>> =
        combine(selectedBookId, searchQuery, selectedFilterTags, untaggedOnly) { id, q, tags, untagged ->
            FilterQuery(id, q, tags, untagged)
        }
            .flatMapLatest { fq ->
                if (fq.bookId == 0L) flowOf(emptyList())
                else {
                    val base = if (fq.query.isBlank()) {
                        diaryRepository.entriesWithImagesFor(fq.bookId)
                    } else {
                        diaryRepository.searchEntriesWithImagesFor(fq.bookId, escapeLike(fq.query.trim()))
                    }
                    if (fq.tags.isEmpty() && !fq.untagged) base
                    else base.map { list ->
                        list.filter { item ->
                            val tags = item.entry.tagList()
                            when {
                                fq.untagged -> tags.isEmpty()
                                else -> fq.tags.all { it in tags }
                            }
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 长期记忆列表 */
    val memories: StateFlow<List<MemoryEntity>> = memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val input = MutableStateFlow("")

    /** 操作提示（保存/删除结果），显示后自动消失 */
    val message = MutableStateFlow<String?>(null)

    /** 期间总结结果（生成后弹窗展示；null = 无弹窗） */
    val periodSummary = MutableStateFlow<String?>(null)

    /** 期间总结是否正在生成（用于禁用按钮防重复触发） */
    val periodSummaryLoading = MutableStateFlow(false)

    /** 期间总结历史（最近 5 条，倒序）——供「期间总结」历史列表重新查看 */
    val periodSummaries: StateFlow<List<PeriodSummaryEntity>> = summaryRepository.periodSummaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** 新增条目（写日记对话框调用），随后后台抽取长期记忆。tags 为本次手选标签 */
    fun addEntry(content: String, tags: List<String> = emptyList()) {
        val text = content.trim()
        val bookId = selectedBookId.value
        if (text.isEmpty() || bookId == 0L) return
        viewModelScope.launch {
            diaryRepository.addEntry(bookId, text, source = "text", tags = tags)
            message.value = "📔 已记录"
            // 记忆抽取后台静默执行
            viewModelScope.launch {
                val facts = memoryExtractor.extract(text)
                if (facts.isNotEmpty()) memoryRepository.addFacts(facts)
            }
        }
    }

    /** 单条编辑：更新日记文字内容 + 标签 */
    fun updateEntry(id: Long, content: String, tags: List<String> = emptyList()) {
        val text = content.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            diaryRepository.updateEntryContent(id, text)
            diaryRepository.updateEntryTags(id, tags)
            message.value = "✏️ 日记已更新"
        }
    }

    /** 标签筛选：切换某个标签（多个标签同时选中 = “且”匹配） */
    fun toggleFilterTag(tag: String) {
        if (tag.isBlank()) return
        selectedFilterTags.update { if (tag in it) it - tag else it + tag }
        if (selectedFilterTags.value.isNotEmpty()) untaggedOnly.value = false
    }

    /** 「未分类」筛选：只看没有标签的日记；选中时清空标签筛选 */
    fun toggleUntaggedOnly() {
        untaggedOnly.update { !it }
        if (untaggedOnly.value) selectedFilterTags.value = emptySet()
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

    /** 手动添加一条长期记忆 */
    fun addMemory(fact: String) {
        val text = fact.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            memoryRepository.addFact(text)
            message.value = "🧠 已添加长期记忆"
        }
    }

    /** 单条编辑长期记忆（保留分类与创建时间） */
    fun updateMemory(id: Long, fact: String) {
        val text = fact.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            memoryRepository.update(id, text)
            message.value = "✏️ 记忆已更新"
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

    /** 生成指定期间（[fromMillis, toMillis)）的日记总结，结果弹窗展示。
     *  生成期间 periodSummaryLoading 置 true，由 UI 持续显示「正在生成」提示（不靠 message）。 */
    fun generatePeriodSummary(fromMillis: Long, toMillis: Long) {
        if (periodSummaryLoading.value) return
        viewModelScope.launch {
            periodSummaryLoading.value = true
            val summary = periodSummaryGenerator.generate(fromMillis, toMillis)
            periodSummaryLoading.value = false
            if (summary == null) {
                message.value = "这段期间没有日记，换一个时间段试试"
            } else {
                periodSummary.value = summary
            }
        }
    }

    fun dismissPeriodSummary() {
        periodSummary.value = null
    }

    /** 打开一条历史期间总结（重新查看） */
    fun openPeriodSummary(summary: String) {
        periodSummary.value = summary
    }
}

/** 日记列表筛选参数：书本 + 搜索词 + 标签集（且匹配）+ 是否只看未分类 */
private data class FilterQuery(
    val bookId: Long,
    val query: String,
    val tags: Set<String>,
    val untagged: Boolean
)
