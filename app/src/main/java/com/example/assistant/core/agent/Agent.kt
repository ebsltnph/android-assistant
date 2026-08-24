package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ChatStream
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.SearchClient
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Agent 编排器：
 * 1. 意图路由（关键词 → LLM 分类 → 聊天兜底）
 * 2. 记录类意图（说"记录…"或 LLM 判定）不拦截：聊天照常回复 + 同步写日记（recordHint）
 * 3. 命令类意图（识屏/提醒/监控）由上层分发到对应处理器
 * 4. 对话类意图走 LLM 流式回复（缓存友好的消息结构，见 PromptBuilder）
 * 5. 联网搜索由**主模型自主决定**（不再单独调用判断模型）：系统提示词外壳带搜索协议，
 *    模型需要时输出"[搜索] 搜索词"，这里执行搜索把结果发回，模型可继续补搜（有上限），
 *    直到给出正式回答。见 [chatReplyFlow]。
 */
class Agent(
    private val providerRegistry: ProviderRegistry,
    private val promptBuilder: PromptBuilder,
    private val intentRouter: IntentRouter,
    private val searchClient: SearchClient
) {

    /**
     * 单次回复的事件流（chatReplyFlow 产出，界面层据此渲染气泡）。
     */
    sealed interface ReplyEvent {
        /** 流式增量：text 为本轮流式文本增量；开头疑似"[搜索"标记的缓冲期 text 为空串（thinking 正常累计） */
        data class Delta(val text: String, val thinking: String) : ReplyEvent

        /** 主模型请求了搜索，正在执行（界面可显示「正在搜索…」状态） */
        data class Searching(val queries: List<String>) : ReplyEvent

        /** 最终回答（answer 已含搜索页脚）；exchanges = 搜索中间轮记录，写回会话历史用 */
        data class Final(
            val answer: String,
            val searchedQueries: List<String>,
            val exchanges: List<Pair<String, String>>
        ) : ReplyEvent
    }

    sealed interface AgentResult {
        /** 需要走 LLM 对话（流式） */
        data class ChatRequested(
            val messages: List<ChatMessage>,
            /** 记录提示：非空表示聊天同时要把用户原话写入日记（bookName 为 null 时写默认日记本） */
            val recordHint: RecordHint? = null
        ) : AgentResult

        /** 命令类意图（本地执行，由上层处理） */
        data class Command(val intent: AssistantIntent) : AgentResult

        /** 未配置模型等无法处理的情况 */
        data class Error(val message: String) : AgentResult
    }

    /** 记录提示：聊天照常流式回复，同时把用户这句话写入日记本 */
    data class RecordHint(val bookName: String? = null)

    /**
     * 路由用户消息，返回执行结果。
     * 记录类意图（说"记录…"或 LLM 分类判定）不拦截：转为「聊天照常回复 + 同步写日记」，
     * 由上层在流式回复的同时把用户原话写入日记本。
     *
     * @param history 会话历史对话尾部（应已含当前用户消息）；不传则只带当前消息
     */
    suspend fun route(text: String, memoryText: String? = null, history: List<ChatMessage> = emptyList()): AgentResult {
        val keyword = intentRouter.keywordRoute(text)

        // 关键词明确说"记录…"：聊天 + 同步写日记（不拦截）
        if (keyword is AssistantIntent.RecordDiary) {
            return chatRequested(text, memoryText, history, RecordHint(keyword.bookName))
        }

        // 其他关键词命令（识屏/提醒/监控）：本地执行
        if (keyword != null) return AgentResult.Command(keyword)

        // 关键词未命中 → LLM 分类兜底（是否记录也由 LLM 判断）
        val llmHit = intentRouter.llmClassify(text)
        if (llmHit is AssistantIntent.RecordDiary) {
            return chatRequested(text, memoryText, history, RecordHint(llmHit.bookName))
        }
        if (llmHit != null && llmHit !is AssistantIntent.Chat) return AgentResult.Command(llmHit)

        // 聊天兜底
        return chatRequested(text, memoryText, history, null)
    }

    /**
     * 组装聊天请求（记录类与普通聊天共用）。
     * 是否需要搜索、搜什么词，全部交给主模型在回复中决定（协议见 PromptBuilder 外壳），
     * 这里只负责组装基础消息，不再预判搜索、不再额外消耗一次 LLM 调用。
     */
    private suspend fun chatRequested(
        text: String,
        memoryText: String?,
        history: List<ChatMessage>,
        recordHint: RecordHint?
    ): AgentResult {
        val profile = providerRegistry.profileFor(Capability.CHAT)
            ?: return AgentResult.Error("尚未配置模型提供商，请到「设置」填写")
        if (!profile.isConfigured()) {
            return AgentResult.Error("模型提供商未配置完整，请到「设置」检查")
        }

        val conversation = if (history.isNotEmpty()) history else listOf(ChatMessage("user", text))
        val messages = promptBuilder.buildChatMessages(memoryText, conversation)
        return AgentResult.ChatRequested(messages, recordHint)
    }

    /**
     * 主模型驱动的搜索循环（架构核心）：
     *
     *   流式收集一轮回复
     *     ├─ 整条消息都是 "[搜索] 词" 行 → 执行搜索，结果作为 user 消息接上，再收集一轮
     *     │                                （已达上限则改为注入"请直接回答"指令强制收尾）
     *     └─ 否则视为正式回答，结束
     *
     * 缓冲策略：开头疑似"[搜索"标记时文本暂不上屏（避免内部标记闪现在气泡里），
     * 流结束后统一判定是搜索请求还是正文。异常情况由 guard 上限保证必然终止。
     */
    fun chatReplyFlow(baseMessages: List<ChatMessage>): Flow<ReplyEvent> = flow {
        var messages = baseMessages
        var forcedFinal = false          // 达搜索上限后置 true：下一轮无论输出什么都当作最终回答
        val doneQueries = mutableListOf<String>()
        val exchanges = mutableListOf<Pair<String, String>>() // (模型的搜索请求原文, 发回的结果消息)

        var guard = 0
        while (true) {
            if (++guard > 8) break // 双保险：协议被模型玩坏也不会死循环

            // ---- 一轮流式收集（带开头标记缓冲）----
            var acc = ""
            var released = false
            chatStream(messages).collect { chunk ->
                val delta = chunk.choices.firstOrNull()?.delta
                val t = delta?.textContent.orEmpty()
                val th = delta?.reasoningContent.orEmpty()
                if (t.isNotEmpty()) acc += t
                if (!released && stillBuffering(acc)) {
                    // 缓冲期：文本不上屏，思考过程照常展示
                    if (th.isNotEmpty()) emit(ReplyEvent.Delta("", th))
                } else {
                    released = true
                    emit(ReplyEvent.Delta(t, th))
                }
            }

            // ---- 判定这一轮是搜索请求还是最终回答 ----
            val queries = if (forcedFinal || acc.isBlank()) null else extractSearchRequests(acc)
            if (queries == null) {
                val cleaned = stripSearchLines(acc)
                val footer = if (doneQueries.isEmpty()) ""
                else "\n\n🔍 已联网搜索：" + doneQueries.joinToString("、")
                emit(ReplyEvent.Final(cleaned + footer, doneQueries.toList(), exchanges.toList()))
                return@flow
            }

            if (doneQueries.size >= MAX_SEARCH_ROUNDS) {
                // 次数用完模型还想搜：注入指令强制它基于已有结果作答
                messages = messages +
                    ChatMessage("assistant", acc) +
                    ChatMessage("user", SEARCH_LIMIT_NOTE)
                forcedFinal = true
                continue
            }

            // 执行搜索，结果以 user 消息形式接在对话后（模型下一轮能看到）
            emit(ReplyEvent.Searching(queries))
            val resultsMsg = searchResultsMessage(queries)
            doneQueries.addAll(queries)
            exchanges += acc to resultsMsg
            messages = messages + ChatMessage("assistant", acc) + ChatMessage("user", resultsMsg)
        }

        // guard 兜底出口（正常流程到不了这里）
        emit(ReplyEvent.Final("（搜索轮次异常终止，请重试）", doneQueries.toList(), exchanges.toList()))
    }

    /** 发送流式对话请求（会话尾部由调用方维护，这里只发一次请求） */
    suspend fun chatStream(messages: List<ChatMessage>): Flow<ChatResponse> {
        val profile = providerRegistry.profileFor(Capability.CHAT)
            ?: throw IllegalStateException("未配置对话提供商")
        val api = providerRegistry.apiFor(profile)
        val effort = providerRegistry.reasoningEffortFor(profile)
        val request = ChatRequest(
            model = profile.model,
            messages = messages,
            temperature = 0.7,
            maxTokens = 2048,
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

    /** 执行一组搜索并组装成发回给模型的「[搜索结果]」消息 */
    private suspend fun searchResultsMessage(queries: List<String>): String = buildString {
        append("[搜索结果]")
        for (q in queries) {
            // 单个词搜索失败不阻塞其他词，更不阻塞聊天本身
            val results = try {
                searchClient.search(q, maxResults = 5)
            } catch (_: Exception) {
                emptyList()
            }
            append("\n\n搜索词：").append(q)
            if (results.isEmpty()) {
                append("\n（没有找到相关结果）")
            } else results.forEachIndexed { i, r ->
                append("\n").append(i + 1).append(". ").append(r.title)
                append("\n   来源：").append(r.url)
                append("\n   ").append(r.content.take(300))
            }
        }
        append("\n\n以上是系统自动搜索的结果。若信息足以回答，请直接给出正式回答（不要再出现任何标记）；")
        append("若仍有明显缺口，可再输出一行\"[搜索] 更精确的搜索词\"补充搜索（剩余次数有限，省着用）。")
    }

    companion object {
        /** 单次回复允许的最大搜索轮数 */
        private const val MAX_SEARCH_ROUNDS = 3

        /** 搜索请求标记（协议与 PromptBuilder 外壳里的说明保持一致） */
        private const val MARK = "[搜索]"

        /** 达到搜索上限后的强制收尾指令 */
        private const val SEARCH_LIMIT_NOTE =
            "（已达单次回复的搜索次数上限。请综合以上搜索结果与你自己的知识直接回答用户的问题，不要再输出[搜索]）"

        /** 匹配一行搜索请求：兼容半角/全角方括号、冒号可有可无（如 "[搜索] 词" / "［搜索］：词"） */
        private val SEARCH_LINE =
            Regex("""^[ \t]*[\[［][ \t]*搜[ \t]*索[ \t]*[\]］][ \t]*[:：]?[ \t]*(.+?)[ \t]*$""")

        /** 开头仍在打搜索标记（或只有空白）→ 文本暂缓上屏，等流结束统一判定 */
        internal fun stillBuffering(acc: String): Boolean {
            val t = acc.trimStart()
            if (t.isEmpty()) return true
            return startsAsMark(t, MARK) || startsAsMark(t, "［搜索］")
        }

        private fun startsAsMark(t: String, mark: String): Boolean =
            if (t.length <= mark.length) mark.startsWith(t) else t.startsWith(mark)

        /**
         * 判断整条回复是否为纯搜索请求：每一非空行都是"[搜索] 词"行。
         * 是 → 返回去重后的搜索词列表；否（含空消息、带正文的混合输出）→ null。
         */
        internal fun extractSearchRequests(text: String): List<String>? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val queries = mutableListOf<String>()
            for (line in lines) {
                val m = SEARCH_LINE.matchEntire(line) ?: return null
                queries += m.groupValues[1]
            }
            return queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }
        }

        /** 防御：从最终回答里剥掉混进正文的搜索标记行（模型没守协议时避免内部符号外露） */
        internal fun stripSearchLines(text: String): String =
            text.lines()
                .filterNot { SEARCH_LINE.matchEntire(it.trim()) != null }
                .joinToString("\n")
                .trimEnd()
    }
}
