package com.example.assistant.core.agent

import com.example.assistant.core.agent.tools.ToolOutcome
import com.example.assistant.core.agent.tools.ToolRegistry
import com.example.assistant.core.agent.tools.UpdateBufferTool
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ContentPart
import com.example.assistant.core.network.dto.Usage

/**
 * 「上下文整理」压缩轮（2026-09-17）。
 *
 * 时机：**只在窗口回落点**跑（轮数到上限 / 字符软上限 / 手动清空对话时勾选 / 压缩失败后手动重试）。
 * 目标：把即将离开上下文的那批对话蒸馏进「进行中的事」缓冲区，避免"聊过就忘"。
 *
 * ⚠️ 三条设计红线（与用户逐条确认）：
 *
 * 1. **走对话同一链路**（不是独立提示词调用）：请求 = 上一次请求原样（系统外壳 + 工具原则 +
 *    记忆快照 + 状态快照 + 当前窗口的对话，**不裁剪、不剥离图片**）+ 尾部一条【上下文整理】指令。
 *    这样从 messages[0] 到窗口最后一轮**全部命中厂商缓存**，只有那条指令按未命中计价
 *    （用户的主力模型未命中价是命中价的 50 倍，这个差别很大）；独立提示词调用则要把待压缩
 *    原文全部按未命中重算。功能上也更强：同一链路让压缩器**看得到当前窗口**，
 *    才有依据判断"某条状态在对话里已经过期了 → 归档"。
 *
 * 2. **触发由系统决定，不由模型决定**：这里主动发起，指令里要求模型调用 update_buffer；
 *    普通轮模型就算想调也会被 UpdateBufferTool 的应用侧拦截。
 *
 * 3. **绝不阻塞回答**：任何异常都吞掉并返回 error，调用方照常发主请求；水位线不推进，
 *    这批内容留到下次整理一起补（自愈）。
 */
