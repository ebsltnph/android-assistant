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
                val pending = app.container.reminderRepository.pending(System.currentTimeMillis())
                app.container.reminderScheduler.rescheduleAll(pending)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
