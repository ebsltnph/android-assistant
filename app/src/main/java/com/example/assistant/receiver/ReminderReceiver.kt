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

/**
 * 提醒到点广播（含 5 分钟确认重复触发）。
 *
 * 流程（用户确认前不停止）：
 * 1. 到点 → 发通知（点击可打开 App 弹确认窗）→ 写日记（仅首次）→ 重复提醒重排下一次
 * 2. **用户未确认**（ackedAt < triggerAt）→ 排 5 分钟后再触发（重复发通知，不写日记不重排）
 * 3. 用户确认（MainActivity 弹窗）→ 取消 5 分钟闹钟；一次性提醒标记 fired
 *
 * 免打扰时段内改发静默通知（不响铃，醒来可见）。
 * 注意：BroadcastReceiver 里做 DB 操作必须 goAsync + 协程（不超过 10 秒）。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1)
        if (id < 0) return
        val isAckRepeat = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_ACK_REPEAT, false)
        val app = context.applicationContext as AssistantApplication
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = app.container.reminderRepository
                val scheduler = app.container.reminderScheduler
                val reminder = repo.byId(id)
                // 已删除/已取消 → 忽略（5 分钟闹钟残留由 cancel 清理，这里防御）
                if (reminder == null || reminder.status == "cancelled") return@launch

                // 本次触发已确认（用户确认时间 >= 触发时间）→ 不再重复通知
                val acked = reminder.ackedAtEpochMillis != null &&
                    reminder.ackedAtEpochMillis >= reminder.triggerAtEpochMillis
                if (acked) return@launch

                // 发通知（免打扰 → 静默渠道；点击 → App 弹确认窗）
                val body = "提醒时间到了"
                if (app.container.quietHours.isInQuietWindow()) {
                    Notifier.notifyReminderSilent(context, reminder.title, body, id.toInt(), id)
                } else {
                    Notifier.notifyReminder(context, reminder.title, body, id.toInt(), id)
                }

                // 未确认 → 排下一次 5 分钟重复闹钟（**首次和重复触发都要续排**，
                // 否则只重复一次就停——这是"无法一直发"的根因）
                scheduler.scheduleAckRepeat(id)

                // 5 分钟确认重复触发：只补发通知，不写日记不重排
                if (isAckRepeat) return@launch

                // ---- 首次触发 ----
                // 触发后写入日记（默认日记本；重复提醒每天触发都记）
                app.container.diaryRepository.defaultBook()?.let { book ->
                    app.container.diaryRepository.addEntry(
                        book.id, "⏰ 提醒：${reminder.title}", source = "reminder"
                    )
                }

                // 重复提醒：重排下一次（daily 加 1 天 / weekly 加 7 天，时刻不变；
                // 闹钟延迟/错过触发导致结果已过期时会继续推进到未来时刻）
                val nextTime = ReminderScheduler.nextOccurrence(
                    reminder.triggerAtEpochMillis, reminder.repeatRule, System.currentTimeMillis()
                )
                if (nextTime != null) {
                    repo.reschedule(id, nextTime)
                    scheduler.schedule(reminder.copy(triggerAtEpochMillis = nextTime, status = "pending"))
                }
                // 一次性提醒：不 markFired——保持 pending 等用户确认（确认后 markFired），
                // 未确认则继续 5 分钟重复（僵尸清理已排除未确认的）
            } finally {
                pendingResult.finish()
            }
        }
    }
}
