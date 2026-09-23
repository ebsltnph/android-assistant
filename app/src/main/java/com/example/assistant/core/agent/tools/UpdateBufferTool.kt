package com.example.assistant.core.agent.tools

import com.example.assistant.data.db.entity.BufferItemEntity
import com.example.assistant.data.repo.BufferRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 「进行中的事」缓冲区维护工具（2026-09-17）。
 *
 * 设计要点（用户逐条拍板）：
 *  1. **进工具手册**（messages[1]，被缓存的前缀）——把压缩的全部要求写在 description 里，
 *     永久免费，且尾部触发指令只需一句「请调用 update_buffer」。
 *  2. **绝不允许主动调用**：description 里写死，且应用侧 `compactionRoundActive` 硬拦截
 *     （不是压缩轮就回 Failure）。模型手贱也写不进去，只浪费一轮，不损坏数据。
 *  3. 触发由系统决定（ContextCompactor 在窗口回落时发 [上下文整理] 指令），不由模型决定。
 */
class UpdateBufferTool(
    private val bufferRepository: BufferRepository
) : AssistantTool {

    override val name = NAME

    override val description =
        "update_buffer(items?, archive_ids?, delete_ids?)：「进行中的事」缓冲区维护工具——" +
            "把对话里正在推进、或需要持续关注一段时间的事情，整理成简练条目。" +
            "⚠️ 你**绝不能主动调用**这个工具；只有在收到以【上下文整理】开头的系统指令时才调用它，" +
            "其它任何情况下都不要调用、也不要向用户提起它。\n" +
            "参数：\n" +
            "- items：要新增或改写的条目数组，每项 {\"id\"?:编号, \"kind\"?:\"progress|watch\", " +
            "\"title\":事项名, \"body\":浓缩正文, \"diary_ids\"?:[日记编号]}。" +
            "带 id = 改写该条目（编号见当前注入块里的 #id=）；不带 id = 新增。" +
            "**没有任何变化的条目不要出现在 items 里**（不要逐字重写未变化的条目，那会造成措辞漂移）。\n" +
            "- kind：progress = 在办事项；watch = 需要盯一段时间的状态（如身体不适、等待某个结果）。\n" +
            "- body：简练但保留关键细节——**专有名词、数值、日期、参数、结论一律原样保留**，" +
            "不要概括成空话；可用分号分节，例如「已完成：…；待办：…；关键过程数据：…」。\n" +
            "- diary_ids：这些事对应的日记编号（编号见对话里的 #id=），需要细节时你可用 " +
            "read_diary(id=编号) 取全文；不确定就别填。\n" +
            "- archive_ids：已经完成、或不再需要盯的条目编号（移出注入但保留数据）；" +
            "delete_ids：确认没用的条目编号。\n" +
            "- 如果这批对话确实没有任何值得记进「进行中的事」的内容（纯闲聊、没有进展），" +
            "**仍然要调用一次本工具**并传 {\"noop\": true} 明确表示「无需记录」，不要输出别的文字。\n" +
            "args 示例：{\"items\":[{\"id\":7,\"title\":\"准备考试\"," +
            "\"body\":\"已完成：…；待办：…\"}],\"archive_ids\":[3]}"

    override fun actionLabel(args: JsonObject): String = "整理状态"

    override suspend fun execute(args: JsonObject): ToolOutcome {
        // 应用侧硬拦截：只有压缩轮可以写（description 里的禁令是第一道，这里是第二道）
        if (!compactionRoundActive) {
            return ToolOutcome.Failure(
                "update_buffer 只能在上下文整理时调用（现在是普通对话轮）。" +
                    "请忽略这次调用，正常回答用户；如果用户想手动调整「进行中的事」，" +
                    "让他到应用里的「进行中的事」页面修改。"
            )
        }

        // 显式 noop：这批对话没有值得记录的内容（让压缩流程知道"看过了、确实没有"，
        // 从而正常推进水位线，不要反复重试同一批）
        if (args.argBool("noop") == true) {
            return ToolOutcome.Success("已确认这批对话无需整理进「进行中的事」。（不要输出任何其它文字）")
        }

        val rawItems = parseItems(args)
        val archiveIds = args.argLongList("archive_ids")
        val deleteIds = args.argLongList("delete_ids")
        if (rawItems.isEmpty() && archiveIds.isEmpty() && deleteIds.isEmpty()) {
            return ToolOutcome.Failure(
                "没有可执行的内容：items / archive_ids / delete_ids 至少要有一个。" +
                    "如果这批对话确实没有值得记进「进行中的事」的进展，直接结束、不要调用本工具。"
            )
        }

        val lines = mutableListOf<String>()
        var changed = 0
        rawItems.forEach { obj ->
            val title = obj.argStr("title").orEmpty()
            val body = obj.argStr("body").orEmpty()
            if (title.isBlank() && body.isBlank()) return@forEach
            val diaryIds = obj.argStrList("diary_ids").mapNotNull { it.trim().toLongOrNull() }
            val (id, created) = bufferRepository.upsertFromCompaction(
                id = obj.argLong("id"),
                title = title,
                body = body,
                kind = obj.argStr("kind"),
                diaryIds = diaryIds
            )
            changed++
            lines += (if (created) "新增" else "改写") + " #id=" + id + "｜" + title
        }
        val archived = bufferRepository.archiveMany(archiveIds)
        if (archived > 0) lines += "已归档 " + archived + " 条：" + archiveIds.joinToString("、") { "#id=$it" }
        val deleted = bufferRepository.deleteMany(deleteIds)
        if (deleted > 0) lines += "已删除 " + deleted + " 条：" + deleteIds.joinToString("、") { "#id=$it" }

        if (changed == 0 && archived == 0 && deleted == 0) {
            return ToolOutcome.Failure("没有任何条目被写入（可能 id 都不存在）。请检查编号后重试，或直接结束。")
        }
        return ToolOutcome.Success(
            "「进行中的事」已更新：\n" + lines.joinToString("\n") +
                "\n（如果还有别的变化可以继续调用；没有就停止输出，不要再写任何文字。）"
        )
    }

    /**
     * 读出条目数组；兼容三种写法：
     *  - `items: [{...},{...}]`（推荐）
     *  - 只有一条时直接把 title/body 平铺在顶层
     *  - `items` 里的元素是字符串（极少见，容忍成 title）
     */
    private fun parseItems(args: JsonObject): List<JsonObject> {
        val arr = args["items"] as? JsonArray
        if (arr != null) {
            return arr.mapNotNull { it as? JsonObject }
        }
        // 单条平铺
        return if (args.containsKey("title") || args.containsKey("body")) listOf(args) else emptyList()
    }

    companion object {
        const val NAME = "update_buffer"

        /**
         * 压缩轮进行中标记（**只有压缩轮允许写缓冲区**）。
         * 由 ContextCompactor 在调用前后置位/复位；见 execute 的拦截逻辑。
         */
        @Volatile
        var compactionRoundActive: Boolean = false
    }
}

/** Long 数组读取（模型可能写 [3,7] / ["3","7"] / "3,7"） */
fun JsonObject.argLongList(key: String): List<Long> =
    argStrList(key).mapNotNull { it.trim().toLongOrNull() }
