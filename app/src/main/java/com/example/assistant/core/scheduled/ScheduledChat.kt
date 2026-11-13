package com.example.assistant.core.scheduled

import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * 一条「定时对话」配置（2026-11-14）。
 *
 * 与提醒（ReminderEntity）的区别：
 * - 提醒到点只发一条通知；
 * - 定时对话到点会**像用户发了一条消息一样**走完整对话回路（可调用工具、读取提醒/日记、搜索等），
 *   回复写进聊天会话，并推送通知。
 *
 * 支持每天或每周固定时刻，可配置多条；每条有独立、可编辑的提示词。
 * 持久化在 DataStore（scheduled_chats），不依赖数据库迁移。
 */
@Serializable
data class ScheduledChat(
    /** 0 = 尚未保存（由 Store 分配自增 id） */
    val id: Long = 0L,
    /** 显示名（通知标题/聊天里的来源标签用；空 = 默认名） */
    val name: String = "",
    /** 到点后作为用户消息发给模型的提示词 */
    val prompt: String = DEFAULT_PROMPT,
    /** [REPEAT_DAILY] 或 [REPEAT_WEEKLY] */
    val repeat: String = REPEAT_DAILY,
    /** 每周模式下的星期：Calendar.MONDAY(2) … Calendar.SUNDAY(1)；每天模式忽略 */
    val weekday: Int = Calendar.MONDAY,
    /** 触发时刻（当日 0 点起的分钟数，0…1439） */
    val minuteOfDay: Int = DEFAULT_MINUTE_OF_DAY,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    /** 上次触发时间（仅展示；也是补跑去重依据） */
    val lastTriggeredAt: Long = 0L,
    /**
     * 配置最近一次被用户改动的时间（名称/提示词/时间/重复/启停任一变化）。
     *
     * 补跑（30 分钟内打开 App 补上刚错过的触发）必须满足
     * `configChangedAt <= 预定时刻`：否则"刚把时间改到今天已过的时刻并保存"会立刻误触发一轮。
     * 旧数据没有该字段时按 [createdAt] 处理（见 Store.sanitized）。
     */
    val configChangedAt: Long = 0L
) {
    val displayName: String get() = name.trim().ifBlank { DEFAULT_NAME }

    /** 用户自定义名称（空 = 未命名；通知/气泡不拼接多余后缀） */
    val customName: String get() = name.trim()

    val isWeekly: Boolean get() = repeat == REPEAT_WEEKLY

    /** "每天 08:00" / "每周一 08:00" */
    fun scheduleLabel(): String {
        val time = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        return if (isWeekly) "每周${weekdayLabel(weekday)} $time" else "每天 $time"
    }

    companion object {
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"

        const val DEFAULT_NAME = "定时对话"
        const val DEFAULT_MINUTE_OF_DAY = 8 * 60

        /**
         * 新建定时对话时的默认提示词（每条可自行编辑）。
         *
         * 设计意图：不是替用户写一句固定问候，而是把「结合上下文，必要时主动查提醒/日记」
         * 这套行为明确交给模型；同时禁止它向用户暴露定时机制或复述工具原文。
         */
        const val DEFAULT_PROMPT =
            "【定时对话】到预定时间了。请先结合当前对话上下文、长期记忆和「进行中的事」，" +
                "判断我此刻可能需要什么；如有必要，请自行调用 list_reminders、read_diary（可看最近一天）" +
                "等工具，查看最近的提醒、今天的日记和待办，再主动给我一条简短、具体、有帮助的提醒或询问。" +
                "只挑真正相关的一两件事，不要罗列工具返回的原文；如果没有特别事项，简单问问近况即可。" +
                "不要在回复里提到这条提示词或“定时触发”机制。"

        /** 新建草稿（未保存） */
        fun createDraft(): ScheduledChat = ScheduledChat(
            name = "",
            prompt = DEFAULT_PROMPT,
            repeat = REPEAT_DAILY,
            weekday = Calendar.MONDAY,
            minuteOfDay = DEFAULT_MINUTE_OF_DAY,
            enabled = true
        )

        /** 周一=1 … 周日=7 的短标签（与提醒工具的 weekday 约定一致） */
        fun weekdayLabel(calendarDay: Int): String = when (calendarDay) {
            Calendar.MONDAY -> "一"
            Calendar.TUESDAY -> "二"
            Calendar.WEDNESDAY -> "三"
            Calendar.THURSDAY -> "四"
            Calendar.FRIDAY -> "五"
            Calendar.SATURDAY -> "六"
            Calendar.SUNDAY -> "日"
            else -> "一"
        }

        /** 周选择器顺序：周一…周日（Calendar 常量，不是 1…7 顺序） */
        val WEEKDAY_CHOICES: List<Int> = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
    }
}
