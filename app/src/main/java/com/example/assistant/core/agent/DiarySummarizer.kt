package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记录整理器：把聊天里要记的内容整理成简洁的日记条目，并顺带判断 0-3 个标签。
 * 标签只从用户自定义词汇表（availableTags）里选；没有合适的就不打（保持未分类）。
 * 用独立短提示词（不污染主对话缓存前缀），后台静默执行。
 * 任何失败（未配置/网络/空内容/解析失败）都回退原文 + 空标签——记录不能丢。
 */
class DiarySummarizer(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 整理结果：summary = 日记正文；tags = 0-3 个用户标签 */
    @Serializable
    data class DiarySummaryResult(
        val summary: String,
        val tags: List<String> = emptyList()
    )

    /**
     * 整理要记录的内容并判断标签；失败时返回原文和空标签。
     * @param availableTags 用户自定义标签词汇表（逗号分隔列表）
     */
    suspend fun summarize(text: String, availableTags: List<String> = emptyList()): DiarySummaryResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext DiarySummaryResult(text, emptyList())
            val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext DiarySummaryResult(text, emptyList())
            if (!profile.isConfigured()) return@withContext DiarySummaryResult(text, emptyList())

            try {
                val api = providerRegistry.apiFor(profile)
                val prompt = promptStore.prompt(PromptStore.PromptKey.DIARY_SUMMARIZE)
                val effort = providerRegistry.reasoningEffortFor(profile)
                val userContent = buildString {
                    append("可用标签：").append(availableTags.joinToString("，")).append("\n\n")
                    append("需要记录的内容：\n").append(text)
                }
                val request = ChatRequest(
                    model = profile.model,
                    messages = listOf(
                        ChatMessage("system", prompt),
                        ChatMessage("user", userContent)
                    ),
                    temperature = 0.3,
                    // 1024：推理模型思考占配额（与意图分类同值）
                    maxTokens = 1024,
                    reasoningEffort = effort
                )
                val header = providerRegistry.authHeader(profile.apiKey)
                val response = providerRegistry.chatCompat(profile, request, header, api)
                val content = response.choices.firstOrNull()?.message?.textContent
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@withContext DiarySummaryResult(text, emptyList())
                parseResult(content, text, availableTags)
            } catch (e: Exception) {
                // 整理失败不打扰用户，回退原文（记录不能丢）
                DiarySummaryResult(text, emptyList())
            }
        }

    private fun parseResult(
        content: String,
        fallback: String,
        availableTags: List<String>
    ): DiarySummaryResult {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return DiarySummaryResult(fallback, emptyList())
        return try {
            val obj = json.decodeFromString<DiarySummaryResult>(content.substring(start, end + 1))
            val summary = obj.summary.trim().ifBlank { fallback }
            // 只保留用户词汇表里的标签；去重；最多 3 个；0-3 均允许
            val tags = obj.tags
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in availableTags }
                .distinct()
                .take(3)
            DiarySummaryResult(summary, tags)
        } catch (e: Exception) {
            DiarySummaryResult(fallback, emptyList())
        }
    }
}
