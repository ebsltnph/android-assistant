package com.example.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.AssistantApplication
import com.example.assistant.core.agent.DailySummaryGenerator
import com.example.assistant.core.notification.Notifier
import kotlinx.coroutines.flow.first

/**
 * 每日总结 Worker（默认 21:00 后执行）：
 * 汇总当天日记 → LLM 整理成小结 → 发通知。
 * 当天没有日记时直接跳过（不打扰用户）。
 */
class DailySummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AssistantApplication).container
        // 设置页已关闭自动每日小结：只取消调度还不够，Worker 本身也复核（防旧任务/竞态执行）
        if (!container.settingsStore.dailySummaryEnabled.first()) return Result.success()
        val generator = DailySummaryGenerator(
            diaryRepository = container.diaryRepository,
            providerRegistry = container.providerRegistry,
            promptStore = container.promptStore,
            summaryStore = container.summaryStore,
            summaryRepository = container.summaryRepository,
            appContext = applicationContext
        )
        val summary = generator.generateToday() ?: return Result.success() // 今天没写日记，不打扰
        Notifier.notifyDiarySummary(applicationContext, summary)
        return Result.success()
    }
}