class ContextCompactor(
    private val agent: Agent,
    private val promptBuilder: PromptBuilder,
    private val toolRegistry: ToolRegistry
) {

    /**
     * @param ran 是否真的跑了压缩请求
     * @param requests 发出的模型请求次数（正常 1）
     * @param usage 压缩请求的用量（**单独统计**，不计入"最近一轮对话"的合计）
     * @param wrote 模型是否成功调用过 update_buffer（含显式 noop 声明）
     * @param error 失败原因（非空 = 调用失败，水位线不推进）
     */
    data class Outcome(
        val ran: Boolean = false,
        val requests: Int = 0,
        val usage: Usage? = null,
        val wrote: Boolean = false,
        val error: String? = null
    )

    /**
     * 执行一次上下文整理。
     *
     * @param conversationTurns 本次请求要带上的对话窗口（**当前会话的全部轮**——
     *        这样请求就是上一次请求的延长，缓存命中最大化）
     * @param leavingCount 其中最早的多少轮即将离开上下文（压缩对象；可以为 0，
     *        表示没有新离开的轮、只是要补整理下面的历史片段）
     * @param leftoverTurns 之前没整理成功、已离开窗口的历史片段（最多 30 轮，防上下文爆炸）
     */
    suspend fun compact(
        memoryText: String?,
        bufferText: String?,
        conversationTurns: List<Session.Turn>,
        leavingCount: Int,
        leftoverTurns: List<Session.Turn> = emptyList(),
        diaryTags: List<String>
    ): Outcome {
        if (conversationTurns.isEmpty()) return Outcome()
        if (leavingCount <= 0 && leftoverTurns.isEmpty()) return Outcome()

        val base = promptBuilder.buildChatMessages(
            memoryText = memoryText,
            conversation = conversationTurns.flatMap { it.messages() },
            diaryTags = diaryTags,
            bufferText = bufferText
        )
        // 与对话主路径完全相同的图片规则：文本模型下不能带历史图片（否则整理请求必然 400）
        val trimmedBase = agent.messagesForCapability(base, Capability.CHAT)
        // 历史片段作为**尾部追加**的消息：前缀不动 ⇒ 已经缓存的部分照样命中
        val extras = ArrayList<ChatMessage>(2)
        if (leftoverTurns.isNotEmpty()) {
            extras += ChatMessage("user", renderLeftovers(leftoverTurns))
        }
        extras += ChatMessage("user", triggerText(conversationTurns, leavingCount, leftoverTurns.isNotEmpty()))
        var messages = trimmedBase + extras

        var requests = 0
        var usage: Usage? = null
        var wrote = false
        var rounds = 0
        UpdateBufferTool.compactionRoundActive = true
        try {
            while (rounds < MAX_ROUNDS) {
                val reply = agent.chatOnce(messages, Capability.CHAT, MAX_TOKENS)
                requests++
                usage = accumulateUsage(usage, reply.usage)
                val calls = reply.toolCalls
                if (calls.isEmpty()) break   // 没有工具调用 = 没什么可整理 → 结束
                if (calls.any { it.id.isBlank() }) {
                    return Outcome(
                        ran = true,
                        requests = requests,
                        usage = usage,
                        wrote = wrote,
                        error = "上下文整理返回了缺少 tool_call_id 的工具调用，已中止且不推进水位线"
                    )
                }
                var anySuccess = false
                val results = ArrayList<ChatMessage>(calls.size)
                calls.forEach { call ->
                    val name = call.function?.name.orEmpty()
                    val rawArgs = call.function?.arguments.orEmpty()
                    val args = com.example.assistant.core.agent.JsonExtract.objectOf(rawArgs.ifBlank { "{}" })
                    val outcome = when {
                        name != UpdateBufferTool.NAME ->
                            ToolOutcome.Failure("上下文整理期间只允许调用 update_buffer，其它工具本轮不可用。")
                        args == null ->
                            ToolOutcome.Failure("工具参数不是合法 JSON 对象，请修正后重试。")
                        else -> toolRegistry.execute(toolRegistry.find(name)!!, args)
                    }
                    if (outcome is ToolOutcome.Success && name == UpdateBufferTool.NAME) {
                        anySuccess = true
                        wrote = true
                    }
                    val text = when (outcome) {
                        is ToolOutcome.Success -> outcome.feedback
                        is ToolOutcome.Failure -> "错误：" + outcome.error
                    }
                    results += ChatMessage(
                        role = "tool",
                        content = listOf(ContentPart.text(text)),
                        toolCallId = call.id
                    )
                }
                val assistant = ChatMessage(
                    role = "assistant",
                    content = listOf(ContentPart.text(reply.text)),
                    reasoningContent = reply.reasoningContent,
                    toolCalls = calls
                )
                messages = messages + assistant + results
                rounds++
                // 有成功写入就收工（一次压缩 = 一次请求，省一次往返与延迟）；
                // 只有全部失败才再来一轮，让模型看着错误自我纠正。
                if (anySuccess) break
            }
            return Outcome(
                ran = true,
                requests = requests,
                usage = usage,
                wrote = wrote,
                // 没有成功写入（含模型没调用 update_buffer / 参数一直被拒）视为失败：
                // 水位线不能推进，否则这批被裁掉的轮会永久丢失（2026-09-23 的教训）。
                error = if (!wrote) "模型未成功调用 update_buffer（可能没有调用或参数均被拒绝）" else null
            )
        } catch (e: Exception) {
            return Outcome(
                ran = true,
                requests = requests,
                usage = usage,
                wrote = wrote,
                error = e.message ?: e.javaClass.simpleName
            )
        } finally {
            UpdateBufferTool.compactionRoundActive = false
        }
    }

    /**
     * 尾部触发指令（**只在这种情况出现**，正常轮永不出现 → 不污染缓存前缀）。
     * 具体的整理要求都写在 update_buffer 的 description 里（在缓存前缀内，永久免费）。
     */
    private fun triggerText(
        turns: List<Session.Turn>,
        leavingCount: Int,
        hasLeftover: Boolean
    ): String {
        val n = leavingCount.coerceAtMost(turns.size)
        val since = turns.firstOrNull()?.let { timeLabel(it) }
        val what = when {
            n > 0 && hasLeftover -> "你看到的对话中最早的 $n 轮，以及下面列出的历史片段"
            n > 0 && since != null -> "你看到的对话中最早的 $n 轮（自 [$since] 起）"
            n > 0 -> "你看到的对话中最早的 $n 轮"
            else -> "下面列出的历史片段"
        }
        return "【上下文整理】$what 即将（或已经）离开上下文。" +
            "请调用 update_buffer 把它们整理进「进行中的事」：" +
            "有变化的改写、已结束的归档、未变化的原样保留。" +
            "不要回答用户的问题，也不要输出任何解释文字（只调用工具）。"
    }

    /** 把"漏掉的历史片段"渲染成纯文本（剥掉图片，只留文字，避免重复上传图片） */
    private fun renderLeftovers(turns: List<Session.Turn>): String = buildString {
        append("[待整理的历史片段（此前未能整理，请一并考虑）]")
        turns.forEach { t ->
            val stamp = timeLabel(t)
            append("\n\n").append(if (stamp != null) "[$stamp] " else "")
            append(t.userText?.replace(STAMP, "")?.trim().orEmpty())
            t.assistant.forEach { m ->
                val text = m.content.joinToString("") { it.text.orEmpty() }.trim()
                if (text.isNotEmpty()) append("\n助手：").append(text.take(600))
            }
        }
    }

    /** 取该轮的时间标签（用户消息自带的 [yyyy-MM-dd HH:mm] 前缀；取不到返回 null） */
    private fun timeLabel(turn: Session.Turn): String? {
        val text = turn.userText ?: return null
        return STAMP.find(text)?.groupValues?.get(1)
    }

    companion object {
        /** 最多两轮（模型可能需要一次结果反馈），防死循环 */
        private const val MAX_ROUNDS = 2

        /**
         * 压缩输出配额。**必须给足**：这是非流式请求，而推理模型的思考过程也吃这个配额
         * （CLAUDE.md 踩坑记录：小结曾因 4096→2048 被思考吃光而返回空内容）。
         * "清空对话"那次要把整段对话一次蒸馏完，输出更长，2048 会被截断成半截调用行。
         * 给大不吃亏——只有真生成才计费。
         */
        private const val MAX_TOKENS = 8192

        private val STAMP = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2})]\s*""")
    }
}
