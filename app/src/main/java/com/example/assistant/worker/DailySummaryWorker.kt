package com.example.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.AssistantApplication
import com.example.assistant.core.agent.GENERATION_MAX_RETRY
import com.example.assistant.core.agent.GenerationOutcome
import com.example.assistant.core.agent.noticeText
import com.example.assistant.core.notification.Notifier
import kotlinx.coroutines.flow.first

/**
 * 每日总结 Worker（默认 21:00 后执行）：
 * 汇总当天日记 → LLM 整理成小结 → 发通知。
 * 当天没有日记时直接跳过（不打扰用户）。
 *
 * 2026-09-23：失败不再静默——**把失败原因通知给用户，并安排一次 15 分钟后的自动重试**
 * （`Result.retry()` + 调度侧的 15 分钟退避；`runAttemptCount` 保证只重试一次）。
 * "未配置模型"这类重试没用的原因不重试，只告诉用户原因。
 */
class DailySummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AssistantApplication).container
        // 设置页已关闭自动每日小结：只取消调度还不够，Worker 本身也复核（防旧任务/竞态执行）
        if (!container.settingsStore.dailySummaryEnabled.first()) return Result.success()
        return when (val outcome = container.dailySummaryGenerator.generateToday()) {
            is GenerationOutcome.NoData -> Result.success()   // 今天没写日记，不打扰
            is GenerationOutcome.Ok -> {
                Notifier.notifyDiarySummary(applicationContext, outcome.text)
                Result.success()
            }
            is GenerationOutcome.Failed -> {
                val willRetry = outcome.retryable && runAttemptCount < GENERATION_MAX_RETRY
                Notifier.notifyDiarySummary(applicationContext, outcome.noticeText(willRetry))
                // 注意：周期任务绝不能返回 failure()（那会取消后续周期），只 retry 或 success
                if (willRetry) Result.retry() else Result.success()
            }
        }
    }
}
