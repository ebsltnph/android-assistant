package com.example.assistant.core.agent

/**
 * 用户意图（sealed class）。
 * 由 IntentRouter 判定，Agent 分发执行；LLM 只负责对话/分类/抽取/总结，
 * 命令类意图（写日记、设提醒等）本地执行。
 */
sealed interface AssistantIntent {
    /** 日常问答，走 LLM 对话 */
    data class Chat(val text: String) : AssistantIntent

    /** 记日记：content 为日记内容，bookName 为 null 时写默认日记本 */
    data class RecordDiary(val content: String, val bookName: String? = null) : AssistantIntent

    /** 设提醒：title + 触发时间（解析失败时由对话追问） */
    data class SetReminder(val title: String, val timeText: String) : AssistantIntent

    /** 识屏任务 */
    data class ScreenSense(val action: ScreenAction) : AssistantIntent

    /** 关注一个新闻类事件（周期搜索监控） */
    data class MonitorEvent(val query: String) : AssistantIntent
}

enum class ScreenAction {
    /** 提取文字 */
    EXTRACT_TEXT,
    /** 翻译 */
    TRANSLATE,
    /** 详细对话（用户给出具体要求） */
    FULL_ANALYSIS
}
