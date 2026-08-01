package com.example.assistant.core.agent

import com.example.assistant.core.network.dto.ChatMessage

/**
 * 会话对话尾部管理：只保留最近约 10 轮。
 * 截断时永远删最早的消息（消息 0/1/2 的缓存前缀由 PromptBuilder 重建，不在此维护）。
 */
class Session(private val maxTurns: Int = 10) {

    private val messages = ArrayDeque<ChatMessage>()

    val all: List<ChatMessage> get() = messages.toList()

    fun addUser(text: String) {
        messages.addLast(ChatMessage("user", text))
        trim()
    }

    fun addAssistant(text: String) {
        messages.addLast(ChatMessage("assistant", text))
        trim()
    }

    /** 替换最后一条助手消息（"重新生成"后保持上下文一致）；空会话时忽略 */
    fun replaceLastAssistant(text: String) {
        if (messages.isEmpty()) return
        messages.removeLast()
        messages.addLast(ChatMessage("assistant", text))
    }

    fun clear() = messages.clear()

    private fun trim() {
        while (messages.size > maxTurns * 2) {
            messages.removeFirst()
        }
    }
}
