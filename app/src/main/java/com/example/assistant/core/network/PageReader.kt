package com.example.assistant.core.network

/**
 * 网页正文读取接口：让模型能深读指定 URL 的全文（对话工具 read_webpage 用）。
 *
 * 搜索与抓取是两项独立能力：实现只访问设置里为“抓取”选择的提供商。
 */
data class ExtractedPage(
    val title: String?,
    val content: String,
    val provider: WebProvider
)

interface PageReader {
    val provider: WebProvider

    /**
     * 抓取并清洗网页正文。
     * @throws WebApiException 页面不可达、无正文可提取等（错误信息会回传给模型）
     */
    suspend fun extract(url: String): ExtractedPage
}
