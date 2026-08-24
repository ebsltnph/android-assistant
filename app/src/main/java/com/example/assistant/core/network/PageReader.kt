package com.example.assistant.core.network

/**
 * 网页正文读取接口：让模型能深读指定 URL 的全文（对话工具 read_webpage 用）。
 * 实现：TavilyExtractClient（Tavily /extract 端点，与搜索同一服务/Key）。
 */
interface PageReader {
    /**
     * 抓取并清洗网页正文，返回纯文本。
     * @throws Exception 页面不可达、无正文可提取等（错误信息会回传给模型）
     */
    suspend fun extract(url: String): String
}
