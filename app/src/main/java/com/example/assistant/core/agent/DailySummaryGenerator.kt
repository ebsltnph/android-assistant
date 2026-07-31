package com.example.assistant.core.agent

import android.util.Log
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.repo.DiaryRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日小结生成器：汇总当天日记 → LLM 整理成小结文本。
 * 由 DailySummaryWorker（21:00 自动）与日记页「今日小结」按钮共用。
 */
class DailySummaryGenerator(
    private val diaryRepository: DiaryRepository,
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    /**
     * 生成今日小结。当天无日记返回 null（调用方静默跳过）。
     * LLM 不可用时返回简短的兜底统计文本。
     */
    suspend fun generateToday(): String? {
        val entries = diaryRepository.entriesBetween(dayStartMillis(), System.currentTimeMillis())
        if (entries.isEmpty()) return null

        val profile = providerRegistry.profileFor(Capability.CHAT)
        val fallback = "今天写了 ${entries.size} 条日记，点开「日记」页看看吧"
        if (profile == null || !profile.isConfigured()) return fallback

        return try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.DAILY_SUMMARY)
            val diaryText = entries.joinToString("\n") { "· ${entryTime(it.createdAtEpochMillis)} ${it.content}" }
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", diaryText)
                ),
                temperature = 0.5,
                maxTokens = 800
            )
            val response = api.chat(providerRegistry.authHeader(profile.apiKey), request)
            response.choices.firstOrNull()?.message?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        } catch (e: Exception) {
            Log.w(TAG, "每日小结 LLM 调用失败", e)
            fallback
        }
    }

    private fun dayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val entryTimeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    private fun entryTime(millis: Long): String = entryTimeFormat.format(Date(millis))

    companion object {
        private const val TAG = "DailySummaryGenerator"
    }
}
