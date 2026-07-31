package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ResponseFormat
import com.example.assistant.core.storage.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 意图路由：关键词快速路径（免网络、省电）→ LLM 分类兜底 → 聊天兜底。
 */
class IntentRouter(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 关键词路由（纯本地，不联网）。返回 null 表示未命中，需要 LLM 或聊天兜底 */
    fun keywordRoute(text: String): AssistantIntent? {
        val t = text.trim()
        if (t.isEmpty()) return null

        // 识屏：优先级最高（"翻译"等词也可能出现在聊天里，但"识屏/截屏/屏幕"更明确）
        if (t.containsAny(KEYWORDS_SCREEN)) {
            val action = when {
                t.containsAny(listOf("翻译")) -> ScreenAction.TRANSLATE
                t.containsAny(listOf("对话", "分析", "看看", "描述")) -> ScreenAction.FULL_ANALYSIS
                else -> ScreenAction.EXTRACT_TEXT
            }
            return AssistantIntent.ScreenSense(action)
        }

        // 记录（日记）
        if (t.containsAny(KEYWORDS_DIARY)) {
            val book = when {
                t.containsAny(listOf("工作", "上班", "开会")) -> "工作"
                t.containsAny(listOf("生活", "日常")) -> "生活"
                else -> null
            }
            val content = stripKeywords(t, KEYWORDS_DIARY)
            return AssistantIntent.RecordDiary(content.ifBlank { t }, book)
        }

        // 提醒
        if (t.containsAny(KEYWORDS_REMINDER)) {
            return AssistantIntent.SetReminder(t, t)
        }

        // 事件监控
        if (t.containsAny(KEYWORDS_MONITOR)) {
            return AssistantIntent.MonitorEvent(stripKeywords(t, KEYWORDS_MONITOR))
        }

        return null
    }

    /** LLM 分类兜底：关键词未命中且已配置模型时，用短提示词分类 */
    suspend fun llmClassify(text: String): AssistantIntent? = withContext(Dispatchers.IO) {
        val profile = providerRegistry.profileFor(Capability.CLASSIFY) ?: return@withContext null
        if (!profile.isConfigured()) return@withContext null

        try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.INTENT_CLASSIFIER)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                temperature = 0.0,
                maxTokens = 100,
                responseFormat = ResponseFormat("json_object")
            )
            val response = api.chat(providerRegistry.authHeader(profile.apiKey), request)
            val content = response.choices.firstOrNull()?.message?.textContent ?: return@withContext null
            val obj = json.parseToJsonElement(content).jsonObject
            when (obj["intent"]?.jsonPrimitive?.content) {
                "record_diary" -> AssistantIntent.RecordDiary(text)
                "set_reminder" -> AssistantIntent.SetReminder(text, text)
                "screen_sense" -> AssistantIntent.ScreenSense(ScreenAction.EXTRACT_TEXT)
                "monitor_event" -> AssistantIntent.MonitorEvent(text)
                else -> null
            }
        } catch (e: Exception) {
            // 分类失败不阻塞用户，交给聊天兜底
            null
        }
    }

    private fun String.containsAny(keywords: List<String>): Boolean =
        keywords.any { this.contains(it) }

    /** 去掉触发关键词，留下实质内容 */
    private fun stripKeywords(text: String, keywords: List<String>): String {
        var result = text.trim()
        for (kw in keywords) {
            result = result.replace(kw, "").trim()
        }
        return result
    }

    companion object {
        private val KEYWORDS_DIARY = listOf("记录", "记一下", "写日记", "记到", "帮我记")
        private val KEYWORDS_REMINDER = listOf("提醒", "闹钟", "到点", "待办")
        private val KEYWORDS_SCREEN = listOf("识屏", "截屏", "截个屏", "这个屏幕", "翻译这个")
        private val KEYWORDS_MONITOR = listOf("搜索", "查一下", "帮我留意", "关注", "新闻", "盯一下")
    }
}
