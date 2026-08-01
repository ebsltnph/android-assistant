package com.example.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.assistant.AssistantApplication
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.notification.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 提醒到点广播：发通知 → 标记 fired → 重复提醒重排下一次。
 * 免打扰时段内改发静默通知（不响铃，醒来可见）。
 * 注意：BroadcastReceiver 里做 DB 操作必须 goAsync + 协程（不超过 10 秒）。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1)
        if (id < 0) return
        val app = context.applicationContext as AssistantApplication
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = app.container.reminderRepository
                val scheduler = app.container.reminderScheduler
                val reminder = repo.byId(id)
                if (reminder == null || reminder.status != "pending") return@launch // 已取消/已触发过

                val body = "提醒时间到了"
                if (app.container.quietHours.isInQuietWindow()) {
                    Notifier.notifyReminderSilent(context, reminder.title, body, id.toInt())
                } else {
                    Notifier.notifyReminder(context, reminder.title, body, id.toInt())
                }

                // 触发后写入日记（默认日记本，作为当天记录；重复提醒每天触发都记）
                app.container.diaryRepository.defaultBook()?.let { book ->
                    app.container.diaryRepository.addEntry(
                        book.id, "⏰ 提醒：${reminder.title}", source = "reminder"
                    )
                }

                // 重复提醒：重排下一次（daily 加 1 天 / weekly 加 7 天，时刻不变）
                val nextTime = nextTrigger(reminder.triggerAtEpochMillis, reminder.repeatRule)
                if (nextTime != null) {
                    repo.reschedule(id, nextTime)
                    scheduler.schedule(reminder.copy(triggerAtEpochMillis = nextTime, status = "pending"))
                } else {
                    repo.markFired(id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 计算下一次触发时间：daily = 明天同时刻；weekly = 下周日同时刻；null = 一次性 */
    private fun nextTrigger(triggerAt: Long, repeatRule: String?): Long? {
        if (repeatRule == null) return null
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
        when (repeatRule) {
            "daily" -> cal.add(Calendar.DAY_OF_MONTH, 1)
            "weekly" -> cal.add(Calendar.DAY_OF_WEEK, 7)
            else -> return null
        }
        return cal.timeInMillis
    }
}
