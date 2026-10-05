package com.example.assistant.core.agent.tools

import com.example.assistant.data.repo.DiaryRepository
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.db.entity.DiaryImageEntity
import com.example.assistant.data.db.entity.tagList
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 读日记工具：主模型可以按关键词 / 标签 / 最近天数检索用户日记，也可以直接按编号取某一条。
 * 设计取向「简单优先」：个人日记量级小（几百条），一次取回最近 500 条后在内存里过滤，
 * 不写复杂 SQL；过滤条件全部可选，模型按需组合。
 *
 * ⚠️ 编号（#id=）的准确性很关键：update_diary 靠它定位条目，且会要求模型复述原正文做核对。
 * 因此列表返回的编号写成显式的 `#id=123`（原来只写 `#123`，模型容易和日期/标签看串、记错），
 * 并支持 `id` 参数**按编号取完整正文**——列表里的长正文会截断，改之前必须能读到原文。
 */
class ReadDiaryTool(
    private val diaryRepository: DiaryRepository
) : AssistantTool {

    override val name = "read_diary"
    override val readOnly = true
    override val description =
        "read_diary(query?, tags?, days?, limit?, id?)：查询用户的历史日记，返回条目列表（含 #id=编号、日期与正文）。" +
        "query 为关键词（正文包含匹配）；tags 为标签数组（多个标签须同时满足）；" +
        "days 表示只看最近 N 天（不传不限时间）；limit 为最多返回几条（默认 8，最大 20）；" +
        "id 为日记编号——传了就只返回这一条，而且是**完整正文不截断**。" +
        "每条结果都会标注是否有图片、几张（形如「图片 2 张（#img=31、#img=32）」）；" +
        "要看图里的内容就用 read_diary_image(id=编号) 把原图调到眼前。" +
        "所有参数都可选，全都不传就是最近的日记。" +
        "要修改或删除某条日记前，务必先 read_diary(id=编号) 读回它的完整正文，" +
        "再把编号和正文原样交给 update_diary（系统会核对，写错会被拒绝）。" +
        "args 示例：{\"query\":\"项目评审\",\"days\":7} 或 {\"id\":252}"

    override fun actionLabel(args: JsonObject): String {
        val id = args.argLong("id")
        if (id != null) return "查日记 #$id"
        val q = args.argStr("query")
        val t = args.argStrList("tags")
        return when {
            q != null -> "查日记「" + q.take(10) + "」"
            t.isNotEmpty() -> "查日记（标签：" + t.joinToString("、") + "）"
            else -> "翻看日记"
        }
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        // ---- 按编号直取单条：完整正文（update_diary 前的核对素材） ----
        val singleId = args.argLong("id")
        if (singleId != null) {
            val e = diaryRepository.entryById(singleId)
                ?: return ToolOutcome.Failure(
                    "没有 #id=$singleId 的日记条目（可能已被删除，或编号记错了）。" +
                        "请不带参数再翻一次最近的日记，用返回里带 #id= 的那一串编号。"
                )
            val singleImages = diaryRepository.imagesFor(e.id)
            return ToolOutcome.Success(
                "日记 #id=" + e.id + "（完整正文）：\n" + render(e, cap = 0, images = singleImages)
            )
        }

        // ---- 检索列表 ----
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
        // 为每条匹配结果带上图片信息：模型据此知道哪条有图、有几张，可调 read_diary_image 看图
        val withImages = matched.map { e -> e to diaryRepository.imagesFor(e.id) }
        val anyImages = withImages.any { it.second.isNotEmpty() }
        val rendered = withImages.map { (e, imgs) -> render(e, cap = CONTENT_CAP, images = imgs) }
        val hint = if (anyImages) {
            "\n\n（带图片的日记可以调 read_diary_image(id=编号) 把原图调到眼前，看完再回答用户）"
        } else {
            ""
        }
        return ToolOutcome.Success(
            "找到 " + matched.size + " 条日记（要改某条：先 read_diary(id=编号) 取完整正文再调 update_diary）：\n" +
                rendered.joinToString("\n\n") + hint
        )
    }

    /**
     * 渲染一条日记。
     * @param cap 正文最多显示多少字；0 = 不截断
     * @param images 该条的图片（用于标注张数与 #img=编号，供 update_diary 增删）
     */
    private fun render(e: DiaryEntryEntity, cap: Int, images: List<DiaryImageEntity> = emptyList()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val imageTag = if (images.isEmpty()) "｜图片：无"
        else "｜图片 " + images.size + " 张（" + images.joinToString("、") { "#img=" + it.id } + "）"
        val head = "#id=" + e.id + "｜" + fmt.format(Date(e.createdAtEpochMillis)) +
            (if (e.tags.isBlank()) "" else "｜标签：" + e.tagList().joinToString("、")) + imageTag
        val text = if (cap > 0 && e.content.length > cap) {
            e.content.take(cap) + "\n…（正文共 " + e.content.length + " 字，此处只显示前 " + cap +
                " 字；要修改请先 read_diary(id=" + e.id + ") 取完整正文）"
        } else {
            e.content
        }
        return head + "\n" + text
    }

    companion object {
        /** 候选池上限：一次最多取回多少条参与过滤（个人日记足够覆盖数年） */
        private const val MAX_POOL = 500

        /** 列表里单条正文回传给模型的最大长度（防长日记挤占上下文；改单条时用 id 取全文） */
        private const val CONTENT_CAP = 600
    }
}
