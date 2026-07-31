package com.example.assistant

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.worker.DailySummaryWorker
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
    }

    /**
     * 每日总结：按设置的小时（默认 21:00）调度 WorkManager 周期任务。
     * 设置页修改时间后调用 [rescheduleDailySummary] 重排任务。
     * 注意：hour 由调用方直接传入（用户刚选的值），
     * 不要在这里异步读 DataStore——写入与读取并发时可能读到旧值，重排到错误时间。
     */
    fun rescheduleDailySummary(hour: Int) {
        appScope.launch {
            scheduleDailySummary(hour)
        }
    }

    private fun scheduleDailySummaryWithSetting() {
        appScope.launch {
            val hour = container.settingsStore.dailySummaryHour.first()
            scheduleDailySummary(hour)
        }
    }

    private fun scheduleDailySummary(hour: Int) {
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayToHour(hour), TimeUnit.MILLISECONDS)
            .build()
        // REPLACE：直接替换同名任务（原子操作）。
        // 不能用 KEEP+cancel——cancel 是异步的，KEEP 会先看到旧任务而拒绝替换（竞态，改时间不生效）。
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_SUMMARY_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    /** 距下一个指定小时（24h 制）的毫秒数（若已过则顺延到明天） */
    private fun initialDelayToHour(hour: Int): Long {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var target = cal.timeInMillis
        if (target <= now) target += 24 * 60 * 60 * 1000L
        return target - now
    }

    companion object {
        private const val WORK_SUMMARY_NAME = "daily_summary"
    }
}
