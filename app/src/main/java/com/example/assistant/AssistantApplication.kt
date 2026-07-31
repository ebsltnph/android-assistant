package com.example.assistant

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.worker.DailySummaryWorker
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 首次启动：确保种子数据（生活/工作日记本）。
        // runBlocking 只在进程启动时执行一次（Room 查询在后台线程执行，主线程短暂等待，可接受）。
        runBlocking {
            container.diaryRepository.ensureSeedBooks()
        }
        Notifier.ensureChannels(this)
        scheduleDailySummary()
    }

    /**
     * 每日总结：每天 21:00 后执行（WorkManager 周期任务，尊重系统省电策略）。
     * 首日延时对齐到下一个 21:00；KEEP 策略保证不会重复调度。
     */
    private fun scheduleDailySummary() {
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayToNextSummary(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_summary",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** 距下一个 21:00 的毫秒数（若已过则顺延到明天） */
    private fun initialDelayToNextSummary(): Long {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 21)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var target = cal.timeInMillis
        if (target <= now) target += 24 * 60 * 60 * 1000L
        return target - now
    }
}
