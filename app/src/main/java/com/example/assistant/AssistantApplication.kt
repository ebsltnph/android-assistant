package com.example.assistant

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.worker.DailySummaryWorker
import com.example.assistant.worker.EventPollWorker
import com.example.assistant.worker.MorningBriefingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 首次启动：确保种子数据（生活/工作日记本）。
        // runBlocking 只在进程启动时执行一次（Room 查询在后台线程执行，主线程短暂等待，可接受）。
        runBlocking {
            container.diaryRepository.ensureSeedBooks()
        }
        Notifier.ensureChannels(this)
        scheduleDailySummaryWithSetting()
        scheduleEventPoll()
        scheduleBriefingWithSetting()
        // 提醒清理与恢复：
        // - 已触发超 24h 的一次性提醒清理（列表不堆积）
        // - 已确认的过期僵尸提醒清理（未确认的还在 5 分钟确认流程中，跳过）
        // - 已触发但未确认的提醒：恢复 5 分钟重复闹钟（进程被杀/重启后继续提醒直到确认）
        appScope.launch {
            val now = System.currentTimeMillis()
            container.reminderRepository.stalePending(now).forEach {
                container.reminderScheduler.cancel(it.id)
            }
            container.reminderRepository.deleteStalePending(now)
            container.reminderRepository.unackedFiredPending(now).forEach {
                container.reminderScheduler.scheduleAckRepeat(it.id)
            }
            container.reminderRepository.cleanupFired(now - 24 * 3600_000L)
        }
    }

    /** 新闻事件轮询：周期 6 小时（各事件按 pollHours 在 Worker 内过滤） */
    private fun scheduleEventPoll() {
        val request = PeriodicWorkRequestBuilder<EventPollWorker>(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_EVENT_POLL_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * 清晨简报：按设置的时间（分钟数，默认 7:30=450）调度周期任务。
     * 设置页修改后调用 [rescheduleBriefing] 重排（REPLACE 原子替换）。
     */
    fun rescheduleBriefing(minuteOfDay: Int) {
        appScope.launch { scheduleBriefing(minuteOfDay) }
    }

    private fun scheduleBriefingWithSetting() {
        appScope.launch {
            scheduleBriefing(container.settingsStore.briefingMinuteOfDay.first())
        }
    }

    private fun scheduleBriefing(minuteOfDay: Int) {
        val request = PeriodicWorkRequestBuilder<MorningBriefingWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayToMinute(minuteOfDay), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_BRIEFING_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    /** 距下一个指定分钟时刻（当日 0 点起算的分钟数）的毫秒数 */
    private fun initialDelayToMinute(minuteOfDay: Int): Long {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        cal.set(Calendar.MINUTE, minuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var target = cal.timeInMillis
        if (target <= now) target += 24 * 60 * 60 * 1000L
        return target - now
    }

    /** 立即检查一次事件监控（提醒页「立即检查」按钮 / 调试用） */
    fun runEventPollNow() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            WORK_EVENT_POLL_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EventPollWorker>().build()
        )
    }

    /**
     * 每日总结：按设置的时间（分钟数，默认 21:00=1260）调度 WorkManager 周期任务。
     * 设置页修改时间后调用 [rescheduleDailySummary] 重排任务。
     * 注意：minute 由调用方直接传入（用户刚选的值），
     * 不要在这里异步读 DataStore——写入与读取并发时可能读到旧值，重排到错误时间。
     */
    fun rescheduleDailySummary(minute: Int) {
        appScope.launch {
            scheduleDailySummary(minute)
        }
    }

    private fun scheduleDailySummaryWithSetting() {
        appScope.launch {
            scheduleDailySummary(container.settingsStore.dailySummaryMinute.first())
        }
    }

    private fun scheduleDailySummary(minute: Int) {
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayToMinute(minute), TimeUnit.MILLISECONDS)
            .build()
        // REPLACE：直接替换同名任务（原子操作）。
        // 不能用 KEEP+cancel——cancel 是异步的，KEEP 会先看到旧任务而拒绝替换（竞态，改时间不生效）。
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_SUMMARY_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        private const val WORK_SUMMARY_NAME = "daily_summary"
        private const val WORK_EVENT_POLL_NAME = "event_poll"
        private const val WORK_EVENT_POLL_NOW = "event_poll_now"
        private const val WORK_BRIEFING_NAME = "morning_briefing"
    }
}
