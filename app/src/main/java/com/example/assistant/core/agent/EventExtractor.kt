package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ResponseFormat
import com.example.assistant.core.storage.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 事件监控抽取器：把聊天里"关注 XX"的需求解析成搜索配置
 * （displayName / searchQuery / conditionKeywords）。
 */
class EventExtractor(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    data class ExtractResult(
        val displayName: String,
        val searchQuery: String,
        val conditionKeywords: String,
        /** 自定义判断规则（用户额外要求的判断标准） */
        val customRule: String,
        /** 限定来源域名（逗号分隔） */
        val includeDomains: String
    )

    /**
     * 从文本中本地提取域名：
     * - 带协议："https://api-docs.deepseek.com/zh-cn/updates" → api-docs.deepseek.com
     * - 不带协议："api-docs.deepseek.com/zh-cn/updates" → api-docs.deepseek.com
     */
    private fun extractDomainsLocally(text: String): String {
        // 先匹配带协议的，再匹配裸域名（字母开头、含点、后跟路径或结束）
        val withProtocol = Regex("https?://([a-zA-Z0-9.-]+)")
        val bare = Regex("(?:^|\\s)([a-zA-Z0-9][a-zA-Z0-9-]*\\.[a-zA-Z]{2,}(?:/[^\\s]*)?)")
        val domains = mutableListOf<String>()
        withProtocol.findAll(text).forEach { m ->
            domains.add(m.groupValues[1].removePrefix("www."))
        }
        if (domains.isEmpty()) {
            bare.findAll(text).forEach { m ->
                domains.add(m.groupValues[1].removePrefix("www.").substringBefore("/"))
            }
        }
        return domains.distinct().joinToString(",")
    }

    /** 抽取监控配置；失败返回 null（调用方让用户重说） */
    suspend fun extract(text: String): ExtractResult? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext null
        if (!profile.isConfigured()) return@withContext null

        try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.MONITOR_EXTRACT)
            val (thinking, effort) = providerRegistry.thinkingParams()
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                temperature = 0.0,
                maxTokens = 1024,
                responseFormat = ResponseFormat("json_object"),
                thinking = thinking,
                reasoningEffort = effort
            )
            val response = api.chat(providerRegistry.authHeader(profile.apiKey), request)
            val content = response.choices.firstOrNull()?.message?.textContent
                ?: return@withContext null
            val obj = JsonExtract.objectOf(content) ?: return@withContext null
            val name = JsonExtract.str(obj, "displayName") ?: return@withContext null
            val query = JsonExtract.str(obj, "searchQuery") ?: return@withContext null
            // 限定域名：LLM 没提取出来时，用本地正则从原始消息里提取（不依赖模型）
            var domains = JsonExtract.str(obj, "includeDomains").orEmpty()
            if (domains.isBlank()) domains = extractDomainsLocally(text)
            ExtractResult(
                displayName = name,
                searchQuery = query,
                conditionKeywords = JsonExtract.str(obj, "conditionKeywords").orEmpty(),
                customRule = JsonExtract.str(obj, "customRule").orEmpty(),
                includeDomains = domains
            )
        } catch (e: Exception) {
            null
        }
    }
}
