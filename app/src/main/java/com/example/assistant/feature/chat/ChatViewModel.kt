package com.example.assistant.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.Agent.AgentResult
import com.example.assistant.core.agent.AssistantIntent
import com.example.assistant.core.agent.EventExtractor
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.agent.ReminderTimeParser
import com.example.assistant.core.agent.Session
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.EventRepository
import com.example.assistant.data.repo.MemoryRepository
import com.example.assistant.data.repo.ReminderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** 聊天界面的一条消息 */
data class ChatUiMessage(
    val id: Long,
    val role: String,        // "user" | "assistant"
    val text: String,
    /** 推理模型的思考过程（独立于正式回答展示，带"思考过程"标注） */
    val thinking: String = "",
    val streaming: Boolean = false
)

class ChatViewModel(
    private val agent: Agent,
    private val diaryRepository: DiaryRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryExtractor: MemoryExtractor,
    private val reminderRepository: ReminderRepository,
    private val reminderTimeParser: ReminderTimeParser,
    private val reminderScheduler: ReminderScheduler,
    private val eventRepository: EventRepository,
    private val eventExtractor: EventExtractor
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val session = Session()
    private var counter = 0L

    fun setInput(text: String) {
        _inputText.value = text
    }

    fun send() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _isStreaming.value) return
        _inputText.value = ""
        session.addUser(text)
        viewModelScope.launch {
            _messages.update { it + ChatUiMessage(counter++, "user", text) }
            _isStreaming.value = true
            _error.value = null

            // 带上长期记忆 + 会话历史（含当前消息）：多轮上下文 + 记忆注入
            val memoryText = memoryRepository.memoryContextText()
            when (val result = agent.route(text, memoryText = memoryText, history = session.all)) {
                is AgentResult.Command -> {
                    val hint = executeCommand(result.intent)
                    append(ChatUiMessage(counter++, "assistant", hint))
                    session.addAssistant(hint)
                }
                is AgentResult.Error -> {
                    val msg = "⚠️ ${result.message}"
                    append(ChatUiMessage(counter++, "assistant", msg))
                    session.addAssistant(msg)
                }
                is AgentResult.ChatRequested -> {
                    // 记录类请求：聊天照常流式回复，同时把用户原话同步写入日记本（不丢失）
                    result.recordHint?.let { hint -> writeDiary(text, hint.bookName) }
                    // 所有对话都后台做记忆抽取（importance 过滤兜底）——
                    // 防止"你要记得"这类不带"记录"关键词但值得记住的信息被漏掉
                    extractMemoryInBackground(text)
                    val streamingId = counter++
                    append(ChatUiMessage(streamingId, "assistant", "", streaming = true))
                    val answer = streamReply(result.messages, streamingId)
                    session.addAssistant(answer)
                }
            }
            _isStreaming.value = false
        }
    }

    /**
     * 收集流式回复，实时更新消息文本，返回最终内容。
     * 思考过程与正式回答分开积累、同时展示（思考部分带标注）。
     */
    private suspend fun streamReply(messages: List<ChatMessage>, messageId: Long): String {
        var acc = ""
        var thinking = ""
        try {
            agent.chatStream(messages).collect { chunk ->
                val delta = chunk.choices.firstOrNull()?.delta
                val text = delta?.textContent.orEmpty()
                val think = delta?.reasoningContent.orEmpty()
                if (text.isNotEmpty()) acc += text
                if (think.isNotEmpty()) thinking += think
                updateMessage(messageId) { it.copy(text = acc, thinking = thinking) }
            }
            updateMessage(messageId) { it.copy(streaming = false) }
        } catch (e: Exception) {
            val tail = "\n\n[出错：${e.message}]"
            acc += tail
            updateMessage(messageId) { it.copy(text = acc, streaming = false) }
        }
        return acc
    }

    fun clearConversation() {
        session.clear()
        _messages.value = emptyList()
    }

    private fun append(msg: ChatUiMessage) {
        _messages.update { it + msg }
    }

    private fun updateMessage(id: Long, transform: (ChatUiMessage) -> ChatUiMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /**
     * 执行命令类意图（本地执行，不走 LLM），返回展示给用户的提示文本。
     * 已实现：设提醒（LLM 解析时间 + AlarmManager 排程）；记录类见 saveDiaryFromChat；
     * 事件监控（MonitorEvent）在 P4 事件监控部分接入。
     */
    private suspend fun executeCommand(intent: AssistantIntent): String = when (intent) {
        // 防御：正常路由下 RecordDiary 不会走到这里（Agent 已转为聊天+recordHint）
        is AssistantIntent.RecordDiary -> "📔 已记入日记本"
        is AssistantIntent.SetReminder -> createReminder(intent)
        is AssistantIntent.ScreenSense ->
            "👁️ 收到识屏指令（${intent.action}）\n（识屏功能将在后续阶段开放）"
        is AssistantIntent.MonitorEvent -> createMonitoredEvent(intent)
        is AssistantIntent.Chat -> intent.text
    }

    /** 创建事件监控：LLM 抽取搜索配置 → 入库（轮询由 EventPollWorker 周期执行） */
    private suspend fun createMonitoredEvent(intent: AssistantIntent.MonitorEvent): String {
        val extracted = eventExtractor.extract(intent.query)
            ?: return "🔎 没听清要关注什么。请再说一次，比如：\n\"关注华为手机新品发布\""
        eventRepository.add(
            displayName = extracted.displayName,
            searchQuery = extracted.searchQuery,
            conditionKeywords = extracted.conditionKeywords,
            customRule = extracted.customRule,
            includeDomains = extracted.includeDomains
        )
        return buildString {
            append("🔎 已开始关注「").append(extracted.displayName).append("」（").append(extracted.searchQuery).append("）")
            append("\n来源：").append(extracted.includeDomains.ifBlank { "不限" })
            if (extracted.customRule.isNotBlank()) append("\n规则：").append(extracted.customRule)
            append("\n有重要动态会通知你")
        }
    }

    /** 创建提醒：LLM 解析结构化时间 → 本地算时间戳 → 入库 → AlarmManager 排程 */
    private suspend fun createReminder(intent: AssistantIntent.SetReminder): String {
        val parsed = reminderTimeParser.parse(intent.timeText) ?: return missingTimeHint()
        val triggerAt = reminderTimeParser.resolveTrigger(Calendar.getInstance(), parsed)
        val id = reminderRepository.add(
            title = parsed.title,
            triggerAtEpochMillis = triggerAt,
            repeatRule = parsed.repeat
        )
        reminderScheduler.schedule(reminderRepository.byId(id)!!)
        val timeText = formatTriggerTime(triggerAt)
        val repeatText = when (parsed.repeat) {
            "daily" -> "，每天重复"
            "weekly" -> "，每周重复"
            else -> ""
        }
        return "⏰ 已设置：$timeText 提醒「${parsed.title}」$repeatText"
    }

    private fun missingTimeHint(): String =
        "⏰ 没听清具体时间。请告诉我时间，比如：\n\"提醒我明天下午3点开会\""

    /** 触发时间显示：今天/明天 + HH:mm */
    private fun formatTriggerTime(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        val now = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        val time = "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        val dayDiff = (cal.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR))
        return when {
            dayDiff == 0 -> "今天 $time"
            dayDiff == 1 -> "明天 $time"
            else -> "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 $time"
        }
    }

    /** 聊天同时记录：把用户这句话（原文）写入日记本（按指定名/默认本匹配） */
    private suspend fun writeDiary(text: String, bookName: String?) {
        val books = diaryRepository.books.first()
        val book = books.firstOrNull { it.name == bookName }
            ?: books.firstOrNull { it.isDefault }
            ?: books.firstOrNull()
            ?: return // 还没有日记本（启动时已种子创建，这里防御）
        diaryRepository.addEntry(book.id, text, source = "chat")
    }

    /** 后台静默抽取长期记忆（带重要性过滤，评分不足的不存），失败不打扰用户 */
    private fun extractMemoryInBackground(text: String) {
        viewModelScope.launch {
            val facts = memoryExtractor.extract(text)
            if (facts.isNotEmpty()) memoryRepository.addFacts(facts)
        }
    }
}
