package com.example.assistant.core.calendar

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/**
 * 系统日历写入器：把每日小结写为当天「全天事件」。
 * - 写入用户默认日历（或首选的可见日历）
 * - 同一天重复生成时先删除旧事件再插入 → 保证一天只保留最后一条
 * - 需要 WRITE_CALENDAR 权限（无权限时静默跳过，不打扰）
 */
object CalendarWriter {

    private const val TAG = "CalendarWriter"
    private const val EVENT_TITLE = "每日小结"

    /**
     * 把小结写为归属日期的「全天事件」。date 是归属日期（yyyy-MM-dd）——
     * 由调用方（DailySummaryGenerator）决定：凌晨 4 点前生成的小结归属前一天。
     * 删除范围跟随 date：只删归属日当天的旧小结，不影响其他日期的日历事件。
     * 返回是否成功（无权限/无日历返回 false）。
     */
    fun writeDailySummary(context: Context, date: String, summary: String): Boolean {
        return try {
            val cal = parseDate(date) ?: return false
            val calendarId = pickCalendarId(context) ?: return false
            // 全天事件按 CalendarContract 约定必须以 UTC 毫秒存储。
            // 用 UTC 时区构建当天 0 点（绝对时刻），不要对本地毫秒做加减偏移（方向容易算反）。
            val calUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            }
            val dayStartUtc = calUtc.timeInMillis
            val dayEndUtc = dayStartUtc + 24 * 60 * 60 * 1000

            // 只删当天的旧「每日小结」再插入新的一条（保证一天只保留最后一条）。
            // 注意不能把前一天划进删除范围：早期调试为了清脏事件写过「前一天 + 今天」，
            // 导致每次写今日小结都把昨天的日历小结一起删掉——已修正只删当天。
            deleteExisting(context, calendarId, dayStartUtc, dayEndUtc)

            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, EVENT_TITLE)
                // 写入前把超长行在句末标点处断行：部分日历 App 的事件详情不自动折行，
                // 一长段文字会横向溢出难阅读（荣耀日历已实测）；只影响日历里的排版，App 内展示不受影响
                put(CalendarContract.Events.DESCRIPTION, formatForCalendar(summary))
                put(CalendarContract.Events.DTSTART, dayStartUtc)
                put(CalendarContract.Events.DTEND, dayEndUtc)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC") // 全天事件约定用 UTC
                put(CalendarContract.Events.ALL_DAY, 1)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Log.i(TAG, "写入结果: ${uri?.toString() ?: "null"}（日历 id=$calendarId, UTC=$dayStartUtc）")
            return uri != null
        } catch (e: SecurityException) {
            Log.w(TAG, "无日历权限，跳过写入")
            false
        } catch (e: Exception) {
            Log.w(TAG, "写入系统日历失败", e)
            false
        }
    }

    /** 选择用户默认日历；无默认时选第一个可见日历（排除"其他"类日历） */
    private fun pickCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            var fallbackId: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val visible = cursor.getInt(2)
                if (visible == 1) {
                    fallbackId = id
                    break
                }
            }
            if (fallbackId == null && cursor.moveToFirst()) fallbackId = cursor.getLong(0)
            return fallbackId
        }
        return null
    }

    /** 删除当天已有的「每日小结」事件（保证一天只保留最后一条） */
    private fun deleteExisting(context: Context, calendarId: Long, dayStart: Long, dayEnd: Long) {
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
            "${CalendarContract.Events.TITLE} = ? AND " +
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
        val args = arrayOf(calendarId.toString(), EVENT_TITLE, dayStart.toString(), dayEnd.toString())
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, null, selection, args, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndex(CalendarContract.Events._ID)
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(idCol)
                    context.contentResolver.delete(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                        null, null
                    )
                }
            }
    }

    /**
     * 把小结文本整理成适合日历展示的多行文本：
     * 保留原有换行，并把超长行在句末标点（。！？；）处断成两行，没有标点则按空格断、
     * 再不行硬切。保证每行不长，即使日历 App 不自动折行也能完整可读。
     */
    fun formatForCalendar(text: String): String {
        return text.split("\n").flatMap { wrapLine(it) }.joinToString("\n")
    }

    private fun wrapLine(line: String): List<String> {
        if (line.isEmpty()) return listOf("")
        if (line.length <= MAX_SUMMARY_LINE) return listOf(line)
        val head = line.take(MAX_SUMMARY_LINE)
        // 断点优先选句末标点，其次空格；都在行首则硬切（保证每次至少推进 1 字符，不会死循环）
        val cut = when {
            head.lastIndexOfAny(SENTENCE_ENDS) > 0 -> head.lastIndexOfAny(SENTENCE_ENDS) + 1
            head.lastIndexOf(' ') > 0 -> head.lastIndexOf(' ') + 1
            else -> MAX_SUMMARY_LINE
        }
        return listOf(line.substring(0, cut)) + wrapLine(line.substring(cut))
    }

    private const val MAX_SUMMARY_LINE = 36 // 每行最多约 36 个汉字（手机竖屏约可显示 18-24 字，留余量）

    private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '；')

    /** 解析 yyyy-MM-dd → Calendar（当天 0 点） */
    private fun parseDate(date: String): Calendar? {
        return try {
            val parts = date.split("-")
            if (parts.size != 3) return null
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            cal
        } catch (e: Exception) {
            null
        }
    }
}
