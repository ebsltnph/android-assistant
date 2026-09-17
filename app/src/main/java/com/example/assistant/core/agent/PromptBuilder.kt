package com.example.assistant.core.agent

import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ContentPart
import com.example.assistant.core.storage.PromptStore

/**
 * 缓存友好的消息组装（架构核心）。
 *
 * 目标：请求前缀在多次调用间字节级一致，让各厂商的提示词缓存命中。规则：
 *  - messages[0] 静态助手提示词（固定外壳 + 用户可编辑中间段）→ 稳定，缓存
 *  - messages[1] 工具手册 + 日记标签词汇表（手册是代码常量；标签只在用户改设置时变）→ 缓存
 *  - messages[2] 长期记忆块（排序稳定，记忆编辑时才变）→ 缓存
 *  - messages[3..n] 对话尾部（**每个用户消息自带创建时刻的时间戳**）→ 逐轮延长，缓存
 *  - 后台批处理（小结/期间总结/简报等）用独立短提示词，不走这里
 *
 * ⚠️ 分层原则：块的顺序按"最稳定 → 最易变"排。**绝不要**把易变内容（比如"当前时间"）
 * 放在对话之前——提示词缓存按最长公共前缀命中，中间插一条每分钟都变的消息会让
 * 其后的整段对话历史永远无法命中（2026-09-11 修正：当前时间已改由用户消息时间戳承载）。
 */
class PromptBuilder(private val promptStore: PromptStore) {

    /**
     * 组装主对话请求（分段布局见类注释）。
     * @param memoryText 长期记忆块文本（未启用传 null）
     * @param conversation 对话尾部（含最新用户消息；用户消息已带时间戳）
     * @param toolManual 工具手册静态文本（ToolRegistry.manual()）
     * @param diaryTags 日记标签词汇表（并入手册块；空则不追加）
     */
    suspend fun buildChatMessages(
        memoryText: String?,
        conversation: List<ChatMessage>,
        toolManual: String,
        diaryTags: List<String> = emptyList()
    ): List<ChatMessage> {
        val system = promptStore.prompt(PromptStore.PromptKey.ASSISTANT_SYSTEM)
        // 固定外壳保证缓存前缀稳定；用户只编辑中间段，外壳永不变化
        val shelledSystem = SYSTEM_SHELL_PREFIX + system + SYSTEM_SHELL_SUFFIX

        val builder = ArrayList<ChatMessage>(conversation.size + 3)
        builder += ChatMessage("system", shelledSystem)
        // 手册 + 标签：标签变动频率极低（用户改设置），放这里比"每轮重建的易变消息"缓存友好
        builder += ChatMessage("system", manualWithTags(toolManual, diaryTags))

        if (!memoryText.isNullOrBlank()) {
            builder += ChatMessage(
                "system",
                listOf(ContentPart.text(MEMORY_BLOCK_LABEL + "\n" + memoryText))
            )
        }

        builder += conversation
        return builder
    }

    /** 工具手册 + 可用日记标签（供 write_diary 选标签；没有标签时不追加） */
    fun manualWithTags(toolManual: String, diaryTags: List<String>): String =
        if (diaryTags.isEmpty()) toolManual
        else toolManual + "\n可用日记标签：" + diaryTags.joinToString("、") +
            "（write_diary 的 tags 只能从中选择）"

    /**
     * 静态前缀（对话尾部之前的所有块）的指纹，2026-09-14。
     *
     * 用途：这些块一变，厂商缓存的**最长公共前缀**就在对话之前断掉了——整段历史全部失效。
     * 会话层（Session.buildContext）据此把上下文窗口回落到下限重新起跑，
     * 而不是白扛着 U 轮历史每轮付全价。指纹只做相等比较，不对外展示、不落盘。
     */
    suspend fun prefixSignature(
        memoryText: String?,
        toolManual: String,
        diaryTags: List<String>
    ): String {
        val system = promptStore.prompt(PromptStore.PromptKey.ASSISTANT_SYSTEM)
        val raw = SYSTEM_SHELL_PREFIX + system + SYSTEM_SHELL_SUFFIX + SEP +
            manualWithTags(toolManual, diaryTags) + SEP +
            MEMORY_BLOCK_LABEL + SEP + memoryText.orEmpty()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 指纹分段分隔符（内容里不可能出现的控制字符） */
        private const val SEP = "\u0000"

        /** 外壳前缀/后缀固定不变；中间段 = 用户可编辑的提示词 */
        private const val SYSTEM_SHELL_PREFIX =
            "你是\"随身助手\"，一个运行在用户手机上的个人 AI 助手。以下是用户对你的设定，请始终遵守：\n"
        private const val SYSTEM_SHELL_SUFFIX =
            "\n\n说明：每条用户消息开头的 [年-月-日 时:分] 是这条消息的发送时间（系统自动添加，" +
                "不是用户输入的内容），需要判断当前时间或推算相对时间时，以最后一条用户消息的时间为准。"
        private const val MEMORY_BLOCK_LABEL = "以下是助手需要长期记住的关于用户的事实："
    }
}
