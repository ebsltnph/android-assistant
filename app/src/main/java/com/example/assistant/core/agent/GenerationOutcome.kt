package com.example.assistant.core.agent

/**
 * 后台生成任务（每日小结 / 清晨简报）的结果（2026-09-23）。
 *
 * 之前的问题：失败与"今天没写日记"都是 null / 静默兜底，**调用方分不清**，
 * 于是既不会重试、也没告诉用户为什么失败（小结只是把原因混进了兜底文本，简报完全静默）。
 * 现在把三种情况显式区分开，Worker 才能做到「输出失败原因 + 15 分钟后自动重试一次」。
 */
sealed interface GenerationOutcome {

    /** 成功（[text] 是已落库、待通知的完整文本） */
    data class Ok(val text: String) : GenerationOutcome

    /** 没有可生成的内容（如今天没写日记）——静默跳过，不算失败 */
    data object NoData : GenerationOutcome

    /**
     * 失败。
     * @param reason 给用户看的失败原因（一句话）
     * @param fallback 兜底文本（**已含原因**，照旧落库 + 通知，界面/日历里立刻能看到出了什么事）
     * @param retryable false = 重试也没用（如"未配置模型"），不必安排自动重试
     */
    data class Failed(
        val reason: String,
        val fallback: String,
        val retryable: Boolean = true
    ) : GenerationOutcome
}

/**
 * 通知/界面里用的失败文案：兜底文本 + 是否会自动重试的说明。
 * @param willRetry 这次失败是否已经安排了「15 分钟后重试一次」
 */
fun GenerationOutcome.Failed.noticeText(willRetry: Boolean): String =
    fallback + if (willRetry) "\n\n（15 分钟后会自动重试一次）" else ""

/** 自动重试的最大次数（一次就够：第一次失败 → 重试一次 → 再失败就等下个周期） */
const val GENERATION_MAX_RETRY = 1
