package com.example.assistant.core.network

/**
 * 联网搜索/网页抓取的提供商。
 *
 * 搜索与抓取的选择彼此独立（设置里两个下拉框），选中哪个就只用哪个，
 * 失败不会静默切换到另一家——避免把查询和凭据发给用户没选择的服务，
 * 也避免在用户不知情时产生费用。
 */
enum class WebProvider(
    val storageValue: String,
    val displayName: String,
    /** 选择器里的简短说明（同时说明计费/限制） */
    val searchHint: String,
    val fetchHint: String
) {
    TAVILY(
        storageValue = "tavily",
        displayName = "Tavily",
        searchHint = "默认；不填 Key 使用 keyless 免费模式（有限流）",
        fetchHint = "默认；与搜索共用 Tavily Key/keyless"
    ),
    DEEPSEEK(
        storageValue = "deepseek",
        displayName = "DeepSeek",
        searchHint = "DeepSeek 官方搜索；按成功请求计费（¥5/千次）",
        fetchHint = "DeepSeek 官方抓取；按成功请求计费（¥5/千次）"
    );

    val isPaid: Boolean get() = this == DEEPSEEK

    companion object {
        /** 未知/旧值一律回退 Tavily（默认行为），不因配置损坏改成付费服务 */
        fun fromStorage(value: String?): WebProvider =
            entries.firstOrNull { it.storageValue == value } ?: TAVILY
    }
}

/**
 * 一次搜索请求（两家提供商共用的语义字段）。
 *
 * topic/timeRange/includeDomains 是现有对话与事件监控已经在用的语义；
 * 适配器负责把它们映射成各家支持的字段或查询语法。
 */
data class WebSearchRequest(
    val query: String,
    /** "general" | "news"（DeepSeek 没有该字段，不会发送未知参数） */
    val topic: String = "general",
    /** "day" | "week" | "month" | "year"，null = 不限 */
    val timeRange: String? = null,
    val maxResults: Int = 5,
    /** 只搜索这些域名（逗号分隔），null/空 = 不限 */
    val includeDomains: String? = null
)

/**
 * 搜索结果条目：清洗后的摘要，可直接喂给 LLM 阅读理解。
 * [content] 保持旧字段名，兼容工具与事件监控；[provider] 便于诊断。
 */
data class SearchResult(
    val title: String,
    val url: String,
    /** 正文摘要（LLM 消费用） */
    val content: String,
    val score: Double = 0.0,
    /** 发布日期（DeepSeek 可能返回；Tavily 当前通常没有） */
    val publishedAt: String? = null,
    val provider: WebProvider = WebProvider.TAVILY
)

/**
 * 搜索接口：实现必须遵守“只访问自己选定的提供商”，不得内部回退到别家。
 */
interface SearchClient {
    val provider: WebProvider

    suspend fun search(request: WebSearchRequest): List<SearchResult>
}
