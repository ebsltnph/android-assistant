package com.example.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.AssistantApplication
import com.example.assistant.core.notification.Notifier

/**
 * 新闻事件轮询 Worker（周期 6 小时，由 AssistantApplication 调度）：
 * 对每个启用的监控事件搜索 Tavily（news 主题、近一周）→ 判断命中 → 通知（24h 去重）。
 * 各事件按 pollHours 跳过未到期项；免打扰时段内命中不通知（顺延下周期）。
 * 全部失败都不抛异常（Worker 下次周期再试）。
 */
class EventPollWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AssistantApplication).container
        val events = container.eventRepository.enabledEvents()
        if (events.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        for (event in events) {
            // pollHours 过滤：未到轮询间隔的跳过
            if (now - event.lastCheckedAtEpochMillis < event.pollHours * 3600_000L) continue

            val results = try {
                container.searchClient.search(
                    event.searchQuery,
                    topic = "news",
                    timeRange = "week",
                    maxResults = 5,
                    includeDomains = event.includeDomains.ifBlank { null }
                )
            } catch (e: Exception) {
                emptyList()
            }

            // 命中判断：有自定义规则 → 必须 LLM 判断（规则需要模型理解）；
            // 只有条件关键词 → 本地关键词判断；都没有 → LLM 判断（失败按未命中）
            val hit = if (results.isNotEmpty()) {
                if (event.customRule.isNotBlank() || event.conditionKeywords.isBlank()) {
                    container.eventHitJudge.judge(event, results)?.hit ?: false
                } else {
                    container.eventHitJudge.keywordHit(event, results)
                }
            } else false

            if (hit && now - event.lastNotifiedAtEpochMillis > NOTIFY_COOLDOWN_MS &&
                !container.quietHours.isInQuietWindow()
            ) {
                val title = "📰 ${event.displayName}"
                val body = results.firstOrNull()?.let {
                    "${it.title}\n${it.url}"
                } ?: "有新动态，点开看看"
                Notifier.notifyReminder(applicationContext, title, body, event.id.toInt())
                container.eventRepository.markNotified(event.id, now)
            }
            container.eventRepository.markChecked(event.id, now)
        }
        return Result.success()
    }

    companion object {
        /** 同一事件通知冷却：24 小时内不重复通知 */
        private const val NOTIFY_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    }
}
