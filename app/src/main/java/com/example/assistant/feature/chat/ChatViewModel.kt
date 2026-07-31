package com.example.assistant.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.agent.Agent.AgentResult
import com.example.assistant.core.agent.AssistantIntent
import com.example.assistant.core.agent.Session
import com.example.assistant.core.network.dto.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class ChatViewModel(private val agent: Agent) : ViewModel() {

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

            when (val result = agent.route(text)) {
                is AgentResult.Command -> {
                    val hint = commandHint(result.intent)
                    append(ChatUiMessage(counter++, "assistant", hint))
                    session.addAssistant(hint)
                }
                is AgentResult.Error -> {
                    val msg = "⚠️ ${result.message}"
                    append(ChatUiMessage(counter++, "assistant", msg))
                    session.addAssistant(msg)
                }
                is AgentResult.ChatRequested -> {
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

    private fun commandHint(intent: AssistantIntent): String = when (intent) {
        is AssistantIntent.RecordDiary ->
            "📔 好的，为你记录：\"${intent.content}\"${intent.bookName?.let { "（记入「$it」日记本）" } ?: ""}\n（日记功能将在下一阶段开放，先把这句话当作记录吧）"
        is AssistantIntent.SetReminder ->
            "⏰ 收到提醒需求：\"${intent.title}\"\n（定时提醒功能将在后续阶段开放）"
        is AssistantIntent.ScreenSense ->
            "👁️ 收到识屏指令（${intent.action}）\n（识屏功能将在后续阶段开放）"
        is AssistantIntent.MonitorEvent ->
            "🔎 收到事件关注需求：\"${intent.query}\"\n（事件监控功能将在后续阶段开放）"
        is AssistantIntent.Chat -> intent.text
    }
}
