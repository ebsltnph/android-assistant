package com.example.assistant.core.quiet

import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * 免打扰时段判断。窗口以"分钟数"存储（如 23:00 = 1380，07:00 = 420）。
 * 起止相同 = 未启用。支持跨午夜（23:00-07:00 这类 start > end 的窗口）。
 */
class QuietHours(private val settingsStore: SettingsStore) {

    /** 当前时刻是否处于免打扰窗口（默认 23:00-07:00） */
    suspend fun isInQuietWindow(now: Calendar = Calendar.getInstance()): Boolean {
        val start = settingsStore.quietStartMinute.first()
        val end = settingsStore.quietEndMinute.first()
        if (start == end) return false // 起止相同视为未启用
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (start < end) {
            nowMinute in start until end
        } else {
            nowMinute >= start || nowMinute < end
        }
    }
}
