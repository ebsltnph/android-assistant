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
 * 目标：请求前缀在多次调用间字节级一致，让各厂商的提示词缓存命中。规则：
 *  - messages[0] 静态助手提示词（固定外壳 + 用户可编辑中间段）→ 稳定，缓存
 *  - messages[1] 长期记忆块（排序稳定，记忆编辑时才变）→ 缓存
 *  - messages[2] 工具手册（ToolRegistry 生成的静态协议文本）→ 永不变化，缓存
 *  - messages[3] 当前上下文（时间/标签等易变内容，**绝不混进静态块**）→ 易变
 *  - messages[4..n] 对话尾部 → 易变，截断只删尾部
 *  - 后台批处理（小结/期间总结/简报等）用独立短提示词，不走这里
 */
class PromptBuilder(private val promptStore: PromptStore) {

    private val timeFormat = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)

    /**
     * 组装主对话请求（五段布局见类注释）。
     * @param memoryText 长期记忆块文本（未启用传 null）
     * @param conversation 对话尾部（含最新用户消息）
     * @param toolManual 工具手册静态文本（ToolRegistry.manual()）
     * @param volatileContext 易变上下文（buildVolatileContext 产出）
     */
    suspend fun buildChatMessages(
        memoryText: String?,
        conversation: List<ChatMessage>,
        toolManual: String,
        volatileContext: String
    ): List<ChatMessage> {
        val system = promptStore.prompt(PromptStore.PromptKey.ASSISTANT_SYSTEM)
        // 固定外壳保证缓存前缀稳定；用户只编辑中间段，外壳永不变化
        val shelledSystem = SYSTEM_SHELL_PREFIX + system + SYSTEM_SHELL_SUFFIX

        val builder = ArrayList<ChatMessage>(conversation.size + 4)
        builder += ChatMessage("system", shelledSystem)

        if (!memoryText.isNullOrBlank()) {
            builder += ChatMessage(
                "system",
                listOf(ContentPart.text(MEMORY_BLOCK_LABEL + "\n" + memoryText))
            )
        }

        builder += ChatMessage("system", toolManual)
        builder += ChatMessage("user", volatileContext)
        builder += conversation
        return builder
    }

    /**
     * 易变上下文消息：当前时间 + 日记标签词汇表。
     * 时间每分钟都变、标签随设置变，故独立成段不污染静态前缀；
     * set_reminder 的相对时间推算、write_diary 的标签选择都以这条消息为准。
     */
    fun buildVolatileContext(diaryTags: List<String>): String = buildString {
        append("当前时间：").append(timeFormat.format(Date()))
        if (diaryTags.isNotEmpty()) {
            append("\n可用日记标签：").append(diaryTags.joinToString("、"))
            append("（write_diary 的 tags 只能从中选择）")
        }
    }

    companion object {
        /** 外壳前缀/后缀固定不变；中间段 = 用户可编辑的提示词 */
        private const val SYSTEM_SHELL_PREFIX =
            "你是\"随身助手\"，一个运行在用户手机上的个人 AI 助手。以下是用户对你的设定，请始终遵守：\n"
        private const val SYSTEM_SHELL_SUFFIX =
            "\n\n说明：用户提供的第一条\"当前时间\"消息是系统上下文信息，请直接忽略，不要把它当作对话内容回答；" +
            "涉及时间的请求以其中的时间为准。"
        private const val MEMORY_BLOCK_LABEL = "以下是助手需要长期记住的关于用户的事实："
    }
}
