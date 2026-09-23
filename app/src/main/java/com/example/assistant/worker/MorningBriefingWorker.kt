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
 * 清晨简报 Worker（默认 7:30，设置页可改）：
 * 今日提醒 + 昨日小结 → LLM 组装 → 通知。
 *
 * 2026-09-23：失败不再静默（原先异常时直接吃模板、用户看不出没润色过）——
 * 现在**通知里写明失败原因，并安排一次 15 分钟后的自动重试**
 * （`Result.retry()` + 调度侧的 15 分钟退避；`runAttemptCount` 保证只重试一次）。
 */
class MorningBriefingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AssistantApplication).container
        // 设置页已关闭清晨简报：Worker 内复核，防旧任务/竞态执行
        if (!container.settingsStore.briefingEnabled.first()) return Result.success()
        return when (val outcome = container.dailyBriefingGenerator.generate()) {
            is GenerationOutcome.NoData -> Result.success()
            is GenerationOutcome.Ok -> {
                Notifier.notifyBriefing(applicationContext, outcome.text)
                Result.success()
            }
            is GenerationOutcome.Failed -> {
                val willRetry = outcome.retryable && runAttemptCount < GENERATION_MAX_RETRY
                Notifier.notifyBriefing(applicationContext, outcome.noticeText(willRetry))
                // 周期任务不能返回 failure()（会取消后续周期）
                if (willRetry) Result.retry() else Result.success()
            }
        }
    }
}
