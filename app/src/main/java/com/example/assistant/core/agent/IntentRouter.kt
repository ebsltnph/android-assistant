package com.example.assistant.core.agent

/**
 * 意图路由（纯本地关键词，不联网、不调 LLM）。
 * 架构收敛后只保留识屏直连——其余意图由主模型在工具回路中自主判断执行；
 * 记录类关键词保留一个检测函数供「记录兜底」（模型漏发 write_diary 时静默存原文）使用。
 */
class IntentRouter {

    /** 关键词路由。返回 null = 无直连意图，走对话回路 */
    fun keywordRoute(text: String): AssistantIntent? {
        val t = text.trim()
        if (t.isEmpty()) return null

        // 识屏：唯一保留的本地直连（"翻译"等词也可能出现在聊天里，但"识屏/截屏/屏幕"更明确）
        if (t.containsAny(KEYWORDS_SCREEN)) {
            val action = when {
                t.containsAny(listOf("翻译")) -> ScreenAction.TRANSLATE
                t.containsAny(listOf("对话", "分析", "看看", "描述")) -> ScreenAction.FULL_ANALYSIS
                else -> ScreenAction.EXTRACT_TEXT
            }
            return AssistantIntent.ScreenSense(action)
        }
        return null
    }

    /** 是否像一条记录请求（记录兜底用；不做任何拦截） */
    fun looksLikeDiaryRequest(text: String): Boolean = text.trim().containsAny(KEYWORDS_DIARY)

    private fun String.containsAny(keywords: List<String>): Boolean =
        keywords.any { this.contains(it) }

    companion object {
        private val KEYWORDS_DIARY = listOf("记录", "记一下", "写日记", "记到", "帮我记")
        private val KEYWORDS_SCREEN = listOf("识屏", "截屏", "截个屏", "这个屏幕", "翻译这个")
    }
}