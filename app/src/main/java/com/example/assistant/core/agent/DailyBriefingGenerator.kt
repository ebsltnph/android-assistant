package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SummaryStore
import com.example.assistant.data.repo.ReminderRepository
import com.example.assistant.data.repo.SummaryRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 清晨简报生成器：今日待触发提醒 + 最近一份每日小结 → LLM 组装成简报文本。
 * 由 MorningBriefingWorker（默认 7:30）调用。生成后存入 SummaryStore（App 内随时可看）。
 * LLM 不可用时返回纯模板兜底。
 */
class DailyBriefingGenerator(
    private val reminderRepository: ReminderRepository,
    private val summaryRepository: SummaryRepository,
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore,
    private val summaryStore: SummaryStore
) {

    /**
     * 生成简报文本。
     * - 成功 → [GenerationOutcome.Ok]
     * - 失败 → [GenerationOutcome.Failed]（**兜底模板照旧落库 + 通知，并在文本里写明原因**；
     *   Worker 会额外说明 15 分钟后重试一次）
     */
    suspend fun generate(): GenerationOutcome {
        val now = System.currentTimeMillis()
        val todayStart = dayStartMillis()
        // 今天 0 点后待触发的提醒
        val todayReminders = reminderRepository.pending(now)
            .filter { it.triggerAtEpochMillis >= todayStart && it.triggerAtEpochMillis < todayStart + 24 * 3600_000L }
        // 昨日小结（严格取昨天；昨天没生成小结就不引用，避免拿几天前的旧数据）
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val yesterdayDate = "%04d-%02d-%02d".format(
            yesterday.get(Calendar.YEAR), yesterday.get(Calendar.MONTH) + 1, yesterday.get(Calendar.DAY_OF_MONTH)
        )
        val summary = summaryRepository.byDate(yesterdayDate)

        val remindersText = if (todayReminders.isEmpty()) {
            "今天没有预设提醒"
        } else {
            todayReminders.joinToString("\n") {
                "· ${timeText(it.triggerAtEpochMillis)} ${it.title}"
            }
        }
        val summaryText = summary?.summary ?: "（无昨日小结）"

        val profile = providerRegistry.profileFor(Capability.CHAT)
        // 日期由代码生成（不依赖 LLM 知识截止，它曾把 8月1日说成 7月31日）
        val dateLabel = todayLabel()
        val template = buildString {
            append("🌅 早上好！\n")
            if (todayReminders.isNotEmpty()) {
                append("今日提醒：\n$remindersText\n")
            }
            summary?.let { append("昨日小结：${it.summary.take(200)}") }
        }

        var failedReason: String? = null
        var retryable = true
        val result = when {
            profile == null || !profile.isConfigured() -> {
                failedReason = "未配置对话模型"
                retryable = false
                template + "\n\n（未配置对话模型，本次只列出提醒与昨日小结；到「设置」配置后会自动生成）"
            }
            else -> try {
                val api = providerRegistry.apiFor(profile)
                var prompt = promptStore.prompt(PromptStore.PromptKey.BRIEFING)
                prompt = prompt.replace("{reminders}", remindersText).replace("{summary}", summaryText)
                val effort = providerRegistry.reasoningEffortFor(profile)
                val request = ChatRequest(
                    model = profile.model,
                    messages = listOf(
                        ChatMessage("system", prompt),
                        // 当前时间告诉模型（"今天/昨天"的基准），避免它按知识截止猜日期
                        ChatMessage("user", "当前时间：$dateLabel ${weekdayText()}\n请生成今天的清晨简报。")
                    ),
                    temperature = 0.7,
                    // 1024：推理模型思考占配额
                    maxTokens = 1024,
                    reasoningEffort = effort
                )
                val header = providerRegistry.authHeader(profile.apiKey)
                val response = providerRegistry.chatCompat(profile, request, header, api)
                response.choices.firstOrNull()?.message?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                    ?: run {
                        failedReason = "模型返回了空内容"
                        template + "\n\n（简报未由模型润色：模型返回了空内容）"
                    }
            } catch (e: Exception) {
                failedReason = e.message ?: e.javaClass.simpleName
                template + "\n\n（简报未由模型润色，生成失败：${e.message}）"
            }
        }
        // 落库：最新一份简报（App 内随时可看，首页入口）
        val today = Calendar.getInstance()
        val date = "%04d-%02d-%02d".format(
            today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)
        )
        val dated = dated(dateLabel, result)
        summaryStore.saveBriefing(dated, date)
        return failedReason?.let { GenerationOutcome.Failed(it, dated, retryable) }
            ?: GenerationOutcome.Ok(dated)
    }

    /** 简报文本前缀日期标签（如「🌅 2026年8月1日 清晨简报」） */
    private fun dated(dateLabel: String, text: String): String =
        "🌅 $dateLabel 清晨简报\n\n$text"

    private fun todayLabel(): String {
        val cal = Calendar.getInstance()
        return "%d年%d月%d日".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    private fun weekdayText(): String =
        when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "星期一"
            Calendar.TUESDAY -> "星期二"
            Calendar.WEDNESDAY -> "星期三"
            Calendar.THURSDAY -> "星期四"
            Calendar.FRIDAY -> "星期五"
            Calendar.SATURDAY -> "星期六"
            else -> "星期日"
        }

    private fun dayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    private fun timeText(millis: Long): String = timeFormat.format(Date(millis))
}
