package com.example.assistant.core.agent.tools

import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.agent.ReminderTimeParser
import com.example.assistant.data.repo.ReminderRepository
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

/**
 * 设置提醒工具：模型直接给**结构化时间参数**（不再有独立的时间解析 LLM 调用），
 * 绝对时间戳仍由本地 Calendar 计算（模型日期算术不可靠，见 ReminderTimeParser 说明）。
 * 校验失败（参数非法/时间在过去）返回 Failure，错误回传给模型修正后重试或向用户追问。
 */
class SetReminderTool(
    private val reminderRepository: ReminderRepository,
    private val reminderScheduler: ReminderScheduler,
    private val timeCalc: ReminderTimeParser
) : AssistantTool {

    override val name = "set_reminder"
    override val description =
        "set_reminder(title, day_offset, hour, minute, offset_minutes?, repeat?, weekday?)：设置提醒/闹钟。" +
        "args 示例：{\"title\":\"开会\",\"day_offset\":1,\"hour\":9,\"minute\":0}（明天上午9点）；" +
        "{\"title\":\"喝水\",\"offset_minutes\":30}（30分钟后）；" +
        "每天重复加 \"repeat\":\"daily\"；每周加 \"repeat\":\"weekly\" 和 \"weekday\":1-7（周一=1）。" +
        "注意：day_offset/hour/minute 由你结合系统消息里的当前时间推算填写，具体时间戳由程序本地计算。"

    override fun actionLabel(args: JsonObject): String {
        val t = args.argStr("title")?.take(12) ?: "?"
        return "提醒「$t」"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val title = args.argStr("title")
            ?: return ToolOutcome.Failure("缺少必填参数 title（提醒内容）")
        val hour = args.argInt("hour") ?: 0
        val minute = args.argInt("minute") ?: 0
        val dayOffset = args.argInt("day_offset") ?: 0
        val offsetMinutes = args.argInt("offset_minutes")
        val repeat = args.argStr("repeat")?.takeIf { it == "daily" || it == "weekly" }
        val weekday = args.argInt("weekday") ?: 0

        // ---- 参数校验（错误回传给模型自纠）----
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolOutcome.Failure("hour/minute 非法（小时 0-23、分钟 0-59），收到：hour=$hour, minute=$minute")
        }
        if (dayOffset < 0) return ToolOutcome.Failure("day_offset 不能为负数（不能设置过去的提醒），收到：$dayOffset")
        offsetMinutes?.let { if (it <= 0) return ToolOutcome.Failure("offset_minutes 必须为正整数（多少分钟后），收到：$it") }
        if (repeat == null && args.argStr("repeat") != null) {
            return ToolOutcome.Failure("repeat 只支持 daily / weekly，收到：" + args.argStr("repeat"))
        }
        if (repeat == "weekly" && weekday !in 1..7) {
            return ToolOutcome.Failure("repeat=weekly 时必须提供合法的 weekday（周一=1…周日=7），收到：$weekday")
        }

        val parsed = ReminderTimeParser.ParseResult(
            title = title,
            dayOffset = dayOffset,
            hour = hour,
            minute = minute,
            offsetMinutes = offsetMinutes,
            repeat = repeat,
            weekday = weekday
        )
        val triggerAt = timeCalc.resolveTrigger(Calendar.getInstance(), parsed)
        if (triggerAt <= System.currentTimeMillis()) {
            return ToolOutcome.Failure(
                "计算出的触发时间在过去（" + formatTrigger(triggerAt) + "），请检查时间参数后重新调用，或向用户确认时间。"
            )
        }

        val id = reminderRepository.add(
            title = title,
            triggerAtEpochMillis = triggerAt,
            repeatRule = repeat
        )
        reminderScheduler.schedule(reminderRepository.byId(id)!!)

        val repeatText = when (repeat) {
            "daily" -> "（每天重复）"
            "weekly" -> "（每周重复）"
            else -> ""
        }
        return ToolOutcome.Success("已设置提醒：「$title」，触发时间 ${formatTrigger(triggerAt)}$repeatText。请自然地告知用户。")
    }

    /** 今天/明天/M月d日 + HH:mm */
    private fun formatTrigger(millis: Long): String {
        val cal = Calendar.getInstance()
        val now = Calendar.getInstance()
        cal.timeInMillis = millis
        val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val dayDiff = cal.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
        return when {
            dayDiff == 0 -> "今天 $time"
            dayDiff == 1 -> "明天 $time"
            else -> "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $time"
        }
    }
}