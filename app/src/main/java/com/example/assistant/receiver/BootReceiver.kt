package com.example.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.assistant.AssistantApplication
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.service.FloatingBallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机广播：重启后恢复后台功能。
 * - 未触发的提醒重新排程到 AlarmManager（闹钟在系统重启后会丢失）
 * - 悬浮球开关开着时自动拉起前台服务（P6）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as AssistantApplication
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                // 未触发的提醒：重排主闹钟（闹钟在系统重启后会丢失）
                val pending = app.container.reminderRepository.pending(now)
                app.container.reminderScheduler.rescheduleAll(pending)
                // 已触发但未确认的提醒：恢复 5 分钟重复闹钟（继续提醒直到用户确认）；
                // 重复提醒同时重排下一次主闹钟（否则错过触发后每日/每周提醒永久失效）
                app.container.reminderRepository.unackedFiredPending(now).forEach {
                    app.container.reminderScheduler.scheduleAckRepeat(it.id)
                    if (it.repeatRule != null) {
                        val next = ReminderScheduler.nextOccurrence(
                            it.triggerAtEpochMillis, it.repeatRule, now
                        ) ?: return@forEach
                        app.container.reminderRepository.reschedule(it.id, next)
                        app.container.reminderScheduler.schedule(
                            it.copy(triggerAtEpochMillis = next, status = "pending")
                        )
                    }
                }
                // P6：悬浮球开关开着 → 开机自动拉起（BOOT_COMPLETED 启动 FGS 是豁免场景）
                if (app.container.settingsStore.floatingBallEnabled.first()) {
                    FloatingBallService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
