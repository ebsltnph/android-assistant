package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ChatStream
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Agent 编排器：
 * 1. 意图路由（关键词 → LLM 分类 → 聊天兜底）
 * 2. 命令类意图（日记/提醒/识屏/监控）由上层分发到对应处理器（P3 起逐步接入）
 * 3. 对话类意图走 LLM 流式回复（缓存友好的消息结构，见 PromptBuilder）
 *
 * 当前阶段（P2）只打通对话链路。
 */
class Agent(
    private val providerRegistry: ProviderRegistry,
    private val promptBuilder: PromptBuilder,
    private val intentRouter: IntentRouter
) {

    sealed interface AgentResult {
        /** 需要走 LLM 对话（流式） */
        data class ChatRequested(val messages: List<ChatMessage>) : AgentResult

        /** 命令类意图（本地执行，由上层处理） */
        data class Command(val intent: AssistantIntent) : AgentResult

        /** 未配置模型等无法处理的情况 */
        data class Error(val message: String) : AgentResult
    }

    /** 路由用户消息，返回执行结果 */
    suspend fun route(text: String, memoryText: String? = null): AgentResult {
        intentRouter.keywordRoute(text)?.let { return AgentResult.Command(it) }
        val llmHit = intentRouter.llmClassify(text)
        if (llmHit != null && llmHit !is AssistantIntent.Chat) return AgentResult.Command(llmHit)

        val profile = providerRegistry.profileFor(Capability.CHAT)
            ?: return AgentResult.Error("尚未配置模型提供商，请到「设置」填写")
        if (!profile.isConfigured()) {
            return AgentResult.Error("模型提供商未配置完整，请到「设置」检查")
        }

        val messages = promptBuilder.buildChatMessages(memoryText, listOf(ChatMessage("user", text)))
        return AgentResult.ChatRequested(messages)
    }

    /** 发送流式对话请求（会话尾部由调用方维护，这里只发一次请求） */
    suspend fun chatStream(messages: List<ChatMessage>): Flow<ChatResponse> {
        val profile = providerRegistry.profileFor(Capability.CHAT)
            ?: throw IllegalStateException("未配置对话提供商")
        val api = providerRegistry.apiFor(profile)
        val request = ChatRequest(
            model = profile.model,
            messages = messages,
            temperature = 0.7,
            maxTokens = 2048,
            stream = true
        )
        return flow {
            val response = api.chatStream(providerRegistry.authHeader(profile.apiKey), request)
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code()}：${response.errorBody()?.string()}")
            }
            emitAll(ChatStream.parse(response.body()!!))
        }
    }

    /** 测试连接：发一个最小请求验证配置是否正确 */
    suspend fun testConnection(): Result<String> {
        val profile = providerRegistry.profileFor(Capability.CHAT)
            ?: return Result.failure(IllegalStateException("未配置提供商"))
        return try {
            val api = providerRegistry.apiFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(ChatMessage("user", "你好，请回复\"连接成功\"四个字")),
                maxTokens = 20
            )
            val response = api.chat(providerRegistry.authHeader(profile.apiKey), request)
            val reply = response.choices.firstOrNull()?.message?.textContent
                ?: return Result.failure(IllegalStateException("响应中没有内容"))
            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
