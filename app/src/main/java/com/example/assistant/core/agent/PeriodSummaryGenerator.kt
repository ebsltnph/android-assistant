package com.example.assistant.core.agent

import android.util.Log
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.repo.SummaryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 期间日记总结生成器：把用户指定时间段内的日记汇总成一份总结。
 * 由日记页「期间总结」按钮手动触发（选择起止日期）。
 * 生成成功后落库（最近 5 条，供之后重新查看/复制/导出）；
 * 不镜像系统日历。
 */
class PeriodSummaryGenerator(
    private val diaryRepository: DiaryRepository,
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore,
    private val summaryRepository: SummaryRepository
) {

    /**
     * 生成 [fromMillis, toMillis) 区间内的日记总结。区间内无日记返回 null。
     * LLM 不可用时返回简短的兜底统计文本（附失败原因，MagicOS 屏蔽 App 日志，
     * 直接显示给用户比抓 logcat 更可靠）。
     */
    suspend fun generate(fromMillis: Long, toMillis: Long): String? {
        val entries = diaryRepository.entriesBetween(fromMillis, toMillis)
        if (entries.isEmpty()) return null

        // 区间标签：toMillis 是「结束日期次日 0 点」的开区间，减 1ms 得到结束日期当天
        val rangeLabel = "${rangeDateFormat.format(Date(fromMillis))} ～ ${rangeDateFormat.format(Date(toMillis - 1))}"
        val profile = providerRegistry.profileFor(Capability.CHAT)
        val fallback = "这段期间共写了 ${entries.size} 条日记，点开「日记」页看看吧"
        val result = if (profile == null || !profile.isConfigured()) {
            fallback + "\n（未配置对话模型，请到「设置」添加）"
        } else {
            try {
                val api = providerRegistry.apiFor(profile)
                val prompt = promptStore.prompt(PromptStore.PromptKey.PERIOD_SUMMARY)
                val diaryText = entries.joinToString("\n") { "· ${entryDateTimeFormat.format(Date(it.createdAtEpochMillis))} ${it.content}" }
                val effort = providerRegistry.reasoningEffortFor(profile)
                val request = ChatRequest(
                    model = profile.model,
                    messages = listOf(
                        ChatMessage("system", prompt),
                        ChatMessage("user", "汇总区间：$rangeLabel\n\n$diaryText")
                    ),
                    temperature = 0.5,
                    // 16384：期间总结跨多天、需覆盖的条目更多，输出比每日小结长；
                    // 推理模型（如 deepseek-v4-flash）的思考过程占 max_tokens 配额，
                    // 配额不足会 finish_reason=length 且 content 为空（思考吃光配额）
                    maxTokens = 16384,
                    reasoningEffort = effort
                )
                val header = providerRegistry.authHeader(profile.apiKey)
                val response = providerRegistry.chatCompat(profile, request, header, api)
                val choice = response.choices.firstOrNull()
                val text = choice?.message?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                if (text != null) {
                    text
                } else {
                    // 附上结束原因方便定位：length=配额被思考吃光；stop=正常结束但内容为空
                    fallback + "\n（模型返回了空内容，结束原因：${choice?.finishReason ?: "无"}，请重试）"
                }
            } catch (e: Exception) {
                Log.w(TAG, "期间总结 LLM 调用失败", e)
                fallback + "\n（生成失败：${e.message}）"
            }
        }

        val fullText = "📅 $rangeLabel\n\n$result"
        // 落库（最近 5 条自动清理），供之后重新查看/复制/导出
        summaryRepository.savePeriodSummary(fromMillis, toMillis, fullText)
        return fullText
    }

    private val entryDateTimeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
    private val rangeDateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

    companion object {
        private const val TAG = "PeriodSummaryGenerator"
    }
}
