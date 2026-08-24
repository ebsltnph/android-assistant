package com.example.assistant.core.agent.tools

import com.example.assistant.core.agent.JsonExtract
import com.example.assistant.core.network.SearchClient
import kotlinx.serialization.json.JsonObject

/** 联网搜索工具：Tavily 搜索，结果（含来源 URL）回传给模型作答 */
class WebSearchTool(private val searchClient: SearchClient) : AssistantTool {

    override val name = "web_search"
    override val description =
        "web_search(query)：联网搜索实时信息（新闻、价格、天气、版本动态等）。" +
        "args 示例：{\"query\":\"华为 新品 发布\"}。搜索摘要不够时可再用 read_webpage 深读某个来源。"

    override fun actionLabel(args: JsonObject): String {
        val q = JsonExtract.str(args, "query")?.trim()?.take(20) ?: "?"
        return "搜索「$q」"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val query = args.argStr("query")
            ?: return ToolOutcome.Failure("缺少必填参数 query（搜索词）")
        return try {
            val results = searchClient.search(query, maxResults = 5)
            if (results.isEmpty()) {
                // 空结果不算失败：告诉模型换词重试或如实回答
                ToolOutcome.Success("搜索「" + query + "」没有找到相关结果。可换更精确的搜索词重试，或基于已有知识回答并说明信息有限。")
            } else {
                ToolOutcome.Success(buildString {
                    append("搜索「").append(query).append("」的结果：\n")
                    results.forEachIndexed { i, r ->
                        append(i + 1).append(". ").append(r.title)
                        append("\n   来源：").append(r.url)
                        append("\n   ").append(r.content.take(300)).append("\n")
                    }
                    append("\n如需深入了解某个来源的全文，可用 read_webpage 传入对应 url。")
                })
            }
        } catch (e: Exception) {
            ToolOutcome.Failure("搜索失败：" + (e.message ?: "网络错误") + "。可稍后重试或直接回答并说明无法联网。")
        }
    }
}
