package com.example.assistant.core.agent

import com.example.assistant.data.db.entity.BufferItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「进行中的事」注入文本渲染器（2026-09-17）。
 *
 * ⚠️ **必须是纯函数**：同样的条目列表必须渲染出逐字节相同的结果。
 * 一旦掺进任何随时间变化的东西（相对天数"3 天前"、当前时间、动态排序），
 * 提示词前缀就会每天/每轮改变 → 厂商提示词缓存永远命中不了（这是本项目的老坑，
 * 见 CLAUDE.md「缓存命中率重构」）。所以：
 *   - 日期一律渲染**绝对日期**（`更新于 2026-09-16`）；相对天数只在界面上算给人看
 *   - 排序按 **id 升序**（不按 updatedAt——一更新就换位置，前缀就变了）
 *   - 截断/省略只取决于条目数据本身，不取决于"现在几点"
 *
 * 上限行为：**一条数据都不删**，只是在注入文本里省略（用户手动管理，不做自动 TTL）。
 */
object BufferRenderer {

    /** 注入块默认字符上限 */
    const val DEFAULT_CHAR_LIMIT = 1200

    /** 注入块默认最大条数 */
    const val DEFAULT_MAX_ITEMS = 15

    private const val OMIT_TEMPLATE = "另有 %d 条更早的状态已省略（可在「进行中的事」页查看）"

    /**
     * 渲染注入文本（不含块标题与固定说明——那两句在 PromptBuilder.BUFFER_BLOCK_HEADER 里）。
     * @return 无可用条目时返回空串（调用方据此不插入块）
     */
    fun render(
        items: List<BufferItemEntity>,
        charLimit: Int = DEFAULT_CHAR_LIMIT,
        maxItems: Int = DEFAULT_MAX_ITEMS
    ): String {
        val active = items
            .filter { it.isActive() && (it.title.isNotBlank() || it.body.isNotBlank()) }
            .sortedBy { it.id }
        if (active.isEmpty()) return ""

        // 条数上限：优先保留"最近更新"的，再按 id 升序排列（顺序稳定）
        val ordered = if (maxItems > 0 && active.size > maxItems) {
            active.sortedWith(
                compareByDescending<BufferItemEntity> { it.updatedAtEpochMillis }.thenByDescending { it.id }
            ).take(maxItems).sortedBy { it.id }
        } else {
            active
        }
        var omitted = active.size - ordered.size

        val sb = StringBuilder()
        var used = 0
        for (item in ordered) {
            val text = renderOne(item)
            val cost = text.length + 1
            // 字符上限：放不下就跳过（第一件无论多长都放，避免渲染成空块）
            if (charLimit > 0 && sb.isNotEmpty() && used + cost > charLimit) {
                omitted++
                continue
            }
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(text)
            used += cost
        }
        if (omitted > 0) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(String.format(Locale.CHINA, OMIT_TEMPLATE, omitted))
        }
        return sb.toString()
    }

    /** 单条渲染：#id=7｜在办｜标题｜更新于 2026-09-16 + 正文 + 相关日记 */
    fun renderOne(item: BufferItemEntity): String {
        val kindLabel = if (item.kind == BufferItemEntity.KIND_WATCH) "关注" else "在办"
        val title = item.title.trim().replace(Regex("\\s*\\n\\s*"), " ")
        val body = item.body.trim().replace(Regex("\n{2,}"), "\n")
        val diary = item.diaryIdList().take(5).joinToString("、") { "#id=$it" }
        return buildString {
            append("#id=").append(item.id).append("｜").append(kindLabel).append("｜")
            append(title).append("｜更新于 ").append(formatDay(item.updatedAtEpochMillis))
            if (body.isNotEmpty()) append("\n").append(body)
            if (diary.isNotEmpty()) append("\n相关日记：").append(diary)
        }
    }

    /** 绝对日期（yyyy-MM-dd，本地时区）——注入文本里只用它，绝不用相对天数 */
    fun formatDay(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))

    /** 界面用：相对天数描述（"3 天前"）——**只给人看，绝不进注入文本** */
    fun relativeDay(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "未知"
        val days = ((now - millis) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "今天"
            days == 1 -> "昨天"
            else -> "$days 天前"
        }
    }
}
