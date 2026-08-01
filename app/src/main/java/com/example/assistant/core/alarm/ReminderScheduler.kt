package com.example.assistant.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.assistant.data.db.entity.ReminderEntity
import com.example.assistant.receiver.ReminderReceiver

/**
 * 提醒排程器：封装 AlarmManager。
 * - 精确闹钟 setExactAndAllowWhileIdle；SCHEDULE_EXACT_ALARM 未授权时 setWindow 兜底（±10 分钟窗口）
 * - PendingIntent 以 reminder.id 为 requestCode（唯一、可更新）
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
    }

    /** 排程一个提醒 */
    fun schedule(reminder: ReminderEntity) {
        val pi = pendingIntent(reminder.id)
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

    /** 取消提醒（删除/取消时） */
    fun cancel(reminderId: Long) {
        alarmManager.cancel(pendingIntent(reminderId))
    }

    /** 全量重排（开机/启动后恢复 pending 提醒） */
    fun rescheduleAll(reminders: List<ReminderEntity>) {
        reminders.forEach { schedule(it) }
    }

    /** 是否有精确闹钟权限（Android 12+ 默认拒绝，需用户到系统设置开启） */
    fun canExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(reminderId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_REMINDER_ID, reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
