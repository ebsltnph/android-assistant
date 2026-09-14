package com.example.assistant.core.agent.tools

import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.db.entity.tagList
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 改/删日记工具（2026-09-11 新增，同日加装"核对原正文"保险）。
 *
 * 为什么需要 id：用户说"把刚才那条日记改成…"时，模型得先知道是哪条——
 * 因此 `read_diary` 的返回里每条都带 `#id=`，本工具按 id 操作。
 *
 * 为什么要 `old_content` 核对：只给 id 的话，模型一旦记错编号就会**默默改掉另一条日记**
 * （用户实测：模型把 id 记串，改完自己都发现"结果前后不一致"，反复读回确认也理不清）。
 * 现在必须同时给出该条**当前正文**，系统与库中内容核对一致才允许写；不一致直接拒绝并
 * 要求重新 read_diary —— 改错条目的代价远高于多读一次。
 *
 * 只处理模型传了的字段（tags 传空数组 = 清空标签）；删除会连图片文件一起清掉。
 */
class UpdateDiaryTool(
    private val diaryRepository: DiaryRepository,
    /** 可用标签词汇表（与 write_diary 同一份，随设置变化，故用惰性提供者） */
    private val availableTags: suspend () -> List<String>
) : AssistantTool {

    override val name = "update_diary"
    override val description =
        "update_diary(id, old_content, content, tags?, delete?)：修改或删除**已存在**的一条日记。" +
        "id 必填，是 read_diary 结果里的 #id= 那串数字（不要自己编）；" +
        "old_content 必填，是这条日记**当前的正文**，必须与 read_diary(id=编号) 返回的完整正文一致" +
        "（系统逐字核对，不一致会直接拒绝并提示你重读——这是防止改错条目的保险）。" +
        "content 必填（delete=true 时除外），是改后的完整正文；" +
        "只改标签时把 old_content 原样再填进 content（两者相同 = 正文不变）。" +
        "tags 覆盖标签（最多 3 个，只能从工具手册给出的可用日记标签里选；传空数组 = 清空标签）；" +
        "delete=true 表示删除整条（此时 content 可省，old_content 仍必填，不可恢复）。" +
        "流程：read_diary 找到目标 → read_diary(id=编号) 取完整正文 → 本工具。" +
        "args 示例：{\"id\":12,\"old_content\":\"…原正文…\",\"content\":\"改后的正文\"} 或 " +
        "{\"id\":12,\"old_content\":\"…原正文…\",\"content\":\"…原正文…\",\"tags\":[\"工作\"]} 或 " +
        "{\"id\":12,\"old_content\":\"…原正文…\",\"delete\":true}"

    override fun actionLabel(args: JsonObject): String {
        val id = args.argLong("id")
        return if (args.argBool("delete") == true) "删除日记 #$id" else "修改日记 #$id"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val id = args.argLong("id")
            ?: return ToolOutcome.Failure(
                "缺少必填参数 id（日记条目的数字编号）。请先调 read_diary 查到要改的那条，" +
                    "用它返回里的 #id=编号再调用我。"
            )
        val entry = diaryRepository.entryById(id)
            ?: return ToolOutcome.Failure(
                "没有 #id=$id 的日记条目（可能已被删除，或编号记错了）。" +
                    "请先调 read_diary 重新确认编号，再调用我。"
            )

        // ---- 保险：必须复述该条当前正文，核对通过才允许动 ----
        val actual = entry.content
        val old = args.argStr("old_content")
            ?: return ToolOutcome.Failure(
                "缺少必填参数 old_content（#id=$id 这条日记的当前正文）。" +
                    "请先调 read_diary(id=$id) 读回它的完整正文，原样填进 old_content，再调用我。"
            )
        if (!contentMatches(old, actual)) {
            return ToolOutcome.Failure(
                "old_content 与 #id=$id 的实际正文不一致，**已拒绝修改**（防止改错条目）。" +
                    "可能原因：编号记错了，或者正文是凭印象写的。" +
                    "请先调 read_diary(id=$id) 读回这条的正文、原样复制到 old_content 再调用我。" +
                    "参考：这条日记的时间是 " + dateLabel(entry.createdAtEpochMillis) + "。"
            )
        }

        // ---- 删除 ----
        if (args.argBool("delete") == true) {
            val preview = actual.take(30)
            diaryRepository.imagesFor(id).forEach { img ->
                try {
                    File(img.path).delete()
                } catch (_: Exception) {
                }
            }
            diaryRepository.deleteEntry(id)
            return ToolOutcome.Success(
                "已删除日记 #$id（原内容开头：$preview…）。这是不可恢复的操作，" +
                    "如果用户并不是要删除，请如实说明已删除。不要在回复里重复整篇内容。"
            )
        }

        // ---- 修改：content 必填（只改标签就把 old_content 原样填回来） ----
        val content = args.argStr("content")
            ?: return ToolOutcome.Failure(
                "缺少必填参数 content（改后的完整正文）。只改标签时，请把 old_content 原样再填进 content" +
                    "（两者相同 = 正文不变，系统不会改动正文）。"
            )
        val tagsProvided = args.hasArg("tags")
        // 与 old_content 完全相同 ⇒ 模型只是回填，视为"正文不改"（避免把截断读回的文本误写成新正文）
        val contentUnchanged = norm(content) == norm(old)

        val changed = mutableListOf<String>()
        if (!contentUnchanged) {
            diaryRepository.updateEntryContent(id, content)
            changed += "正文"
        }
        var note = ""
        var tags = entry.tagList()
        if (tagsProvided) {
            val vocab = availableTags()
            val requested = args.argStrList("tags")
            tags = requested.filter { it in vocab }.distinct().take(3)
            val rejected = requested.filter { it !in vocab }
            diaryRepository.updateEntryTags(id, tags)
            changed += if (tags.isEmpty()) "标签（已清空）" else "标签（" + tags.joinToString("、") + "）"
            if (rejected.isNotEmpty()) {
                note = "；以下标签不在可用词汇表中已忽略：" + rejected.joinToString("、")
            }
        }

        if (changed.isEmpty()) {
            return ToolOutcome.Success(
                "日记 #$id 与你要改的内容完全一致，没有需要写入的改动。\n" + render(id, entry.createdAtEpochMillis, tags, actual)
            )
        }
        val finalContent = if (contentUnchanged) actual else content
        return ToolOutcome.Success(
            "已更新日记 #$id（" + changed.joinToString("、") + "）。它现在的样子是：\n" +
                render(id, entry.createdAtEpochMillis, tags, finalContent) + note +
                "\n（以上已是数据库中的最新内容，不需要再读一次；但不要在回复里向用户重复整篇正文）"
        )
    }

    /** 回传改写后的条目，让模型立刻看到结果（省掉一次 read_diary，也就不会读到旧结果） */
    private fun render(id: Long, createdAt: Long, tags: List<String>, content: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val head = "#id=" + id + "｜" + fmt.format(Date(createdAt)) +
            (if (tags.isEmpty()) "" else "｜标签：" + tags.joinToString("、"))
        val text = if (content.length > FEEDBACK_CAP) content.take(FEEDBACK_CAP) + "…（已截断显示）" else content
        return head + "\n" + text
    }

    private fun dateLabel(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    companion object {
        /**
         * 核对用的最短前缀长度：read_diary 列表里长正文会被截断（600 字），
         * 模型只能复制到截断处，因此允许"库中正文以它开头"；但太短的前缀不足以证明读对了条目。
         */
        private const val MIN_PREFIX_MATCH = 30

        /** 回传新正文的最大长度（模型看得到即可，不必整篇塞回上下文） */
        private const val FEEDBACK_CAP = 300

        /** 归一化：去掉全部空白字符（模型复制正文时经常丢换行/多空格，逐字节比较太脆） */
        internal fun norm(s: String): String = s.filterNot { it.isWhitespace() }

        /**
         * 模型给的正文是否与该条实际正文对得上：
         * 完全一致，或者它是实际正文的前缀（≥ MIN_PREFIX_MATCH 字，对应 read_diary 的截断）。
         */
        internal fun contentMatches(provided: String, actual: String): Boolean {
            val p = norm(provided)
            if (p.isEmpty()) return false
            val a = norm(actual)
            if (p == a) return true
            return p.length >= MIN_PREFIX_MATCH && a.startsWith(p)
        }
    }
}
