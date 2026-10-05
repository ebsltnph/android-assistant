package com.example.assistant.core.agent.tools

import com.example.assistant.data.repo.DiaryRepository
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 读日记图片工具（2026-10-05）：把某条日记的**图片原图**送回对话，让主模型亲眼看。
 *
 * 与其它工具的关键差别：本工具**自身不做任何模型调用**，只返回图片的本地路径
 * （[ToolOutcome.Success.images]）；Agent 检测到非空后把它们作为 image part 塞进这一轮对话，
 * 并把后续请求切到「识屏（视觉）」档案。这样既让主模型看到真实像素，
 * 又不必扩展工具协议之外的任何东西。
 *
 * 因此它必须检查视觉档案是否真的支持图片：不支持时明确失败（而不是注入后 HTTP 400）。
 */
class ReadDiaryImageTool(
    private val diaryRepository: DiaryRepository,
    /** 「识屏（视觉）」指派的档案是否勾选了"支持图片输入" */
    private val visionAvailable: suspend () -> Boolean
) : AssistantTool {

    override val name = "read_diary_image"
    override val readOnly = true
    override val description =
        "read_diary_image(id, image_ids?)：查看某条日记里的图片（把原图送到你眼前，你亲自看图作答）。" +
        "id 必填，是 read_diary 结果里的 #id= 编号（那条日记必须带图片，read_diary 会标注图片张数）。" +
        "image_ids 可选：只想看其中某几张时，传 read_diary 返回的 #img= 编号数组；" +
        "不传就是这一条的全部图片（一次最多附 " + MAX_IMAGES + " 张）。" +
        "调用后系统会把图片附进对话，你直接描述/提取/回答即可，不要向用户复述本协议。" +
        "args 示例：{\"id\":12} 或 {\"id\":12,\"image_ids\":[31,32]}"

    override fun actionLabel(args: JsonObject): String =
        "看日记图片 #" + (args.argLong("id") ?: "?")

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val id = args.argLong("id")
            ?: return ToolOutcome.Failure("缺少必填参数 id（日记编号，来自 read_diary 返回的 #id=）。")
        val entry = diaryRepository.entryById(id)
            ?: return ToolOutcome.Failure(
                "没有 #id=$id 的日记条目（可能已被删除，或编号记错了）。请先 read_diary 确认编号。"
            )
        val all = diaryRepository.imagesFor(id)
        if (all.isEmpty()) {
            return ToolOutcome.Success(
                "#id=$id 这条日记没有图片，不需要看图；请直接根据它的正文回答，或如实告知用户这条没有图。"
            )
        }
        if (!visionAvailable()) {
            return ToolOutcome.Failure(
                "当前「识屏（视觉）」指派的模型没有打开「支持图片输入」，无法看图。" +
                    "请如实告知用户：到 设置 → 模型配置 里指派一个支持图片输入的模型后再试。"
            )
        }
        // 指定了 image_ids 就只看这几张（编号来自 read_diary 的 #img=）；否则看全部（上限 MAX_IMAGES）
        val requestedIds = args.argStrList("image_ids").mapNotNull { it.toLongOrNull() }
        val picked = if (requestedIds.isEmpty()) {
            all.take(MAX_IMAGES)
        } else {
            val selected = all.filter { it.id in requestedIds }
            if (selected.isEmpty()) {
                return ToolOutcome.Success(
                    "#id=$id 里没有这些图片编号（" + requestedIds.joinToString("、") + "）。" +
                        "可用的图片编号：" + all.joinToString("、") { "#img=" + it.id } + "。"
                )
            }
            selected
        }
        // 只把真实存在的文件交给 Agent 注入（图片可能已被清理）
        val paths = picked.map { it.path }.filter { File(it).exists() }
        if (paths.isEmpty()) {
            return ToolOutcome.Success("#id=$id 的图片文件都找不到了（可能已被清理），无法查看。")
        }
        val more = if (requestedIds.isEmpty() && all.size > paths.size) {
            "（本条日记共 " + all.size + " 张，这里只附了前 " + paths.size +
                " 张；要看其余的请带 image_ids 指定 #img= 编号）"
        } else {
            ""
        }
        return ToolOutcome.Success(
            "日记 #id=$id 的 " + paths.size + " 张图片已附在你眼前$more。" +
                "请直接看图：用户有明确问题就据此回答，没有明确问题就概括图里的信息（文字/场景），" +
                "不要在回复里复述这段说明。",
            images = paths
        )
    }

    companion object {
        /** 一次最多注入对话的图片张数（防单次请求体过大） */
        private const val MAX_IMAGES = 8
    }
}
