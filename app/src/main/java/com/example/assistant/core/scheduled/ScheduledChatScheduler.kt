package com.example.assistant.core.scheduled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.assistant.receiver.ScheduledChatReceiver
import com.example.assistant.worker.ScheduledChatWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 定时对话排程器：
 * - AlarmManager 精确闹钟（未授权精确闹钟时用 15 分钟窗口兜底）；
 * - 到点后由 [ScheduledChatReceiver] 排下一次并交给 WorkManager 执行完整对话；
 * - 闹钟在重启/时间或时区变化/应用启动时重排（见 BootReceiver / AssistantApplication）。
 */
class ScheduledChatScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 是否有精确闹钟权限（Android 12+ 默认拒绝） */
    fun canExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * 排下一次触发（会先取消同一 id 的旧闹钟）。
     * @return 下次触发时刻；null = 未启用/配置无效
     */
    fun schedule(schedule: ScheduledChat, now: Long = System.currentTimeMillis()): Long? {
        cancelAlarm(schedule.id)
        if (!schedule.enabled) return null
        val next = nextTriggerAt(schedule, now) ?: return null
        val pi = alarmPendingIntent(schedule.id, next)
        if (canExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            // 未授权精确闹钟：系统可能在窗口内任意时刻触发（通常延迟数分钟）
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, next, FALLBACK_WINDOW_MS, pi)
        }
        return next
    }

    fun cancelAlarm(id: Long) {
        alarmManager.cancel(alarmPendingIntent(id, 0L))
    }

    /** 取消某条的全部待办：闹钟 + 已入队/正在执行的 Worker（删除/停用配置时用） */
    fun cancel(id: Long) {
        cancelAlarm(id)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    /**
     * 立即执行一次（设置页「立即运行」/调试用）。
     * 使用 KEEP：同一配置已有任务在排队/执行时不重复叠加。
     */
    fun enqueueNow(id: Long) {
        enqueueWorker(id)
    }

    private fun enqueueWorker(id: Long) {
        val request = OneTimeWorkRequestBuilder<ScheduledChatWorker>()
            .setInputData(workDataOf(KEY_SCHEDULE_ID to id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id), ExistingWorkPolicy.KEEP, request
        )
    }

    private fun workName(id: Long): String = WORK_NAME_PREFIX + id

    private fun alarmPendingIntent(id: Long, triggerAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_OFFSET + (id and 0xFFFFFF).toInt(),
            Intent(context, ScheduledChatReceiver::class.java)
                .putExtra(KEY_SCHEDULE_ID, id)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val KEY_SCHEDULE_ID = "scheduled_chat_id"

        /** 本次闹钟对应的预定触发时刻（用于去重：重排/补偿触发后旧闹钟再响时直接跳过） */
        const val EXTRA_TRIGGER_AT = "scheduled_chat_trigger_at"

        private const val WORK_NAME_PREFIX = "scheduled_chat_"
        private const val REQUEST_OFFSET = 2_000_000
        private const val FALLBACK_WINDOW_MS = 15 * 60 * 1000L

        /**
         * 下一次触发时刻（纯函数，可单测）：
         * - 每天：今天该时刻已过则明天；
         * - 每周：下一个指定星期几的该时刻（今天已过则下周）。
         *
         * 返回 null = 未启用。秒/毫秒归零。
         */
        fun nextTriggerAt(schedule: ScheduledChat, now: Long): Long? {
            if (!schedule.enabled) return null
            val minute = schedule.minuteOfDay.coerceIn(0, 24 * 60 - 1)
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, minute / 60)
                set(Calendar.MINUTE, minute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (schedule.repeat == ScheduledChat.REPEAT_WEEKLY) {
                val target = schedule.weekday.coerceIn(1, 7)
                // 最多推进 8 天：今天已过 → 下周同一天
                repeat(8) {
                    if (cal.get(Calendar.DAY_OF_WEEK) == target &&
                        cal.timeInMillis > now
                    ) {
                        return cal.timeInMillis
                    }
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                return null
            }
            if (cal.timeInMillis <= now) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
            return cal.timeInMillis
        }

        /**
         * 最近一次**已到点**的预定时刻（补偿触发用；纯函数，可单测）。
         * 例：每天 08:00，now=08:03 → 今天 08:00；now=07:00 → 昨天 08:00。
         * now 恰好等于预定时刻也算"已到点"。
         */
        fun previousTriggerAt(schedule: ScheduledChat, now: Long): Long? {
            if (!schedule.enabled) return null
            val minute = schedule.minuteOfDay.coerceIn(0, 24 * 60 - 1)
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, minute / 60)
                set(Calendar.MINUTE, minute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (schedule.repeat == ScheduledChat.REPEAT_WEEKLY) {
                val target = schedule.weekday.coerceIn(1, 7)
                repeat(8) {
                    if (cal.get(Calendar.DAY_OF_WEEK) == target &&
                        cal.timeInMillis <= now
                    ) {
                        return cal.timeInMillis
                    }
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                }
                return null
            }
            if (cal.timeInMillis > now) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            return cal.timeInMillis
        }
    }
}

/**
 * 定时对话的业务入口：配置保存/删除/启停与闹钟、Worker 的协调。
 * UI 与广播接收器都只依赖它，避免各自拼装存储与排程。
 */
class ScheduledChatManager(
    private val context: Context,
    val store: ScheduledChatStore
) {

    private val scheduler = ScheduledChatScheduler(context)

    /**
     * 串行化"读配置 → 改配置/排闹钟/入队"这一整段：
     * 冷启动时 Application 与 MainActivity.onResume 可能同时 rescheduleAll，
     * 不加锁会基于同一份旧快照各补跑一次（KEEP 只能挡住仍在途的 Worker）。
     */
    private val operationMutex = Mutex()

    val schedules = store.schedules

    val canExact: Boolean get() = scheduler.canExact()

    fun nextTriggerAt(schedule: ScheduledChat): Long? =
        ScheduledChatScheduler.nextTriggerAt(schedule, System.currentTimeMillis())

    /** 保存并重排；停用的配置会同时取消闹钟与待办 */
    suspend fun save(item: ScheduledChat): ScheduledChat = operationMutex.withLock {
        val saved = store.upsert(item)
        if (saved.enabled) scheduler.schedule(saved) else scheduler.cancel(saved.id)
        saved
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): ScheduledChat? = operationMutex.withLock {
        val current = store.byId(id) ?: return@withLock null
        val saved = store.upsert(current.copy(enabled = enabled))
        if (saved.enabled) scheduler.schedule(saved) else scheduler.cancel(saved.id)
        saved
    }

    suspend fun delete(id: Long) = operationMutex.withLock {
        store.delete(id)
        scheduler.cancel(id)
    }

    /** 立即触发一次（不影响下一次排程） */
    fun runNow(id: Long) {
        scheduler.enqueueNow(id)
    }

    /** 重启/启动/时间变化后全量重排 */
    suspend fun rescheduleAll() = operationMutex.withLock {
        val now = System.currentTimeMillis()
        store.all().forEach { schedule ->
            if (!schedule.enabled) {
                scheduler.cancel(schedule.id)
                return@forEach
            }
            // 补偿触发：未授权精确闹钟时系统可能延迟投递；如果这期间用户打开 App/回到前台，
            // 直接重排会按 now 算到"明天/下周"，把本次触发静默吞掉。这里检测"预定时刻刚过
            // 且从未处理过"，先补跑一次（30 分钟内），旧闹钟稍后真正响起时按 triggerAt 去重。
            // 另要求 configChangedAt <= 预定时刻：刚改过时间/刚启用不补跑，避免误触发。
            val previous = ScheduledChatScheduler.previousTriggerAt(schedule, now)
            val activeSince = maxOf(schedule.configChangedAt, schedule.createdAt)
            val due = previous != null &&
                previous <= now &&
                now - previous <= CATCH_UP_GRACE_MS &&
                activeSince > 0L &&
                activeSince <= previous &&
                schedule.lastTriggeredAt < previous
            if (due) {
                store.markTriggered(schedule.id, previous)
                scheduler.enqueueNow(schedule.id)
            }
            scheduler.schedule(schedule)
        }
    }

    /** 闹钟到点：排下一次 + 入队执行本次（接收器调用） */
    suspend fun onAlarm(id: Long, triggerAt: Long) = operationMutex.withLock {
        val schedule = store.byId(id)
        if (schedule == null) {
            scheduler.cancelAlarm(id)
            return@withLock
        }
        if (!schedule.enabled) {
            scheduler.cancel(id)
            return@withLock
        }
        // 同一次触发已由补偿路径处理过（或存在重复闹钟）：只保证下一次已排，不再执行
        if (triggerAt > 0L && schedule.lastTriggeredAt >= triggerAt) {
            scheduler.schedule(schedule)
            return@withLock
        }
        store.markTriggered(id, if (triggerAt > 0L) triggerAt else System.currentTimeMillis())
        scheduler.schedule(schedule)
        scheduler.enqueueNow(id)
    }
}

/** 预定时刻过后多久内打开 App 还补跑本次（覆盖 setWindow 的投递延迟） */
private const val CATCH_UP_GRACE_MS = 30 * 60 * 1000L
