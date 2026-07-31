package com.example.assistant.core.agent

import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ContentPart
import com.example.assistant.core.storage.PromptStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 缓存友好的消息组装（架构核心）。
 *
 * 目标：请求前缀在多次调用间字节级一致，让各厂商的提示词缓存（DeepSeek 上下文缓存、
 * OpenAI 提示词缓存等）命中。规则：
 *  - messages[0] 静态助手提示词（固定外壳 + 用户可编辑中间段）→ 稳定，缓存
 *  - messages[1] 长期记忆块（排序稳定，记忆编辑时才变）→ 缓存
 *  - messages[2] 当前上下文（日期时间等易变信息，**绝不放系统提示词**）→ 易变
 *  - messages[3..n] 对话尾部 → 易变，截断只删尾部
 *  - 每日总结/记忆抽取/意图分类用独立短提示词，不走这里
 */
class PromptBuilder(private val promptStore: PromptStore) {

    private val timeFormat = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)

    /**
     * 组装主对话请求。
     * @param memoryText 长期记忆块文本（未启用传 null）
     * @param conversation 对话尾部（含最新用户消息）
     */
    suspend fun buildChatMessages(
        memoryText: String?,
        conversation: List<ChatMessage>,
        extraContext: String? = null
    ): List<ChatMessage> {
        val system = promptStore.prompt(PromptStore.PromptKey.ASSISTANT_SYSTEM)
        // 固定外壳保证缓存前缀稳定；用户只编辑中间段，外壳永不变化
        val shelledSystem = SYSTEM_SHELL_PREFIX + system + SYSTEM_SHELL_SUFFIX

        val builder = ArrayList<ChatMessage>(conversation.size + 3)
        builder += ChatMessage("system", shelledSystem)

        if (!memoryText.isNullOrBlank()) {
            builder += ChatMessage(
                "system",
                listOf(ContentPart.text(MEMORY_BLOCK_LABEL + "\n" + memoryText))
            )
        }

        // 当前上下文（日期时间等易变信息放这里，避免每天/每小时失效缓存前缀）
        val context = buildString {
            append("当前时间：").append(timeFormat.format(Date()))
            if (!extraContext.isNullOrBlank()) {
                append("\n").append(extraContext)
            }
        }
        builder += ChatMessage("user", context)

        builder += conversation
        return builder
    }

    companion object {
        /** 外壳前缀/后缀固定不变；中间段 = 用户可编辑的提示词 */
        private const val SYSTEM_SHELL_PREFIX =
            "你是\"随身助手\"，一个运行在用户手机上的个人 AI 助手。以下是用户对你的设定，请始终遵守：\n"
        private const val SYSTEM_SHELL_SUFFIX =
            "\n\n说明：用户提供的第一条\"当前时间\"消息是上下文信息，请直接忽略，不要把它当作对话内容回答。"
        private const val MEMORY_BLOCK_LABEL = "以下是助手需要长期记住的关于用户的事实："
    }
}
