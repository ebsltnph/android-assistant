package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜索判断器：每条对话消息由 LLM 判断是否需要联网搜索（全 LLM 判断模式）。
 * 任何失败都视为"不搜索"（不阻塞聊天）。
 * 不用 response_format=json_object（v4 flash 支持不稳定），见 JsonExtract 说明。
 */
class SearchJudger(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    data class JudgeResult(
        val needSearch: Boolean,
        val query: String,
        val reason: String
    )

    /** 判断是否需要搜索；失败返回 null（调用方按不搜索处理） */
    suspend fun judge(text: String): JudgeResult? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext null
        if (!profile.isConfigured()) return@withContext null

        try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.SEARCH_JUDGE)
            val (thinking, effort) = providerRegistry.thinkingParamsFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                temperature = 0.0,
                // 1024：推理模型思考占配额（512 曾被吃光返回空内容）
                maxTokens = 1024,
                thinking = thinking,
                reasoningEffort = effort
            )
            val header = providerRegistry.authHeader(profile.apiKey)
            val response = providerRegistry.chatCompat(profile, request, header, api)
            val content = response.choices.firstOrNull()?.message?.textContent
                ?: return@withContext null
            val obj = JsonExtract.objectOf(content) ?: return@withContext null
            val query = JsonExtract.str(obj, "query").orEmpty()
            val reason = JsonExtract.str(obj, "reason").orEmpty()
            val need = JsonExtract.bool(obj, "need_search")
            // 启发式兜底：模型给出非空搜索词说明它实际认为需要搜索
            // （v4 flash 曾输出"需要搜索"的理由却带 need_search=false，自相矛盾时信任 query）
            val effectiveNeed = need || query.isNotBlank()
            if (effectiveNeed && query.isBlank()) null else JudgeResult(effectiveNeed, query, reason)
        } catch (e: Exception) {
            // 判断失败不阻塞聊天，按不搜索处理
            null
        }
    }
}
