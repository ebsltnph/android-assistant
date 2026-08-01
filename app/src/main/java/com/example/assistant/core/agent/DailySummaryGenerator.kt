package com.example.assistant.core.agent

import android.content.Context
import android.util.Log
import com.example.assistant.core.calendar.CalendarWriter
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SummaryStore
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.SummaryRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日小结生成器：汇总当天日记 → LLM 整理成小结文本。
 * 由 DailySummaryWorker（定时自动）与日记页「今日小结」按钮共用。
 * 生成成功后：
 * 1. 写入 SummaryStore（通知点击快速读取）
 * 2. 写入 Room 历史表（每天一条，重复生成覆盖）
 * 3. 镜像到系统日历（CalendarProvider，日历 App 可查看）
 */
class DailySummaryGenerator(
    private val diaryRepository: DiaryRepository,
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore,
    private val summaryStore: SummaryStore,
    private val summaryRepository: SummaryRepository,
    private val appContext: Context
) {

    /**
     * 生成今日小结。窗口内无日记返回 null（调用方静默跳过）。
     * LLM 不可用时返回简短的兜底统计文本。
     */
    suspend fun generateToday(): String? {
        // 24 小时滑动窗口：取「总结时刻往前 24h」的日记。
        // 不用「当天 0 点起」——总结时间设为 0 点时，当天 0 点到 0 点之间没有数据，会漏掉昨天深夜的日记。
        val now = System.currentTimeMillis()
        val entries = diaryRepository.entriesBetween(now - SUMMARY_WINDOW_MS, now)
        if (entries.isEmpty()) return null

        val profile = providerRegistry.profileFor(Capability.CHAT)
        val fallback = "今天写了 ${entries.size} 条日记，点开「日记」页看看吧"
        // 兜底文本附上失败原因：MagicOS 屏蔽 App 日志（persist.log.tag=M），
        // 把错误直接显示给用户，比抓 logcat 更可靠
        val result = if (profile == null || !profile.isConfigured()) {
            fallback + "\n（未配置对话模型，请到「设置」添加）"
        } else {
            try {
                val api = providerRegistry.apiFor(profile)
                val prompt = promptStore.prompt(PromptStore.PromptKey.DAILY_SUMMARY)
                val diaryText = entries.joinToString("\n") { "· ${entryTime(it.createdAtEpochMillis)} ${it.content}" }
                val (thinking, effort) = providerRegistry.thinkingParams()
                val request = ChatRequest(
                    model = profile.model,
                    messages = listOf(
                        ChatMessage("system", prompt),
                        ChatMessage("user", diaryText)
                    ),
                    temperature = 0.5,
                    // 4096：推理模型（如 deepseek-v4-flash）的思考过程占 max_tokens 配额，
                    // 800 会被思考吃光导致输出为空（finish=length），调大留出输出空间
                    maxTokens = 4096,
                    thinking = thinking,
                    reasoningEffort = effort
                )
                val response = api.chat(providerRegistry.authHeader(profile.apiKey), request)
                response.choices.firstOrNull()?.message?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                    ?: fallback + "\n（模型返回了空内容，请重试）"
            } catch (e: Exception) {
                Log.w(TAG, "每日小结 LLM 调用失败", e)
                fallback + "\n（生成失败：${e.message}）"
            }
        }

        // 落库（最新一份，通知点击读取）
        val date = todayString()
        val datedResult = "${todayLabel()}\n\n$result"
        summaryStore.save(datedResult, date)
        // 历史表（每天一条）
        summaryRepository.saveToday(datedResult, date)
        // 镜像到系统日历（当天事件，同天只保留最后一条）
        CalendarWriter.writeDailySummary(appContext, date, datedResult)
        return datedResult
    }

    private fun todayString(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** 小结正文开头的日期标签，如「2026年7月31日」 */
    private fun todayLabel(): String {
        val cal = Calendar.getInstance()
        return "%d年%d月%d日".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    private val entryTimeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    private fun entryTime(millis: Long): String = entryTimeFormat.format(Date(millis))

    companion object {
        private const val TAG = "DailySummaryGenerator"
        /** 汇总窗口：24 小时（滑动窗口，见 generateToday 注释） */
        private const val SUMMARY_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
