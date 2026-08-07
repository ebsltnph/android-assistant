package com.example.assistant.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.assistant.data.db.entity.ReminderEntity
import com.example.assistant.receiver.ReminderReceiver
import java.util.Calendar

/**
 * 提醒排程器：封装 AlarmManager。
 * - 精确闹钟 setExactAndAllowWhileIdle；SCHEDULE_EXACT_ALARM 未授权时 setWindow 兜底（±10 分钟窗口）
 * - 主闹钟：PendingIntent 以 reminder.id 为 requestCode（唯一、可更新）
 * - 确认重复闹钟：提醒触发后用户未确认（通知点击 → App 弹窗确认）时，
 *   每 [ACK_REPEAT_INTERVAL_MS]（5 分钟）重复触发提醒通知，直到用户确认
 *   （确认后 cancelAckRepeat 取消；见 ReminderReceiver / MainActivity 确认弹窗）
 *
 * **每次排闹钟都带上本次触发时刻（EXTRA_TRIGGER_AT）**：重复提醒首次触发后
 * DB 里的 triggerAtEpochMillis 已重排到下一次，Receiver 必须用"本次触发时刻"
 * 判断用户是否已确认本次（否则确认状态永远对不上，见 ReminderReceiver）。
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        /** 5 分钟重复触发的标记（Receiver 据此跳过写日记/重排） */
        const val EXTRA_IS_ACK_REPEAT = "is_ack_repeat"
        /** 本次广播对应的触发时刻（排程时写入；Receiver 用它判断本次是否已确认） */
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val ACK_REPEAT_INTERVAL_MS = 5 * 60 * 1000L

        /** 确认重复闹钟的 requestCode 偏移（与主闹钟区分，避免互相取消） */
        private const val ACK_REPEAT_REQUEST_OFFSET = 1_000_000

        /**
         * 下一次触发时间：daily +1 天 / weekly +7 天（时刻不变）。
         * 结果过期（<= now）则继续推进（错过触发/闹钟延迟后恢复到未来时刻）；
         * 一次性（repeatRule == null）或未知规则返回 null。
         */
        fun nextOccurrence(triggerAt: Long, repeatRule: String?, now: Long): Long? {
            if (repeatRule == null) return null
            val dayStep = when (repeatRule) {
                "daily" -> 1
                "weekly" -> 7
                else -> return null
            }
            val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
            while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, dayStep)
            return cal.timeInMillis
        }
    }

    /** 排程一个提醒（主闹钟；触发时刻写入 extra，Receiver 判断本次确认用） */
    fun schedule(reminder: ReminderEntity) {
        val pi = pendingIntent(reminder.id, reminder.triggerAtEpochMillis)
        if (canExact()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, reminder.triggerAtEpochMillis, pi
            )
        } else {
            // 兜底：10 分钟窗口内触发（MagicOS 可能进一步延迟）
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP, reminder.triggerAtEpochMillis, 10 * 60 * 1000L, pi
            )
        }
    }

    /** 取消提醒（删除/取消/确认时）：同时取消主闹钟与确认重复闹钟 */
    fun cancel(reminderId: Long) {
        alarmManager.cancel(pendingIntent(reminderId, 0))
        alarmManager.cancel(ackRepeatPendingIntent(reminderId, 0))
    }

    /** 全量重排（开机/启动后恢复 pending 提醒） */
    fun rescheduleAll(reminders: List<ReminderEntity>) {
        reminders.forEach { schedule(it) }
    }

    /**
     * 排程 5 分钟后重复提醒（提醒已触发但用户未确认时）。
     * @param triggerAtEpochMillis 本次触发时刻（Receiver 用它判断本次是否已确认）
     */
    fun scheduleAckRepeat(reminderId: Long, triggerAtEpochMillis: Long) {
        val pi = ackRepeatPendingIntent(reminderId, triggerAtEpochMillis)
        val at = System.currentTimeMillis() + ACK_REPEAT_INTERVAL_MS
        if (canExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi)
        }
    }

    /** 取消 5 分钟重复提醒闹钟（用户确认后）。extras 不参与 PendingIntent 匹配，0 占位即可 */
    fun cancelAckRepeat(reminderId: Long) {
        alarmManager.cancel(ackRepeatPendingIntent(reminderId, 0))
    }

    /** 是否有精确闹钟权限（Android 12+ 默认拒绝，需用户到系统设置开启） */
    fun canExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(reminderId: Long, triggerAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ackRepeatPendingIntent(reminderId: Long, triggerAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.toInt() + ACK_REPEAT_REQUEST_OFFSET,
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_IS_ACK_REPEAT, true)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
