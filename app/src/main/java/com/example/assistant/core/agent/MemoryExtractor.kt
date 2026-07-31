package com.example.assistant.core.agent

import android.util.Log
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ResponseFormat
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.data.db.entity.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记忆抽取器：从日记/聊天内容中提取值得长期记住的事实。
 * 用独立短提示词（不污染主对话缓存前缀），后台静默执行，失败不打扰用户。
 */
class MemoryExtractor(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 抽取结果 DTO：模型输出 JSON 数组，每项 {fact, category} */
    @Serializable
    data class ExtractItem(val fact: String, val category: String = "general")

    /**
     * 从文本中抽取长期记忆。任何失败（未配置/网络/解析）都返回空列表，不抛异常。
     */
    suspend fun extract(text: String): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext emptyList()
        if (!profile.isConfigured()) return@withContext emptyList()

        try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.MEMORY_EXTRACT)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                temperature = 0.0,
                maxTokens = 300,
                responseFormat = ResponseFormat("json_object")
            )
            val response = api.chat(providerRegistry.authHeader(profile.apiKey), request)
            val content = response.choices.firstOrNull()?.message?.textContent
                ?: return@withContext emptyList()

            // 模型可能返回带 ```json 围栏的内容，剥掉再解析
            val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val items = json.decodeFromString<List<ExtractItem>>(cleaned)
            items.filter { it.fact.isNotBlank() }
                .map { MemoryEntity(fact = it.fact, category = it.category.ifBlank { "general" }) }
        } catch (e: Exception) {
            // 抽取失败不打扰用户，仅记录日志
            Log.w(TAG, "记忆抽取失败", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "MemoryExtractor"
    }
}
