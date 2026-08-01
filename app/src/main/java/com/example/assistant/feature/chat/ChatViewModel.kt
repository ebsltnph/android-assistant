package com.example.assistant.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.Agent.AgentResult
import com.example.assistant.core.agent.AssistantIntent
import com.example.assistant.core.agent.MemoryExtractor
import com.example.assistant.core.agent.Session
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val memoryExtractor: MemoryExtractor
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
     * 已实现：无（记录类已改为聊天+同步写日记，见 saveDiaryFromChat）；其余命令后续阶段接入。
     */
    private suspend fun executeCommand(intent: AssistantIntent): String = when (intent) {
        // 防御：正常路由下 RecordDiary 不会走到这里（Agent 已转为聊天+recordHint）
        is AssistantIntent.RecordDiary -> "📔 已记入日记本"
        is AssistantIntent.SetReminder ->
            "⏰ 收到提醒需求：\"${intent.title}\"\n（定时提醒功能将在后续阶段开放）"
        is AssistantIntent.ScreenSense ->
            "👁️ 收到识屏指令（${intent.action}）\n（识屏功能将在后续阶段开放）"
        is AssistantIntent.MonitorEvent ->
            "🔎 收到事件关注需求：\"${intent.query}\"\n（事件监控功能将在后续阶段开放）"
        is AssistantIntent.Chat -> intent.text
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
