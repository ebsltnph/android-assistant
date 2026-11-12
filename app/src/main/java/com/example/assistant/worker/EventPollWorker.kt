package com.example.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.AssistantApplication
import com.example.assistant.core.network.WebSearchRequest
import com.example.assistant.core.notification.Notifier
import com.example.assistant.core.storage.WebAccessStatus
import com.example.assistant.core.storage.WebAccessStatusStore

/**
 * 新闻事件轮询 Worker（周期 6 小时，由 AssistantApplication 调度）：
 * 对每个启用的监控事件按设置选择的供应商搜索（news 主题、近一周）→ 判断命中 → 通知（24h 去重）。
 * 各事件按 pollHours 跳过未到期项；免打扰时段内命中不通知（顺延下周期）。
 * 搜索失败不抛异常（Worker 下次周期再试），但会记录状态，不再伪装成“没有新闻”。
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

            val provider = container.webAccessRegistry.currentSearchProvider()
            var searchSucceeded = false
            val results = try {
                val found = container.webAccessRegistry.currentSearchClient().search(
                    WebSearchRequest(
                        query = event.searchQuery,
                        topic = "news",
                        timeRange = "week",
                        maxResults = 5,
                        includeDomains = event.includeDomains.ifBlank { null }
                    )
                )
                container.webAccessStatusStore.record(
                    WebAccessStatus(
                        timestamp = System.currentTimeMillis(),
                        provider = provider,
                        operation = WebAccessStatusStore.OP_EVENT_SEARCH,
                        success = true,
                        resultCount = found.size,
                        message = if (found.isEmpty()) "没有找到相关结果" else "「${event.displayName}」"
                    )
                )
                searchSucceeded = true
                found
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                container.webAccessStatusStore.record(
                    WebAccessStatus(
                        timestamp = System.currentTimeMillis(),
                        provider = provider,
                        operation = WebAccessStatusStore.OP_EVENT_SEARCH,
                        success = false,
                        resultCount = null,
                        message = "「${event.displayName}」：" + (e.message ?: e.javaClass.simpleName)
                    )
                )
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

            if (hit) {
                val first = results.firstOrNull()
                // 触发历史**每次命中都落库**（App 内详情可看，含免打扰/冷却期间被压下的命中）
                container.eventRepository.addHit(
                    eventId = event.id,
                    title = first?.title ?: event.displayName,
                    url = first?.url.orEmpty(),
                    content = first?.content ?: event.displayName
                )
                // 通知只发一次（24h 冷却 + 免打扰不打扰）
                if (now - event.lastNotifiedAtEpochMillis > NOTIFY_COOLDOWN_MS &&
                    !container.quietHours.isInQuietWindow()
                ) {
                    val title = "📰 ${event.displayName}"
                    val body = first?.let {
                        "${it.title}\n${it.url}"
                    } ?: "有新动态，点开看看"
                    Notifier.notifyEventHit(applicationContext, event.id, title, body)
                    container.eventRepository.markNotified(event.id, now)
                }
            }
            // 搜索失败不推进 lastChecked：否则默认 24h 的 pollHours 会把一次瞬时限流
            // 变成“一整天不再检查”。失败的 6 小时周期会自然重试。
            if (searchSucceeded) {
                container.eventRepository.markChecked(event.id, now)
            }
        }
        return Result.success()
    }

    companion object {
        /** 同一事件通知冷却：24 小时内不重复通知 */
        private const val NOTIFY_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    }
}
