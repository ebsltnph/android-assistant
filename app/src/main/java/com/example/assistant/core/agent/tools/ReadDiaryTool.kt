package com.example.assistant.core.agent.tools

import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.db.entity.tagList
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 读日记工具：主模型可以按关键词 / 标签 / 最近天数检索用户日记。
 * 设计取向「简单优先」：个人日记量级小（几百条），一次取回最近 500 条后在内存里过滤，
 * 不写复杂 SQL；过滤条件全部可选，模型按需组合。
 */
class ReadDiaryTool(
    private val diaryRepository: DiaryRepository
) : AssistantTool {

    override val name = "read_diary"
    override val description =
        "read_diary(query?, tags?, days?, limit?)：查询用户的历史日记，返回条目列表（含日期与标签）。" +
        "query 为关键词（正文包含匹配）；tags 为标签数组（多个标签须同时满足）；" +
        "days 表示只看最近 N 天（不传不限时间）；limit 为最多返回几条（默认 8，最大 20）。" +
        "所有参数都可选，全都不传就是最近的日记。" +
        "args 示例：{\"query\":\"项目评审\",\"days\":7} 或 {\"tags\":[\"工作\"],\"limit\":5}"

    override fun actionLabel(args: JsonObject): String {
        val q = args.argStr("query")
        val t = args.argStrList("tags")
        return when {
            q != null -> "查日记「" + q.take(10) + "」"
            t.isNotEmpty() -> "查日记（标签：" + t.joinToString("、") + "）"
            else -> "翻看日记"
        }
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val query = args.argStr("query")
        val tags = args.argStrList("tags")
        val days = args.argInt("days")?.takeIf { it > 0 }
        val limit = (args.argInt("limit") ?: 8).coerceIn(1, 20)
        val book = diaryRepository.defaultBook()
            ?: return ToolOutcome.Failure("默认日记本不存在（异常状态），请告知用户到日记页检查。")
        // 取回候选池后逐条过滤（contains 匹配即可，无需 SQL LIKE 转义）
        val pool = diaryRepository.latestEntries(book.id, MAX_POOL)
        val earliest = days?.let { System.currentTimeMillis() - it * 86_400_000L }
        val matched = pool.asSequence()
            .filter { earliest == null || it.createdAtEpochMillis >= earliest }
            .filter { query == null || it.content.contains(query) }
            .filter { e -> tags.all { t -> e.tagList().any { it.equals(t, ignoreCase = true) } } }
            .take(limit)
            .toList()
        if (matched.isEmpty()) {
            return ToolOutcome.Success(
                "没有找到符合条件的日记（关键词=" + (query ?: "无") + "，标签=" + 
                (if (tags.isEmpty()) "无" else tags.joinToString("、")) + "，范围=" + (days?.toString() ?: "全部") + "天）。可以放宽条件再试一次。"
            )
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val body = matched.joinToString("\n\n") { e ->
            val tagMark = if (e.tags.isBlank()) "" else "  #" + e.tagList().joinToString(" #")
            val text = if (e.content.length > CONTENT_CAP) e.content.take(CONTENT_CAP) + "…" else e.content
            "【" + fmt.format(Date(e.createdAtEpochMillis)) + "】" + tagMark + "\n" + text
        }
        return ToolOutcome.Success("找到 " + matched.size + " 条日记：\n" + body)
    }

    companion object {
        /** 候选池上限：一次最多取回多少条参与过滤（个人日记足够覆盖数年） */
        private const val MAX_POOL = 500

        /** 单条正文回传给模型的最大长度（防长日记挤占上下文） */
        private const val CONTENT_CAP = 200
    }
}