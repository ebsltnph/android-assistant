package com.example.assistant.core.agent.tools

import com.example.assistant.data.db.entity.ReminderEntity
import com.example.assistant.data.repo.ReminderRepository
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 读提醒工具（2026-09-11 新增）：主模型可以查看用户的提醒列表。
 * 回答"我有哪些提醒""明天有什么安排"这类问题，也给后续"改/删提醒"提供 id。
 *
 * scope：pending（默认）= 还没触发的；all = 含已触发/已取消（带状态）。
 */
class ListRemindersTool(
    private val reminderRepository: ReminderRepository
) : AssistantTool {

    override val name = "list_reminders"
    override val description =
        "list_reminders(scope?, limit?)：读取用户的提醒列表，返回每条的时间、重复规则、状态与 #id。" +
        "scope 可选：\"pending\"（默认，只看还没触发的）或 \"all\"（含已触发/已取消）；" +
        "limit 默认 10、最大 30。用户问\"我有哪些提醒/明天有什么安排\"时用它；" +
        "要改或删某条提醒时也先用它拿到 #id。" +
        "args 示例：{} 或 {\"scope\":\"all\",\"limit\":20}"

    override fun actionLabel(args: JsonObject): String =
        if (args.argStr("scope")?.lowercase() == "all") "查看提醒（全部）" else "查看提醒"

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val scope = args.argStr("scope")?.lowercase() ?: "pending"
        val limit = (args.argInt("limit") ?: 10).coerceIn(1, 30)
        val now = System.currentTimeMillis()
        val list = (if (scope == "all") reminderRepository.all() else reminderRepository.pending(now))
            .sortedBy { it.triggerAtEpochMillis }
            .take(limit)
        if (list.isEmpty()) {
            return ToolOutcome.Success(
                if (scope == "all") "用户目前没有任何提醒记录。"
                else "用户目前没有待触发的提醒（截至现在）。如需看历史记录可以用 scope=\"all\" 再查一次。"
            )
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val body = list.joinToString("\n") { r -> formatOne(r, fmt, now, scope == "all") }
        return ToolOutcome.Success("共 ${list.size} 条提醒：\n$body")
    }

    private fun formatOne(r: ReminderEntity, fmt: SimpleDateFormat, now: Long, withStatus: Boolean): String {
        val repeat = when (r.repeatRule) {
            "daily" -> "每天"
            "weekly" -> "每周"
            else -> "一次性"
        }
        val status = when {
            r.status == "pending" && r.triggerAtEpochMillis >= now -> "待触发"
            r.status == "pending" -> "已过期未触发"
            r.status == "fired" -> "已触发"
            r.status == "cancelled" -> "已取消"
            else -> r.status
        }
        return buildString {
            append("#").append(r.id).append(" ").append(fmt.format(Date(r.triggerAtEpochMillis)))
            append(" · ").append(repeat)
            if (withStatus) append(" · ").append(status)
            append(" 「").append(r.title).append("」")
        }
    }
}
