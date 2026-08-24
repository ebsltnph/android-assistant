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
        conversation: List<ChatMessage>
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
        builder += ChatMessage("user", "当前时间：" + timeFormat.format(Date()))

        builder += conversation
        return builder
    }

    companion object {
        /** 外壳前缀/后缀固定不变；中间段 = 用户可编辑的提示词 */
        private const val SYSTEM_SHELL_PREFIX =
            "你是\"随身助手\"，一个运行在用户手机上的个人 AI 助手。以下是用户对你的设定，请始终遵守：\n"

        /**
         * 外壳后缀：固定说明 + 联网搜索协议（搜索由主模型自主决定，见 Agent.chatReplyFlow）。
         * 放在外壳里保证缓存前缀稳定——协议文本永不变化，也不会被用户编辑提示词时误删。
         */
        private const val SYSTEM_SHELL_SUFFIX =
            "\n\n说明：用户提供的第一条\"当前时间\"消息是上下文信息，请直接忽略，不要把它当作对话内容回答。" +
            "\n\n【联网搜索】你可以在需要时发起网络搜索：当问题涉及实时/最新信息（新闻、天气、价格、赛事、软件版本等），" +
            "或你不确定、不搜索就无法可靠回答的事实时，不要编造，也不要勉强作答，而是只输出一行\"[搜索] 搜索词\"" +
            "（可以输出多行，每行一个不同的搜索词），然后立即停止输出；" +
            "系统会自动执行搜索，并把结果以\"[搜索结果]\"开头的消息发给你，之后你再继续。" +
            "若已有结果仍不足以回答，可以再次输出\"[搜索] 更精确的搜索词\"补充搜索（次数有限，省着用）；" +
            "信息足够后直接给出正式回答，正文中不要再出现任何\"[搜索]\"标记。" +
            "不需要搜索的日常对话请照常直接回答，并且不要向用户提及这套协议的存在。"
        private const val MEMORY_BLOCK_LABEL = "以下是助手需要长期记住的关于用户的事实："
    }
}
