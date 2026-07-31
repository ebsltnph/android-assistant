package com.example.assistant.feature.diary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.DailySummaryGenerator
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.notification.Notifier
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 日记页 ViewModel：日记本管理 + 条目列表 + 新增/删除 + 记忆抽取。
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

    /** 当前日记本条目（按时间倒序展示由 DAO 保证） */
    val entries: StateFlow<List<DiaryEntryEntity>> = selectedBookId
        .flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList())
            else diaryRepository.entriesFor(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 长期记忆列表 */
    val memories: StateFlow<List<MemoryEntity>> = memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val input = MutableStateFlow("")

    /** 操作提示（保存/删除结果），显示后自动消失 */
    val message = MutableStateFlow<String?>(null)

    /** 确保默认选中一个日记本（首次进入选中默认本/第一本） */
    fun initSelectedBook() {
        if (selectedBookId.value == 0L) {
            viewModelScope.launch {
                val list = books.value
                if (list.isNotEmpty()) selectedBookId.value = list.firstOrNull { it.isDefault }?.id ?: list.first().id
            }
        }
    }

    fun selectBook(id: Long) {
        selectedBookId.value = id
    }

    fun setInput(text: String) {
        input.value = text
    }

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

    fun deleteEntry(id: Long) {
        viewModelScope.launch { diaryRepository.deleteEntry(id) }
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
