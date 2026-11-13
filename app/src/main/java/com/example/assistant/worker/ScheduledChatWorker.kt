package com.example.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.AssistantApplication
import com.example.assistant.core.notification.Notifier
import com.example.assistant.core.scheduled.ScheduledChatScheduler
import com.example.assistant.feature.chat.ScheduledChatResult

/**
 * 定时对话执行器（2026-11-14）：
 * 从配置里取最新提示词（不在闹钟里快照，用户改完下一次就生效），
 * 交给 ChatViewModel 的**正常对话回路**执行，完成后推送通知。
 *
 * 下一次闹钟已由 ScheduledChatReceiver 在入队前排好，本 Worker 失败不会断掉周期。
 * 用户正在长回复时 ChatViewModel 会等待 3 分钟；仍忙则 Result.retry()（15 分钟后一次）。
 */
class ScheduledChatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(ScheduledChatScheduler.KEY_SCHEDULE_ID, -1L)
        if (id < 0) return Result.success()

        val container = (applicationContext as AssistantApplication).container
        val schedule = container.scheduledChatStore.byId(id) ?: return Result.success()
        // 排队期间被停用/删除：不再执行（闹钟也已由管理入口取消）
        if (!schedule.enabled) return Result.success()

        container.scheduledChatStore.markTriggered(id)

        return when (val result = container.chatViewModel.runScheduledConversation(schedule)) {
            is ScheduledChatResult.Success -> {
                val text = result.answer.trim()
                if (text.isBlank()) {
                    Notifier.notifyScheduledChat(
                        applicationContext, schedule.id, schedule.customName,
                        "这次定时对话没有生成内容，点开聊聊看", failed = true
                    )
                } else {
                    Notifier.notifyScheduledChat(
                        applicationContext, schedule.id, schedule.customName, text,
                        failed = looksLikeError(text)
                    )
                }
                Result.success()
            }
            is ScheduledChatResult.Busy -> {
                // 只自动重试一次：用户正在对话时不要把定时消息硬塞进去
                if (runAttemptCount < 1) Result.retry() else {
                    Notifier.notifyScheduledChat(
                        applicationContext, schedule.id, schedule.customName,
                        "你正在对话中，这次定时对话已跳过，下次照常触发", failed = true
                    )
                    Result.success()
                }
            }
            is ScheduledChatResult.Failed -> {
                Notifier.notifyScheduledChat(
                    applicationContext, schedule.id, schedule.customName,
                    "执行失败：${result.message}", failed = true
                )
                Result.success()
            }
        }
    }

    /** 与聊天页一致的失败口径：模型未配置/流式中断都会以这些前缀落进正文 */
    private fun looksLikeError(text: String): Boolean =
        text.startsWith("⚠️") || text.contains("[出错：")
}
