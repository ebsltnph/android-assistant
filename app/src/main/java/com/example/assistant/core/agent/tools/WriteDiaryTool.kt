package com.example.assistant.core.agent.tools

import com.example.assistant.data.repo.DiaryRepository
import kotlinx.serialization.json.JsonObject

/**
 * 写日记工具：聊天里的记录请求由主模型整理成简洁正文并选标签后直接落库，
 * 不再有独立的 DiarySummarizer 调用。标签只收用户词汇表内的词（词汇表在易变上下文里提供给模型）。
 */
class WriteDiaryTool(
    private val diaryRepository: DiaryRepository,
    /** 可用标签词汇表（用户自定义，随设置变化，故用惰性提供者而非构造常量） */
    private val availableTags: suspend () -> List<String>
) : AssistantTool {

    override val name = "write_diary"
    override val description =
        "write_diary(content, tags)：把用户要记录的内容写入日记本。" +
        "content 为整理后的简洁日记正文（一两句话、不要对话体）；" +
        "tags 为标签数组（0-3 个，只能从易变上下文消息给出的「可用日记标签」里选，没有合适的就不传）。" +
        "args 示例：{\"content\":\"完成了搜索工具回路重构\",\"tags\":[\"工作\"]}"

    override fun actionLabel(args: JsonObject): String {
        val c = args.argStr("content")?.take(12) ?: "?"
        return "日记「$c」"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val content = args.argStr("content")
            ?: return ToolOutcome.Failure("缺少必填参数 content（日记正文）")
        val vocab = availableTags()
        // 只保留词汇表内的标签；模型给了词表外的词时明确反馈（不算失败）
        val requested = args.argStrList("tags")
        val tags = requested.filter { it in vocab }.distinct().take(3)
        val rejected = requested.filter { it !in vocab }
        val book = diaryRepository.defaultBook()
            ?: return ToolOutcome.Failure("默认日记本不存在（异常状态），请告知用户到日记页检查。")
        diaryRepository.addEntry(book.id, content, source = "chat", tags = tags)
        val note = if (rejected.isEmpty()) ""
        else "；以下标签不在可用词汇表中已忽略：" + rejected.joinToString("、")
        return ToolOutcome.Success(
            "已写入日记：" + content +
            (if (tags.isEmpty()) "" else "（标签：" + tags.joinToString("、") + "）") + note +
                "。请不要在回复里重复日记全文。"
        )
    }
}