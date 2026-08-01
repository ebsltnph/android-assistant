package com.example.assistant.core.network

/**
 * 搜索结果条目：清洗后的内容摘要，可直接喂给 LLM 阅读理解。
 */
data class SearchResult(
    val title: String,
    val url: String,
    /** 正文摘要（LLM 消费用） */
    val content: String,
    val score: Double
)

/**
 * 搜索接口：对话搜索与事件监控共用。
 * 实现：TavilySearchClient；换其他搜索 API 时实现本接口即可（见 CLAUDE.md 计划）。
 */
interface SearchClient {
    /**
     * 搜索并返回结果摘要。
     * @param topic "general" 通用 | "news" 新闻
     * @param timeRange 时间过滤 "day"/"week"/"month"/"year"，null = 不限
     * @param includeDomains 只搜这些域名（逗号分隔），null = 不限来源
     */
    suspend fun search(
        query: String,
        topic: String = "general",
        timeRange: String? = null,
        maxResults: Int = 5,
        includeDomains: String? = null
    ): List<SearchResult>
}
