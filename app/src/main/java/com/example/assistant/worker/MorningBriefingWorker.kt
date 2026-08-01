package com.example.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.AssistantApplication
import com.example.assistant.core.notification.Notifier

/**
 * 清晨简报 Worker（默认 7:30，设置页可改）：
 * 今日提醒 + 昨日小结 → LLM 组装 → 通知。
 */
class MorningBriefingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AssistantApplication).container
        val briefing = container.dailyBriefingGenerator.generate()
        Notifier.notifyBriefing(applicationContext, briefing)
        return Result.success()
    }
}
