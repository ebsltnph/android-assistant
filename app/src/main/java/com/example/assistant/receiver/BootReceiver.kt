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

    companion object {
        /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED（API 31 常量） */
        private const val ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val boot = action == Intent.ACTION_BOOT_COMPLETED
        // 时间/时区变化也要重排定时对话：闹钟存的是绝对时刻，跨时区后"每天早上 8 点"会偏
        val timeChanged = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        // 授予精确闹钟权限后（系统会清理旧的 inexact 闹钟）立即重排
        val exactPermissionChanged =
            action == ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (!boot && !timeChanged && !exactPermissionChanged) return
        val app = context.applicationContext as AssistantApplication
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (boot) {
                    val now = System.currentTimeMillis()
                    // 开机恢复是后台场景，任何单步失败都不应让进程崩溃（重启后打开 App 闪退防护）
                    // 未触发的提醒：重排主闹钟（闹钟在系统重启后会丢失）
                    val pending = app.container.reminderRepository.pending(now)
                    app.container.reminderScheduler.rescheduleAll(pending)
                    // 已触发但未确认的提醒：恢复 5 分钟重复闹钟（继续提醒直到用户确认）；
                    // 重复提醒同时重排下一次主闹钟（否则错过触发后每日/每周提醒永久失效）
                    app.container.reminderRepository.unackedFiredPending(now).forEach {
                        // 传触发时刻：Receiver 用它判断"本次触发是否已确认"
                        app.container.reminderScheduler.scheduleAckRepeat(it.id, it.triggerAtEpochMillis)
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
                    // P6：悬浮球开关开着 → 开机自动拉起（BOOT_COMPLETED 启动 FGS 是豁免场景，
                    // allowBackground = true 跳过前台检查——开机时还没有任何 Activity）
                    if (app.container.settingsStore.floatingBallEnabled.first()) {
                        FloatingBallService.start(context, allowBackground = true)
                    }
                }
                // 定时对话：开机与时间/时区变化后都全量重排
                app.container.scheduledChatManager.rescheduleAll()
            } catch (_: Exception) {
                // 开机恢复失败不崩溃：该做的都做了，失败项下次打开 App 时由 Application 再补
            } finally {
                pendingResult.finish()
            }
        }
    }
}
