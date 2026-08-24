package com.example.assistant.core.agent

import java.util.Calendar

/**
 * 提醒时间本地计算（无 LLM）：
 * 主模型经 set_reminder 工具直接给**结构化时间参数**，绝对时间戳由这里用 Calendar 计算——
 * 模型日期算术不可靠（曾把"2分钟后"算成 7月3日），结构描述 + 本地计算才稳。
 */
class ReminderTimeParser {

    /** 结构化时间描述（模型输出）；triggerAt 由 [resolveTrigger] 本地计算 */
    data class ParseResult(
        val title: String,
        /** 相对今天的偏移（0=今天 1=明天 …） */
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

    /**
     * 本地计算绝对触发时间戳。
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