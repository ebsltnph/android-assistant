package com.example.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.assistant.AssistantApplication
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.notification.Notifier
import com.example.assistant.data.db.entity.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 提醒到点广播（含 5 分钟确认重复触发）。
 *
 * 流程（用户确认前不停止）：
 * 1. 到点 → 写日记（仅首次）→ 重复提醒重排下一次 → 发通知（点击可打开 App 弹确认窗）
 * 2. **用户未确认**（ackedAt < 本次触发时刻）→ 排 5 分钟后再触发（重复发通知，不写日记不重排）
 * 3. 用户确认（MainActivity 弹窗）→ 取消 5 分钟闹钟；一次性提醒标记 fired
 *
 * 两个关键点（"确认后仍继续提醒" bug 的根因，勿改）：
 * - **用"本次触发时刻"（EXTRA_TRIGGER_AT，排程时写入）判断确认**——重复提醒首次触发后
 *   DB 的 triggerAtEpochMillis 已重排到下一次，用它判断会把已确认的当成未确认
 * - **续排 5 分钟闹钟前重新读库**——用户确认与本次广播并发时，在途的广播可能
 *   在确认之后再排上闹钟（旧代码的竞态：确认被 cancelAckRepeat 取消了又排回来）
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
                // 已删除/已取消/已标记触发完成 → 忽略（5 分钟闹钟残留由 cancel 清理，这里防御）
                if (reminder == null || reminder.status == "cancelled" || reminder.status == "fired") {
                    return@launch
                }

                // 本次广播对应的触发时刻（排程时写入；老版本闹钟无此字段时回退 DB 当前值）
                val thisTrigger = intent.getLongExtra(
                    ReminderScheduler.EXTRA_TRIGGER_AT, reminder.triggerAtEpochMillis
                )
                // 本次触发已确认（用户确认时间 >= 本次触发时刻）→ 不再发通知
                if (isAcked(reminder, thisTrigger)) return@launch

                // ---- 首次触发（非 5 分钟重复）：写日记 + 重排下一次 ----
                if (!isAckRepeat) {
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
                        scheduler.schedule(
                            reminder.copy(triggerAtEpochMillis = nextTime, status = "pending")
                        )
                    }
                    // 一次性提醒：不 markFired——保持 pending 等用户确认（确认后 markFired），
                    // 未确认则继续 5 分钟重复（僵尸清理已排除未确认的）
                }

                // 发通知（免打扰 → 静默渠道；点击 → App 弹确认窗）
                val body = "提醒时间到了"
                if (app.container.quietHours.isInQuietWindow()) {
                    Notifier.notifyReminderSilent(context, reminder.title, body, id.toInt(), id)
                } else {
                    Notifier.notifyReminder(context, reminder.title, body, id.toInt(), id)
                }

                // 未确认 → 排下一次 5 分钟重复闹钟（**首次和重复触发都要续排**，
                // 否则只重复一次就停——这是"无法一直发"的根因）。
                // 续排前**重新读取**：用户可能恰好在此期间确认——已确认则不续排
                // （否则会把刚被 cancelAckRepeat 取消的闹钟又排回来，即"确认后仍继续提醒"）
                val fresh = repo.byId(id)
                if (fresh == null || fresh.status == "cancelled" || fresh.status == "fired") return@launch
                if (isAcked(fresh, thisTrigger)) return@launch
                scheduler.scheduleAckRepeat(id, thisTrigger)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 本次触发是否已确认：确认时间 >= 本次触发时刻 */
    private fun isAcked(reminder: ReminderEntity, thisTrigger: Long): Boolean =
        reminder.ackedAtEpochMillis != null && reminder.ackedAtEpochMillis >= thisTrigger
}
