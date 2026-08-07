package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.SearchResult
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ResponseFormat
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.data.db.entity.MonitoredEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 事件命中判断器：把搜索结果交给 LLM 判断是否命中关注事件。
 * 有 conditionKeywords 时优先本地关键词判断（免调用），无关键词或想更准时用 LLM。
 */
class EventHitJudge(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    data class HitResult(val hit: Boolean, val reason: String)

    /** 本地关键词判断（conditionKeywords 逗号分隔，任一命中即算） */
    fun keywordHit(event: MonitoredEventEntity, results: List<SearchResult>): Boolean {
        val keywords = event.conditionKeywords.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (keywords.isEmpty()) return false
        return results.any { r ->
            keywords.any { kw -> r.title.contains(kw) || r.content.contains(kw) }
        }
    }

    /** LLM 判断；失败返回 null（调用方按未命中处理） */
    suspend fun judge(event: MonitoredEventEntity, results: List<SearchResult>): HitResult? =
        withContext(Dispatchers.IO) {
            if (results.isEmpty()) return@withContext HitResult(false, "无搜索结果")
            val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext null
            if (!profile.isConfigured()) return@withContext null

            try {
                val api = providerRegistry.apiFor(profile)
                var prompt = promptStore.prompt(PromptStore.PromptKey.EVENT_HIT)
                // 提示词模板：替换事件与结果占位（自定义规则附加在事件描述后）
                val eventText = buildString {
                    append("「").append(event.displayName).append("」（搜索词：").append(event.searchQuery).append("）")
                    if (event.conditionKeywords.isNotBlank()) {
                        append("，条件关键词：").append(event.conditionKeywords)
                    }
                    if (event.customRule.isNotBlank()) {
                        append("\n用户附加规则（必须严格遵守）：").append(event.customRule)
                    }
                }
                val resultsText = results.joinToString("\n") { r ->
                    "${r.title}（${r.url}）\n${r.content.take(200)}"
                }
                prompt = prompt.replace("{event}", eventText).replace("{results}", resultsText)

                val effort = providerRegistry.reasoningEffortFor(profile)
                val request = ChatRequest(
                    model = profile.model,
                    messages = listOf(
                        ChatMessage("system", prompt),
                        ChatMessage("user", "请判断是否命中。")
                    ),
                    temperature = 0.0,
                    maxTokens = 1024,
                    responseFormat = ResponseFormat("json_object"),
                    reasoningEffort = effort
                )
                val header = providerRegistry.authHeader(profile.apiKey)
                val response = providerRegistry.chatCompat(profile, request, header, api)
                val content = response.choices.firstOrNull()?.message?.textContent
                    ?: return@withContext null
                val obj = JsonExtract.objectOf(content) ?: return@withContext null
                HitResult(
                    hit = JsonExtract.bool(obj, "hit"),
                    reason = JsonExtract.str(obj, "reason").orEmpty()
                )
            } catch (e: Exception) {
                null
            }
        }
}
