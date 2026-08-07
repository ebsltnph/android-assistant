package com.example.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.service.FloatingBallService
import com.example.assistant.worker.DailySummaryWorker
import com.example.assistant.worker.EventPollWorker
import com.example.assistant.worker.MorningBriefingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)

    /** 已 STARTED 的 Activity 计数（>0 = App 在前台）。驱动 FGS 启动的前台判断 */
    private val startedActivities = java.util.concurrent.atomic.AtomicInteger(0)

    /** App 是否在前台（有 Activity 处于 STARTED 及以上状态） */
    val isAppInForeground: Boolean get() = startedActivities.get() > 0

    override fun onCreate() {
        super.onCreate()
        // 前台计数：FloatingBallService.start 用它在后台冷启动（闹钟/WorkManager 唤醒进程）时
        // 跳过 FGS 启动——后台启动前台服务要么抛 ForegroundServiceStartNotAllowedException，
        // 要么（豁免窗口内）5 秒内没 startForeground 会被系统直接杀进程
        // （ForegroundServiceDidNotStartInTimeException，真机抓到的"重启后打开闪退"实锤）
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities.decrementAndGet().coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
        container = AppContainer(this)
        // 首次启动：确保种子数据（「日记」本）。
        // **异步执行，绝不阻塞主线程**：DB 首次打开/迁移/关机后 WAL 恢复可能耗时数秒，
        // 期间主线程若被 runBlocking 卡住，FGS 的 startForeground 超时 5 秒会被系统杀进程
        // （ForegroundServiceDidNotStartInTimeException——"重启后快速打开 App 闪退"的真机实锤）。
        // 种子未完成时日记页会自己补种（见 DiaryViewModel.initSelectedBook）。
        appScope.launch {
            try {
                container.diaryRepository.ensureSeedBooks()
            } catch (_: Exception) {
            }
        }
        Notifier.ensureChannels(this)
        scheduleDailySummaryWithSetting()
        scheduleEventPoll()
        scheduleBriefingWithSetting()
        // 提醒清理与恢复：
        // - 已触发超 24h 的一次性提醒清理（列表不堆积）
        // - 已确认的过期僵尸提醒清理（未确认的还在 5 分钟确认流程中，跳过）
        // - 已触发但未确认的提醒：恢复 5 分钟重复闹钟（进程被杀/重启后继续提醒直到确认）；
        //   重复提醒同时重排下一次主闹钟（否则错过触发后每日/每周提醒永久失效）
        appScope.launch {
            val now = System.currentTimeMillis()
            // 未触发的提醒：重排主闹钟（幂等覆盖，无害）。
            // force-stop 会清掉 App 已排的 AlarmManager 闹钟（DB 里还是 pending）——
            // 启动时重排让提醒自愈，不用等下次开机（开机路径在 BootReceiver）
            container.reminderRepository.pending(now).forEach {
                container.reminderScheduler.schedule(it)
            }
            container.reminderRepository.stalePending(now).forEach {
                container.reminderScheduler.cancel(it.id)
            }
            container.reminderRepository.deleteStalePending(now)
            container.reminderRepository.unackedFiredPending(now).forEach {
                // 传触发时刻：Receiver 用它判断"本次触发是否已确认"
                // （此查询返回的提醒 triggerAt 都在过去 = 本次触发的时刻）
                container.reminderScheduler.scheduleAckRepeat(it.id, it.triggerAtEpochMillis)
                if (it.repeatRule != null) {
                    val next = ReminderScheduler.nextOccurrence(
                        it.triggerAtEpochMillis, it.repeatRule, now
                    ) ?: return@forEach
                    container.reminderRepository.reschedule(it.id, next)
                    container.reminderScheduler.schedule(
                        it.copy(triggerAtEpochMillis = next, status = "pending")
                    )
                }
            }
            container.reminderRepository.cleanupFired(now - 24 * 3600_000L)
        }
        // P6 悬浮球：开关开着 → 进入 App 自动恢复悬浮球服务（防系统杀后台后丢失）。
        // **延迟 2 秒再启动**：onCreate 时首个 Activity 可能还没 STARTED（isAppInForeground=false），
        // 此时启动 FGS 有 5 秒超时被杀风险（ForegroundServiceDidNotStartInTimeException）；
        // 2 秒后用户已进入主界面 → App 在前台 → start 内部的前台检查通过，安全启动。
        // 后台冷启动（闹钟/WorkManager 唤醒进程，无 Activity）→ 检查不过，跳过（本就无需悬浮球）。
        appScope.launch {
            if (container.settingsStore.floatingBallEnabled.first()) {
                kotlinx.coroutines.delay(2_000)
                FloatingBallService.start(this@AssistantApplication)
            }
        }
        // 缓存自动清理（后台执行，失败不影响启动）：孤儿日记图片 + 过期识屏截图
        cleanupCaches()
    }

    /**
     * 缓存自动清理（启动时后台执行）：
     * 1. **孤儿日记图片**：filesDir/diary_images 下未被 DB 引用的文件
     *    （删除条目/删单张图片/换图失败等场景可能留下残留）
     * 2. **过期识屏截图**：cacheDir/screensense 下超过 7 天的文件（截图体积大，
     *    系统 cache 清理不保证及时）
     */
    private fun cleanupCaches() {
        appScope.launch {
            try {
                val referenced = container.diaryRepository.allImagePaths().toHashSet()
                File(filesDir, "diary_images").listFiles()?.forEach { f ->
                    if (f.absolutePath !in referenced) f.delete()
                }
                val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L
                File(cacheDir, "screensense").listFiles()?.forEach { f ->
                    if (f.lastModified() < cutoff) f.delete()
                }
            } catch (_: Exception) {
                // 清理失败不影响启动
            }
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
