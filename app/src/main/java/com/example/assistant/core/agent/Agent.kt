package com.example.assistant.core.agent

import com.example.assistant.core.agent.tools.ToolOutcome
import com.example.assistant.core.agent.tools.ToolRegistry
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ChatStream
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Agent 编排器（主模型统一调度架构）：
 * 1. 意图路由：仅剩识屏关键词本地直连（瞬时、无需模型）；其余全部走对话回路，
 *    由主聊天模型在回复中自主调用工具完成（提醒/记录/记忆/监控/搜索/读网页/识屏）
 * 2. chatReplyFlow 工具回路：模型流式输出 → 解析 [调用] 行 → 本地执行 → 结果回传 →
 *    模型继续（可自纠重试、连环调用），直到给出正式回答
 * 3. 缓存友好：静态块（系统外壳/工具手册）与易变块（时间/标签）分离，见 PromptBuilder
 */
class Agent(
    private val providerRegistry: ProviderRegistry,
    private val promptBuilder: PromptBuilder,
    private val intentRouter: IntentRouter,
    private val toolRegistry: ToolRegistry
) {

    /** 单次回复的事件流（chatReplyFlow 产出，界面层据此渲染气泡） */
    sealed interface ReplyEvent {
        /** 流式增量：text 在标记缓冲期为空串；thinking 为思考增量 */
        data class Delta(val text: String, val thinking: String) : ReplyEvent

        /**
         * 一轮流结束：本轮保留的正文并入气泡基线（纯调用轮 prose 为空串=仅重置流式区），
         * 下一轮的流式文本从基线之后追加显示。
         */
        data class RoundSettled(val prose: String) : ReplyEvent

        /** 正在执行一批工具调用（界面显示「🔧 …」状态） */
        data class ToolsRunning(val labels: List<String>) : ReplyEvent

        /** 最终回答（answer 已含执行页脚）；toolNames = 成功执行过的工具名；exchanges = 工具中间轮 */
        data class Final(
            val answer: String,
            val toolNames: List<String>,
            val exchanges: List<Pair<String, String>>
        ) : ReplyEvent
    }

    sealed interface AgentResult {
        /** 需要走对话回路（流式 + 工具循环） */
        data class ChatRequested(
            val messages: List<ChatMessage>
        ) : AgentResult

        /** 命令类意图（目前仅识屏关键词直连；由上层处理） */
        data class Command(val intent: AssistantIntent) : AgentResult

        /** 未配置模型等无法处理的情况 */
        data class Error(val message: String) : AgentResult
    }

    /**
     * 路由用户消息。识屏关键词本地直连（瞬时、离线可用）；其余一律进对话回路，
     * 由主模型决定是否调用工具、调用哪个——不再有独立的意图分类/判断调用。
     *
     * @param history 会话历史对话尾部（应已含当前用户消息）
     * @param diaryTags 用户自定义日记标签词汇表（进易变上下文，供 write_diary 选标签）
     */
    suspend fun route(
        text: String,
        memoryText: String? = null,
        history: List<ChatMessage> = emptyList(),
        diaryTags: List<String> = emptyList()
    ): AgentResult {
        // 关键词只保留识屏直连
        val keyword = intentRouter.keywordRoute(text)
        if (keyword != null) return AgentResult.Command(keyword)
        return chatRequested(text, memoryText, history, diaryTags)
    }

    /** 组装对话回路的初始请求消息 */
    private suspend fun chatRequested(
        text: String,
        memoryText: String?,
        history: List<ChatMessage>,
        diaryTags: List<String>
    ): AgentResult {
        val profile = providerRegistry.profileFor(Capability.CHAT)
            ?: return AgentResult.Error("尚未配置模型提供商，请到「设置」填写")
        if (!profile.isConfigured()) {
            return AgentResult.Error("模型提供商未配置完整，请到「设置」检查")
        }
        val conversation = if (history.isNotEmpty()) history else listOf(ChatMessage("user", text))
        val messages = promptBuilder.buildChatMessages(
            memoryText = memoryText,
            conversation = conversation,
            toolManual = toolRegistry.manual(),
            volatileContext = promptBuilder.buildVolatileContext(diaryTags)
        )
        return AgentResult.ChatRequested(messages)
    }

    /**
     * 主模型驱动的工具回路（架构核心）：
     *
     *   流式收集一轮回复
     *     ├─ 存在合法"[调用]"行 → 执行全部调用，结果以 user 消息接回对话，再收集一轮
     *     │   （纯调用轮整轮隐藏；混合轮正文剥掉调用行后累加保留，不丢失）
     *     └─ 否则视为正式回答结束
     *
     * 单次回复最多 MAX_TOOL_ROUNDS 个工具轮；超限注入强制收尾指令；
     * guard 上限双保险保证任何情况下必然终止。
     */
    fun chatReplyFlow(baseMessages: List<ChatMessage>): Flow<ReplyEvent> = flow {
        var messages = baseMessages
        var forcedFinal = false
        var toolRounds = 0
        val finalized = StringBuilder()                        // 各轮保留正文的累加
        val usedToolNames = LinkedHashSet<String>()           // 成功执行过的工具名
        val exchanges = mutableListOf<Pair<String, String>>() // (模型输出原文, 回传的结果消息)

        fun absorbProse(prose: String) {
            val p = prose.trim()
            if (p.isEmpty()) return
            if (finalized.isNotEmpty()) finalized.append("\n\n")
            finalized.append(p)
        }

        var guard = 0
        while (true) {
            if (++guard > GUARD_LIMIT) break

            // ---- 一轮流式收集（开头标记缓冲：疑似"[调用"时不上屏）----
            var acc = ""
            var released = false
            chatStream(messages).collect { chunk ->
                val delta = chunk.choices.firstOrNull()?.delta
                val t = delta?.textContent.orEmpty()
                val th = delta?.reasoningContent.orEmpty()
                if (t.isNotEmpty()) acc += t
                if (!released && toolRegistry.stillBuffering(acc)) {
                    if (th.isNotEmpty()) emit(ReplyEvent.Delta("", th))
                } else {
                    released = true
                    emit(ReplyEvent.Delta(t, th))
                }
            }

            // ---- 判定这一轮是否发起工具调用 ----
            val calls = toolRegistry.parseCalls(acc)
            val act = !forcedFinal && toolRounds < ToolRegistry.MAX_TOOL_ROUNDS && calls.isNotEmpty()
            if (!act) {
                absorbProse(toolRegistry.stripCallLines(acc))
                emit(finish(finalized, usedToolNames, exchanges))
                return@flow
            }

            toolRounds++
            val pure = toolRegistry.isPureCallTurn(acc)
            val roundProse = if (pure) "" else toolRegistry.stripCallLines(acc).trim()
            absorbProse(roundProse)
            emit(ReplyEvent.RoundSettled(roundProse))

            // ---- 执行全部调用并组装结果消息 ----
            emit(ReplyEvent.ToolsRunning(calls.map { it.label }))
            val sb = StringBuilder("[结果]")
            calls.forEachIndexed { i, call ->
                val outcome = toolRegistry.execute(call)
                sb.append("\n\n").append(i + 1).append(". tool=").append(call.tool.name)
                when (outcome) {
                    is ToolOutcome.Success -> {
                        usedToolNames += call.tool.name
                        sb.append("｜状态：成功\n").append(outcome.feedback)
                    }
                    is ToolOutcome.Failure ->
                        sb.append("｜状态：失败\n").append(outcome.error)
                }
            }
            sb.append("\n\n请根据以上结果继续：信息足够就直接给出正式回答；有失败可修正参数重新调用（剩余次数有限），或如实告知用户。")
            val resultsMsg = sb.toString()

            messages = messages + ChatMessage("assistant", acc) + ChatMessage("user", resultsMsg)
            exchanges += acc to resultsMsg

            if (toolRounds >= ToolRegistry.MAX_TOOL_ROUNDS && !forcedFinal) {
                messages += ChatMessage("user", ToolRegistry.FORCED_FINAL_NOTE)
                forcedFinal = true
            }
        }

        // guard 兜底出口（正常流程到不了这里）
        emit(finish(finalized, usedToolNames, exchanges))
    }

    /** 组装最终回答：正文 + 已执行工具页脚 */
    private fun finish(
        finalized: StringBuilder,
        usedToolNames: Set<String>,
        exchanges: List<Pair<String, String>>
    ): ReplyEvent.Final {
        val body = finalized.toString().ifBlank { "（模型没有返回内容，请重试或换个说法）" }
        val answer = if (usedToolNames.isEmpty()) body
        else body + "\n\n🔧 已执行：" + usedToolNames.joinToString("、")
        return ReplyEvent.Final(answer, usedToolNames.toList(), exchanges.toList())
    }

    /** 发送流式对话请求（单次请求；工具回路由 chatReplyFlow 编排多次调用本方法） */
    suspend fun chatStream(messages: List<ChatMessage>): Flow<ChatResponse> {
        val profile = providerRegistry.profileFor(Capability.CHAT)
            ?: throw IllegalStateException("未配置对话提供商")
        val api = providerRegistry.apiFor(profile)
        val effort = providerRegistry.reasoningEffortFor(profile)
        val request = ChatRequest(
            model = profile.model,
            messages = messages,
            temperature = 0.7,
            // 4096：推理模型思考占配额，且多轮工具场景回答更长（2048 曾被吃光）
            maxTokens = 4096,
            stream = true,
            reasoningEffort = effort
        )
        val header = providerRegistry.authHeader(profile.apiKey)
        return flow {
            val response = providerRegistry.chatStreamCompat(profile, request, header, api)
            emitAll(ChatStream.parse(response.body()!!))
        }
    }

    /** 测试连接：对指定档案发一个最小请求验证配置是否正确（per-provider） */
    suspend fun testConnection(profile: ProviderProfile): Result<String> {
        return try {
            val api = providerRegistry.apiFor(profile)
            val effort = providerRegistry.reasoningEffortFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(ChatMessage("user", "你好，请回复\"连接成功\"四个字")),
                // 512：推理模型的思考过程占配额，20 会被吃光导致空响应
                maxTokens = 512,
                reasoningEffort = effort
            )
            val header = providerRegistry.authHeader(profile.apiKey)
            val response = providerRegistry.chatCompat(profile, request, header, api)
            val reply = response.choices.firstOrNull()?.message?.textContent
                ?: return Result.failure(IllegalStateException("响应中没有内容"))
            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /** 循环保险丝：正常最多 纯答/多轮工具+强制收尾 轮 */
        private const val GUARD_LIMIT = 10
    }
}
