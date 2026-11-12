package com.example.assistant.core.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 13 个内置工具的 JSON Schema。
 *
 * 原生 Function Calling 把参数约束放进 API 的 tools 字段；描述只保留“何时用/限制”，
 * 参数细节放在 property description 里。**不启用 strict**：现有多个工具的参数是可选的，
 * 且 DeepSeek 思考模式对 tool_choice 有限制，strict 并非必要。
 *
 * 工具名与参数名是稳定协议；改动会改变 schemaSignature，触发一次上下文窗口回落。
 */
internal object ToolSchemas {

    fun of(toolName: String): JsonObject = when (toolName) {
        "web_search" -> schema(
            required = listOf("query"),
            "query" to string("搜索词，尽量精确；支持搜索引擎常见语法")
        )

        "read_webpage" -> schema(
            required = listOf("url"),
            "url" to string("要读取完整正文的网页 URL（http/https）")
        )

        "read_diary" -> schema(
            required = emptyList(),
            "id" to integer("日记编号；传了就只返回这一条且完整正文不截断"),
            "query" to string("正文关键词（包含匹配）"),
            "tags" to stringArray("标签数组；多个标签须同时满足"),
            "days" to integer("只看最近 N 天", minimum = 1),
            "limit" to integer("最多返回几条（默认 8，最大 20）", minimum = 1, maximum = 20)
        )

        "read_diary_image" -> schema(
            required = listOf("id"),
            "id" to integer("带图片的日记编号（来自 read_diary 的 #id=）"),
            "image_ids" to integerArray("只想看其中几张时传 #img= 编号数组；不传 = 全部（最多 8 张）")
        )

        "update_diary" -> schema(
            required = listOf("id", "old_content"),
            "id" to integer("要修改/删除的日记编号（来自 read_diary 的 #id=）"),
            "old_content" to string("该条日记当前完整正文，必须与 read_diary(id=…) 返回一致；不修改正文也要回填"),
            "content" to string("修改后的完整正文；与 old_content 相同 = 正文不变"),
            "tags" to stringArray("覆盖标签（0-3 个，只能从可用日记标签里选；空数组 = 清空）"),
            "add_image_paths" to stringArray("要新增的图片本机路径（只接受任务里给出的真实路径）"),
            "remove_image_ids" to integerArray("要删除的图片编号（#img=）"),
            "delete" to boolean("true = 删除整条日记；old_content 仍必填，不可恢复")
        )

        "list_reminders" -> schema(
            required = emptyList(),
            "scope" to string("pending（默认，未触发）或 all（含已触发/已取消）", enumValues = listOf("pending", "all")),
            "limit" to integer("最多返回几条（默认 10，最大 30）", minimum = 1, maximum = 30)
        )

        "speak" -> schema(
            required = listOf("text"),
            "text" to string("提炼后的口语短文：去 markdown/公式/代码，一两句话，不要照搬回复全文")
        )

        "set_reminder" -> schema(
            required = listOf("title"),
            "title" to string("提醒内容（简短）"),
            "day_offset" to integer("今天起第几天（0=今天，1=明天）", minimum = 0),
            "hour" to integer("小时（0-23）", minimum = 0, maximum = 23),
            "minute" to integer("分钟（0-59）", minimum = 0, maximum = 59),
            "offset_minutes" to integer("相对现在多少分钟后触发；与 day_offset/hour/minute 二选一", minimum = 1),
            "repeat" to string("重复规则：daily / weekly", enumValues = listOf("daily", "weekly")),
            "weekday" to integer("repeat=weekly 时必填：周一=1 … 周日=7", minimum = 1, maximum = 7)
        )

        "write_memory" -> schema(
            required = listOf("fact"),
            "fact" to string("值得长期记住的用户信息（称呼/稳定习惯/重要背景；一次性琐事不要写）"),
            "category" to string("记忆分类，如 preference / background / general"),
            "importance" to integer("重要性 1-10；只有 ≥7 才会真正保存", minimum = 1, maximum = 10)
        )

        "write_diary" -> schema(
            required = listOf("content"),
            "content" to string("整理后的简洁日记正文（一两句话，不要对话体）"),
            "tags" to stringArray("0-3 个标签，只能从易变上下文给出的可用日记标签里选"),
            "image_paths" to stringArray("仅当任务说明明确给出了已保存的图片本机路径时才传")
        )

        "monitor_event" -> schema(
            required = listOf("display_name", "search_query"),
            "display_name" to string("监控事项的简短名称"),
            "search_query" to string("给搜索引擎的查询词"),
            "condition_keywords" to string("命中判断关键词（逗号分隔）；不传则用模型判断"),
            "custom_rule" to string("更复杂的命中规则（需要模型理解时使用）"),
            "include_domains" to string("限定来源域名（逗号分隔），可留空")
        )

        "screen_sense" -> schema(
            required = emptyList(),
            "action" to string(
                "extract=提取文字（默认）/ translate=翻译 / analysis=按要求分析",
                enumValues = listOf("extract", "translate", "analysis")
            )
        )

        "update_buffer" -> schema(
            required = emptyList(),
            "items" to objectArray(
                "新增/改写「进行中的事」条目数组；未变化的条目不要重写",
                itemSchema(
                    required = listOf("title", "body"),
                    "id" to integer("要改写的条目编号（注入块里的 #id=）；不传 = 新增"),
                    "kind" to string("progress=在办事项；watch=需要盯一段时间", enumValues = listOf("progress", "watch")),
                    "title" to string("事项名（2-8 个字）"),
                    "body" to string("当前状态，一两句话、100 字以内；不要把过程细节抄进来"),
                    "diary_ids" to integerArray("对应的日记 #id=（细节需要时再去 read_diary）")
                )
            ),
            "archive_ids" to integerArray("已完成/不再需要盯的条目编号"),
            "delete_ids" to integerArray("确认没用的条目编号"),
            "noop" to boolean("这批对话确实没有可记录内容时传 true，表示已看过、无需记录")
        )

        else -> schema(required = emptyList())
    }

    // ---------- JSON Schema 小工具 ----------

    private fun schema(
        required: List<String>,
        vararg properties: Pair<String, JsonObject>
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (name, prop) -> put(name, prop) }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
        put("additionalProperties", false)
    }

    private fun string(description: String, enumValues: List<String>? = null): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
            if (!enumValues.isNullOrEmpty()) {
                putJsonArray("enum") { enumValues.forEach { add(JsonPrimitive(it)) } }
            }
        }

    private fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", JsonPrimitive(it)) }
            maximum?.let { put("maximum", JsonPrimitive(it)) }
        }

    private fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun stringArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    private fun integerArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "integer") }
    }

    private fun objectArray(description: String, items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    /** 数组元素的 object schema（与顶层 schema 同规则） */
    private fun itemSchema(
        required: List<String>,
        vararg properties: Pair<String, JsonObject>
    ): JsonObject = schema(required, *properties)
}
