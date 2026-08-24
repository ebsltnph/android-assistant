package com.example.assistant.core.agent.tools

import com.example.assistant.core.network.PageReader
import kotlinx.serialization.json.JsonObject

/**
 * 网页阅读工具：读取指定 URL 的正文全文（Tavily Extract）。
 * 正文截断到约 6000 字防上下文爆炸；失败原因回传，模型可换来源或放弃。
 */
class ReadWebpageTool(private val pageReader: PageReader) : AssistantTool {

    override val name = "read_webpage"
    override val description =
        "read_webpage(url)：读取指定网页的正文全文（适合搜索摘要不够、需要某个页面详细内容时）。" +
        "args 示例：{\"url\":\"https://example.com/article\"}"

    override fun actionLabel(args: JsonObject): String {
        val url = args.argStr("url")?.removePrefix("https://")?.removePrefix("http://") ?: "?"
        return "阅读 " + url.take(30)
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val url = args.argStr("url")
            ?: return ToolOutcome.Failure("缺少必填参数 url（要以 http:// 或 https:// 开头）")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutcome.Failure("url 必须以 http:// 或 https:// 开头，收到的是：" + url.take(100))
        }
        return try {
            val content = pageReader.extract(url)
            val truncated = content.length > MAX_CHARS
            val body = if (truncated) content.take(MAX_CHARS) else content
            ToolOutcome.Success(
                buildString {
                    append("网页 ").append(url).append(" 的正文：\n\n").append(body)
                    if (truncated) append("\n\n（正文过长，已截断到前 ").append(MAX_CHARS).append(" 字）")
                }
            )
        } catch (e: Exception) {
            ToolOutcome.Failure(
                "读取网页失败：" + (e.message ?: "未知错误") +
                    "。可改用其他来源，或基于搜索摘要与已有知识回答。"
            )
        }
    }

    companion object {
        /** 单页正文注入上限：控制上下文预算（多轮工具时尤其重要） */
        private const val MAX_CHARS = 6000
    }
}
