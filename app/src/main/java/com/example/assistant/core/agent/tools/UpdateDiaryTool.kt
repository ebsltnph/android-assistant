package com.example.assistant.core.agent.tools

import com.example.assistant.data.repo.DiaryRepository
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 改日记工具（2026-09-11 新增）：主模型可以修改**已有**日记的正文 / 标签，或删除整条。
 *
 * 为什么需要 id：用户说"把刚才那条日记改成…"时，模型得先知道是哪条——
 * 因此 `read_diary` 的返回里每条都带 `#id`，本工具按 id 操作。
 * 只处理模型传了的字段（tags 传空数组 = 清空标签）；删除会连图片文件一起清掉。
 */
class UpdateDiaryTool(
    private val diaryRepository: DiaryRepository,
    /** 可用标签词汇表（与 write_diary 同一份，随设置变化，故用惰性提供者） */
    private val availableTags: suspend () -> List<String>
) : AssistantTool {

    override val name = "update_diary"
    override val description =
        "update_diary(id, content?, tags?, delete?)：修改或删除**已存在**的一条日记。" +
        "id 是日记条目的数字编号，只能从 read_diary 的返回结果里取（不要自己编）。" +
        "content 覆盖正文；tags 覆盖标签（最多 3 个，只能从工具手册给出的可用日记标签里选；传空数组 = 清空标签）；" +
        "delete=true 表示删除整条（此时忽略 content/tags，不可恢复）。" +
        "不确定要改哪条时，先调 read_diary 找到它再改。" +
        "args 示例：{\"id\":12,\"content\":\"改后的正文\"} 或 {\"id\":12,\"tags\":[\"工作\"]} 或 {\"id\":12,\"delete\":true}"

    override fun actionLabel(args: JsonObject): String {
        val id = args.argLong("id")
        return if (args.argBool("delete") == true) "删除日记 #$id" else "修改日记 #$id"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val id = args.argLong("id")
            ?: return ToolOutcome.Failure(
                "缺少必填参数 id（日记条目的数字编号）。请先调 read_diary 查到要改的那条，用结果里的 #id 再调用我。"
            )
        val entry = diaryRepository.entryById(id)
            ?: return ToolOutcome.Failure(
                "没有 #id=$id 的日记条目（可能已被删除）。请先调 read_diary 确认要改哪一条。"
            )

        // 删除：图片记录随外键级联删掉，文件本体在这里清
        if (args.argBool("delete") == true) {
            val preview = entry.content.take(30)
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

        val content = args.argStr("content")?.takeIf { it.isNotBlank() }
        val tagsProvided = args.hasArg("tags")
        if (content == null && !tagsProvided) {
            return ToolOutcome.Failure(
                "没有要修改的内容：请传 content（新正文）、tags（新标签）或 delete=true 之一。"
            )
        }

        val changed = mutableListOf<String>()
        if (content != null) {
            diaryRepository.updateEntryContent(id, content)
            changed += "正文"
        }
        var note = ""
        if (tagsProvided) {
            val vocab = availableTags()
            val requested = args.argStrList("tags")
            val tags = requested.filter { it in vocab }.distinct().take(3)
            val rejected = requested.filter { it !in vocab }
            diaryRepository.updateEntryTags(id, tags)
            changed += if (tags.isEmpty()) "标签（已清空）" else "标签（" + tags.joinToString("、") + "）"
            if (rejected.isNotEmpty()) {
                note = "；以下标签不在可用词汇表中已忽略：" + rejected.joinToString("、")
            }
        }
        val preview = (content ?: entry.content).take(40)
        return ToolOutcome.Success(
            "已更新日记 #$id 的 " + changed.joinToString("、") + "：$preview$note" +
                "。请不要在回复里重复日记全文。"
        )
    }
}
