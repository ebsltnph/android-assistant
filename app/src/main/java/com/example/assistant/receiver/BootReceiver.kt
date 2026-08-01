package com.example.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.assistant.AssistantApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机广播：重启后把未触发的提醒重新排程到 AlarmManager
 * （闹钟在系统重启后会丢失，必须重排）。
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
                // 已触发但未确认的提醒：恢复 5 分钟重复闹钟（继续提醒直到用户确认）
                app.container.reminderRepository.unackedFiredPending(now).forEach {
                    app.container.reminderScheduler.scheduleAckRepeat(it.id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
