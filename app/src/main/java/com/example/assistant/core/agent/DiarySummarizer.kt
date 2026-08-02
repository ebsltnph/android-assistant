package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 记录整理器：把聊天里要记的内容整理成简洁的日记条目。
 * 用独立短提示词（不污染主对话缓存前缀），后台静默执行。
 * 任何失败（未配置/网络/空内容）都回退原文——记录不能丢。
 */
class DiarySummarizer(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    /**
     * 整理要记录的内容；失败时返回原文。
     */
    suspend fun summarize(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext text
        if (!profile.isConfigured()) return@withContext text

        try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.DIARY_SUMMARIZE)
            val (thinking, effort) = providerRegistry.thinkingParamsFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                temperature = 0.3,
                // 1024：推理模型思考占配额（与意图分类同值）
                maxTokens = 1024,
                thinking = thinking,
                reasoningEffort = effort
            )
            val response = api.chat(providerRegistry.authHeader(profile.apiKey), request)
            response.choices.firstOrNull()?.message?.textContent
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: text
        } catch (e: Exception) {
            // 整理失败不打扰用户，回退原文（记录不能丢）
            text
        }
    }
}
