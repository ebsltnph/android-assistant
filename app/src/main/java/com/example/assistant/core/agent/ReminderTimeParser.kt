package com.example.assistant.core.agent

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ResponseFormat
import com.example.assistant.core.storage.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 提醒时间解析器：LLM 把自然语言时间解析成"结构化描述"（dayOffset/hour/minute 等），
 * **绝对时间戳由本地代码计算**——模型日期算术不可靠（曾把"2分钟后"算成 7月3日），
 * 结构描述 + Calendar 计算才稳。失败返回 null（调用方让用户补充）。
 */
class ReminderTimeParser(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    /** 结构化时间描述（模型输出）；triggerAt 由 [resolveTrigger] 本地计算 */
    data class ParseResult(
        val title: String,
        /** 相对今天的偏移（0=今天 1=明天 2=后天） */
        val dayOffset: Int = 0,
        /** 24 小时制 */
        val hour: Int = 0,
        val minute: Int = 0,
        /** "X分钟后"用；非空时忽略 dayOffset/hour/minute */
        val offsetMinutes: Int? = null,
        /** null | "daily" | "weekly" */
        val repeat: String? = null,
        /** weekly 时 1-7（周一=1），其他情况 0 */
        val weekday: Int = 0
    )

    /** 解析提醒需求（结构化描述）；失败返回 null */
    suspend fun parse(text: String): ParseResult? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val profile = providerRegistry.profileFor(Capability.CHAT) ?: return@withContext null
        if (!profile.isConfigured()) return@withContext null

        try {
            val api = providerRegistry.apiFor(profile)
            val prompt = promptStore.prompt(PromptStore.PromptKey.REMINDER_PARSE)
            val effort = providerRegistry.reasoningEffortFor(profile)
            val request = ChatRequest(
                model = profile.model,
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", text)
                ),
                temperature = 0.0,
                // 1024：推理模型思考占配额（512 曾被思考吃光返回空内容）
                maxTokens = 1024,
                responseFormat = ResponseFormat("json_object"),
                reasoningEffort = effort
            )
            val header = providerRegistry.authHeader(profile.apiKey)
            val response = providerRegistry.chatCompat(profile, request, header, api)
            val content = response.choices.firstOrNull()?.message?.textContent
                ?: return@withContext null
            val obj = JsonExtract.objectOf(content) ?: return@withContext null
            val title = JsonExtract.str(obj, "title") ?: return@withContext null
            val repeat = JsonExtract.str(obj, "repeat")
            // 校验：weekly 时 weekday 必须合法
            if (repeat == "weekly" && (JsonExtract.int(obj, "weekday") ?: 0) !in 1..7) {
                return@withContext null
            }
            ParseResult(
                title = title,
                dayOffset = JsonExtract.int(obj, "dayOffset") ?: 0,
                hour = JsonExtract.int(obj, "hour") ?: 0,
                minute = JsonExtract.int(obj, "minute") ?: 0,
                offsetMinutes = JsonExtract.int(obj, "offsetMinutes"),
                repeat = repeat,
                weekday = JsonExtract.int(obj, "weekday") ?: 0
            )
        } catch (e: Exception) {
            // 解析失败不阻塞，调用方让用户补充时间
            null
        }
    }

    /**
     * 本地计算绝对触发时间戳（可靠性关键：日期算术不交给模型）。
     * - offsetMinutes 非空："X分钟后"
     * - 一次性：dayOffset 当天该时刻；时刻已过则顺延到明天
     * - daily：下一次该时刻（今天已过则明天）
     * - weekly：从今天起下一个匹配的星期几（今天匹配且时刻未过则今天）
     */
    fun resolveTrigger(now: Calendar, r: ParseResult): Long {
        val cal = now.clone() as Calendar
        r.offsetMinutes?.let {
            cal.add(Calendar.MINUTE, it)
            return cal.timeInMillis
        }
        cal.set(Calendar.HOUR_OF_DAY, r.hour)
        cal.set(Calendar.MINUTE, r.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, r.dayOffset)
        when (r.repeat) {
            "daily" -> {
                // 每天：取今天/目标日该时刻，已过则推到明天
                while (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_MONTH, 1)
            }
            "weekly" -> {
                // 每周：从目标日（通常今天）找到下一个匹配星期
                while (cal.get(Calendar.DAY_OF_WEEK) != toCalendarDay(r.weekday)) {
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_MONTH, 7)
            }
            else -> {
                // 一次性：目标日时刻已过则顺延到明天
                if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return cal.timeInMillis
    }

    /** 周一=1…周日=7 → Calendar.SUNDAY=1…SATURDAY=7 */
    private fun toCalendarDay(weekday: Int): Int =
        if (weekday == 7) Calendar.SUNDAY else weekday + 1
}
