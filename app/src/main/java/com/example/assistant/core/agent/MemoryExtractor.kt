package com.example.assistant.core.agent

import android.util.Log
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.data.db.entity.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记忆抽取器：从日记/聊天内容中提取值得长期记住的事实。
 * 重要性由 LLM 评分（importance 1-10），低于 [IMPORTANCE_THRESHOLD] 的过滤不存——
 * 防止"今天在和代码搏斗"这类一次性内容进入长期记忆。
 * 用独立短提示词（不污染主对话缓存前缀），后台静默执行，失败不打扰用户。
 */
class MemoryExtractor(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 抽取结果 DTO：模型输出 JSON 数组，每项 {fact, category, importance} */
    @Serializable
    data class ExtractItem(
        val fact: String,
        val category: String = "general",
        /** 重要度评分 1-10（LLM 判断）。模型未返回时默认 5（低于阈值 → 不存，保守） */
        val importance: Int = 5
    )

    /** 从模型输出中提取事实数组（容忍 ```json 围栏与前后废话文本） */
    private fun extractItems(content: String): List<ExtractItem> {
        val t = content.trim()
        val start = t.indexOf('[')
        val end = t.lastIndexOf(']')
        val arrayText = if (start >= 0 && end > start) t.substring(start, end + 1) else t
        return try {
            json.decodeFromString<List<ExtractItem>>(arrayText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 从文本中抽取长期记忆（importance ≥ 7 才存）。
     * 任何失败（未配置/网络/解析）都返回空列表，不抛异常。
     */
    suspend fun extract(text: String): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext emptyList()
        if (!profile.isConfigured()) return@withContext emptyList()

        try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.MEMORY_EXTRACT)
            val (thinking, effort) = providerRegistry.thinkingParamsFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                temperature = 0.0,
                // 1024：推理模型思考占配额，300 在长文本时可能被吃光
                maxTokens = 1024,
                thinking = thinking,
                reasoningEffort = effort
            )
            val header = providerRegistry.authHeader(profile.apiKey)
            val response = providerRegistry.chatCompat(profile, request, header, api)
            val content = response.choices.firstOrNull()?.message?.textContent
                ?: return@withContext emptyList()

            // 模型可能返回数组 JSON 或带围栏/前后废话，取数组子串解析
            val items = extractItems(content)
            val filtered = items.filter { it.fact.isNotBlank() && it.importance >= IMPORTANCE_THRESHOLD }
            // 调试日志：看模型评分与过滤结果（不含密钥），便于按使用体验调整阈值/提示词
            Log.i(TAG, "抽取 ${items.size} 条，过滤后 ${filtered.size} 条：$items")
            filtered.map { MemoryEntity(fact = it.fact, category = it.category.ifBlank { "general" }) }
        } catch (e: Exception) {
            // 抽取失败不打扰用户，仅记录日志
            Log.w(TAG, "记忆抽取失败", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "MemoryExtractor"
        /** 记忆重要性阈值：LLM 评分 ≥ 7 才存入长期记忆（后续可按使用体验调整） */
        private const val IMPORTANCE_THRESHOLD = 7
    }
}
