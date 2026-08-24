package com.example.assistant.core.agent.tools

import com.example.assistant.data.repo.EventRepository
import kotlinx.serialization.json.JsonObject

/**
 * 创建事件监控工具：模型直接给出监控配置入库（取代独立的 EventExtractor 调用）。
 * 原有的本地域名正则兜底保留：模型没给 include_domains 时从各参数文本里提取。
 */
class MonitorEventTool(private val eventRepository: EventRepository) : AssistantTool {

    override val name = "monitor_event"
    override val description =
        "monitor_event(display_name, search_query, condition_keywords?, custom_rule?, include_domains?)：" +
        "创建对某话题/事件的持续监控（后台周期搜索，命中时通知用户）。用户说「关注/帮我留意/盯…」时使用。" +
        "args 示例：{\"display_name\":\"DeepSeek更新\",\"search_query\":\"DeepSeek 更新 文档\"," +
        "\"condition_keywords\":\"DeepSeek\",\"custom_rule\":\"只关注官方更新日志\",\"include_domains\":\"api-docs.deepseek.com\"}"

    override fun actionLabel(args: JsonObject): String {
        val n = args.argStr("display_name")?.take(14) ?: "?"
        return "关注「$n」"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val displayName = args.argStr("display_name")
            ?: return ToolOutcome.Failure("缺少必填参数 display_name（监控事项的简短名称）")
        val searchQuery = args.argStr("search_query")
            ?: return ToolOutcome.Failure("缺少必填参数 search_query（给搜索引擎的搜索词）")
        var domains = args.argStr("include_domains").orEmpty()
        if (domains.isBlank()) {
            // 模型没提取出域名时：本地正则从全部参数文本兜底提取（不依赖模型）
            domains = extractDomainsLocally(listOfNotNull(
                args.argStr("search_query"), args.argStr("custom_rule"), args.argStr("display_name")
            ).joinToString(" "))
        }
        eventRepository.add(
            displayName = displayName,
            searchQuery = searchQuery,
            conditionKeywords = args.argStr("condition_keywords").orEmpty(),
            customRule = args.argStr("custom_rule").orEmpty(),
            includeDomains = domains
        )
        return ToolOutcome.Success(
            buildString {
                append("已开始关注「").append(displayName).append("」（搜索词：").append(searchQuery).append("）")
                append("，来源：").append(domains.ifBlank { "不限" })
                args.argStr("custom_rule")?.let { append("；判断规则：").append(it) }
                append("。有重要动态会通知用户。")
            }
        )
    }

    /** 从文本中本地提取域名（带协议或裸域名均可），逗号分隔返回 */
    private fun extractDomainsLocally(text: String): String {
        val withProtocol = Regex("https?://([a-zA-Z0-9.-]+)")
        val bare = Regex("(?:^|\\s)([a-zA-Z0-9][a-zA-Z0-9-]*\\.[a-zA-Z]{2,}(?:/[^\\s]*)?)")
        val domains = mutableListOf<String>()
        withProtocol.findAll(text).forEach { m -> domains.add(m.groupValues[1].removePrefix("www.")) }
        if (domains.isEmpty()) {
            bare.findAll(text).forEach { m ->
                domains.add(m.groupValues[1].removePrefix("www.").substringBefore("/"))
            }
        }
        return domains.distinct().joinToString(",")
    }
}