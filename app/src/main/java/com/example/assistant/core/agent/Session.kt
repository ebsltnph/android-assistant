package com.example.assistant.core.agent

import com.example.assistant.core.network.dto.ChatMessage

/**
 * 会话对话尾部管理：只保留最近约 10 轮。
 * 截断时永远删最早的消息（消息 0/1/2 的缓存前缀由 PromptBuilder 重建，不在此维护）。
 */
class Session(private var maxTurns: Int = 10) {

    private val messages = ArrayDeque<ChatMessage>()

    /**
     * 调整上下文长度并立即按新阈值截断（永远删最早的消息，不丢新消息）。
     * 设置页「聊天上下文长度」实时生效时调用。
     */
    fun setMaxTurns(turns: Int) {
        if (turns < 1) return
        maxTurns = turns
        trim()
    }

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

    /**
     * 撤回最后一轮（编辑重发用）：从尾部找到最后一条 user 消息，
     * 把它和它后面的所有消息（助手回复、工具轮结果等）一并删除。
     * 没有用户消息时不做任何事。
     */
    fun removeLastTurn() {
        val idx = messages.indexOfLast { it.role == "user" }
        if (idx >= 0) {
            while (messages.size > idx) messages.removeLast()
        }
    }

    fun clear() = messages.clear()

    private fun trim() {
        while (messages.size > maxTurns * 2) {
            messages.removeFirst()
        }
    }
}
