package com.example.assistant.core.agent

/**
 * 本地直连意图（sealed）。
 * 主模型统一调度架构下只剩识屏：关键词命中即本地发起（瞬时、离线可用、无子 LLM 调用）；
 * 其余能力（提醒/记录/记忆/监控/搜索/读网页）全部由主聊天模型经工具回路完成，不再走意图枚举。
 */
sealed interface AssistantIntent {
    /** 识屏任务 */
    data class ScreenSense(val action: ScreenAction) : AssistantIntent
}

enum class ScreenAction {
    /** 提取文字 */
    EXTRACT_TEXT,
    /** 翻译 */
    TRANSLATE,
    /** 详细对话（用户给出具体要求） */
    FULL_ANALYSIS
}