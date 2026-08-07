package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ChatStream
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.SearchClient
import com.example.assistant.core.network.SearchResult
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
 * 5. 对话搜索（全 LLM 判断）：每条消息先由 LLM 判断是否需要联网搜索，
 *    需要则搜索并注入上下文（extraContext），LLM 基于结果回答
 */
class Agent(
    private val providerRegistry: ProviderRegistry,
    private val promptBuilder: PromptBuilder,
    private val intentRouter: IntentRouter,
    private val searchJudger: SearchJudger,
    private val searchClient: SearchClient
) {

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

    /** 组装聊天请求（记录类与普通聊天共用） */
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

        // 对话搜索（全 LLM 判断）：需要搜索则搜索并注入上下文；搜索失败不阻塞聊天
        val extraContext = searchExtraContext(text)
        val conversation = if (history.isNotEmpty()) history else listOf(ChatMessage("user", text))
        val messages = promptBuilder.buildChatMessages(memoryText, conversation, extraContext)
        return AgentResult.ChatRequested(messages, recordHint)
    }

    /**
     * 判断是否需要联网搜索并组装搜索结果上下文。
     * 返回 null = 无需搜索或搜索失败（正常聊天即可，不打扰用户）。
     */
    private suspend fun searchExtraContext(text: String): String? {
        val judge = searchJudger.judge(text) ?: return null
        if (!judge.needSearch || judge.query.isBlank()) return null
        return try {
            val results = searchClient.search(judge.query, maxResults = 5)
            if (results.isEmpty()) null else buildSearchContext(judge.query, results)
        } catch (e: Exception) {
            null // 搜索失败不阻塞聊天
        }
    }

    /** 搜索结果 → 注入消息[2] 当前上下文的文本（易变，不破坏缓存前缀） */
    private fun buildSearchContext(query: String, results: List<SearchResult>): String = buildString {
        append("以下是关于「").append(query).append("」的网络搜索结果，请基于它们回答，必要时注明来源：\n")
        results.forEachIndexed { i, r ->
            append(i + 1).append(". ").append(r.title).append("\n")
            append("   ").append(r.url).append("\n")
            append("   ").append(r.content.take(300)).append("\n")
        }
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
}
