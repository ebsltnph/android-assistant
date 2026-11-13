package com.example.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.assistant.AssistantApplication
import com.example.assistant.core.scheduled.ScheduledChatScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 定时对话闹钟接收器（2026-11-14）：
 * 到点先把下一次闹钟排好（即使本次执行失败/进程随后被杀，明天/下周仍会触发），
 * 再把真正的对话交给 WorkManager（后台执行时间不受广播 10 秒限制）。
 */
class ScheduledChatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ScheduledChatScheduler.KEY_SCHEDULE_ID, -1L)
        if (id < 0) return
        val triggerAt = intent.getLongExtra(ScheduledChatScheduler.EXTRA_TRIGGER_AT, 0L)
        val app = context.applicationContext as AssistantApplication
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.scheduledChatManager.onAlarm(id, triggerAt)
            } catch (_: Exception) {
                // 后台唤醒失败不崩溃；下次应用启动会全量重排
            } finally {
                pendingResult.finish()
            }
        }
    }
}
