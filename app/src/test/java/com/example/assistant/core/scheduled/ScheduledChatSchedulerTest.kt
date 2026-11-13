package com.example.assistant.core.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * 定时对话下次触发时间（纯函数）。
 * 用默认时区构造 Calendar，只断言"未来、时刻正确、星期正确、间隔合理"，不依赖具体时区。
 */
class ScheduledChatSchedulerTest {

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month, day, hour, minute, second)
    }.timeInMillis

    private fun Calendar.part(field: Int): Int = get(field)

    @Test
    fun `daily before time fires today`() {
        val now = at(2026, Calendar.JANUARY, 5, 7, 30)
        val schedule = ScheduledChat(repeat = ScheduledChat.REPEAT_DAILY, minuteOfDay = 8 * 60)

        val next = ScheduledChatScheduler.nextTriggerAt(schedule, now)

        assertTrue(next != null && next > now)
        val cal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(5, cal.part(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.part(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.part(Calendar.MINUTE))
        assertEquals(0, cal.part(Calendar.SECOND))
    }

    @Test
    fun `daily at or after time moves to tomorrow`() {
        val now = at(2026, Calendar.JANUARY, 5, 8, 0)
        val schedule = ScheduledChat(repeat = ScheduledChat.REPEAT_DAILY, minuteOfDay = 8 * 60)

        val next = ScheduledChatScheduler.nextTriggerAt(schedule, now)!!

        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(6, cal.part(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.part(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `weekly upcoming weekday fires later this week`() {
        // 2026-01-04 是周日；目标周一 08:00 → 次日
        val now = at(2026, Calendar.JANUARY, 4, 12, 0)
        val schedule = ScheduledChat(
            repeat = ScheduledChat.REPEAT_WEEKLY,
            weekday = Calendar.MONDAY,
            minuteOfDay = 8 * 60
        )

        val next = ScheduledChatScheduler.nextTriggerAt(schedule, now)!!

        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(5, cal.part(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, cal.part(Calendar.DAY_OF_WEEK))
        assertEquals(8, cal.part(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `weekly today after time moves to next week`() {
        // 周一 09:00，目标周一 08:00 → 下周一
        val now = at(2026, Calendar.JANUARY, 5, 9, 0)
        val schedule = ScheduledChat(
            repeat = ScheduledChat.REPEAT_WEEKLY,
            weekday = Calendar.MONDAY,
            minuteOfDay = 8 * 60
        )

        val next = ScheduledChatScheduler.nextTriggerAt(schedule, now)!!

        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(12, cal.part(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, cal.part(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `disabled schedule has no next trigger`() {
        val schedule = ScheduledChat(enabled = false)
        assertNull(
            ScheduledChatScheduler.nextTriggerAt(
                schedule, at(2026, Calendar.JANUARY, 5, 7, 0)
            )
        )
    }

    @Test
    fun `previous daily trigger is today after the time`() {
        val now = at(2026, Calendar.JANUARY, 5, 8, 3)
        val schedule = ScheduledChat(repeat = ScheduledChat.REPEAT_DAILY, minuteOfDay = 8 * 60)

        val prev = ScheduledChatScheduler.previousTriggerAt(schedule, now)!!

        val cal = Calendar.getInstance().apply { timeInMillis = prev }
        assertEquals(5, cal.part(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.part(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.part(Calendar.MINUTE))
    }

    @Test
    fun `previous daily trigger is yesterday before the time`() {
        val now = at(2026, Calendar.JANUARY, 5, 7, 0)
        val schedule = ScheduledChat(repeat = ScheduledChat.REPEAT_DAILY, minuteOfDay = 8 * 60)

        val prev = ScheduledChatScheduler.previousTriggerAt(schedule, now)!!

        val cal = Calendar.getInstance().apply { timeInMillis = prev }
        assertEquals(4, cal.part(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.part(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `previous weekly trigger walks back to the target weekday`() {
        // 2026-01-07 周三 12:00；目标周一 08:00 → 2026-01-05 周一
        val now = at(2026, Calendar.JANUARY, 7, 12, 0)
        val schedule = ScheduledChat(
            repeat = ScheduledChat.REPEAT_WEEKLY,
            weekday = Calendar.MONDAY,
            minuteOfDay = 8 * 60
        )

        val prev = ScheduledChatScheduler.previousTriggerAt(schedule, now)!!

        val cal = Calendar.getInstance().apply { timeInMillis = prev }
        assertEquals(5, cal.part(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, cal.part(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `default prompt asks model to use reminders and diary`() {
        val prompt = ScheduledChat.DEFAULT_PROMPT
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("list_reminders"))
        assertTrue(prompt.contains("read_diary"))
        assertTrue(prompt.contains("上下文"))
    }

    @Test
    fun `schedule survives json round trip`() {
        val json = Json { encodeDefaults = true }
        val original = ScheduledChat(
            id = 7,
            name = "早间问候",
            prompt = "自定义提示词",
            repeat = ScheduledChat.REPEAT_WEEKLY,
            weekday = Calendar.FRIDAY,
            minuteOfDay = 9 * 60 + 5,
            enabled = false,
            createdAt = 123L,
            lastTriggeredAt = 456L,
            configChangedAt = 789L
        )

        val decoded = json.decodeFromString<ScheduledChat>(
            json.encodeToString(original)
        )

        assertEquals(original, decoded)
    }
}
