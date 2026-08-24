package com.example.assistant.core.agent.tools

import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.repo.MemoryRepository
import kotlinx.serialization.json.JsonObject

/**
 * 写长期记忆工具：模型在对话中判断值得记住的信息并主动写入。
 * 服务端仍执行 importance≥7 过滤——低于阈值只回"已忽略"，防止一次性琐事进长期记忆。
 */
class WriteMemoryTool(private val memoryRepository: MemoryRepository) : AssistantTool {

    override val name = "write_memory"
    override val description =
        "write_memory(fact, category, importance)：把值得长期记住的用户信息写入记忆库" +
        "（称呼偏好、稳定习惯、重要背景等；一次性琐事不要写）。" +
        "args 示例：{\"fact\":\"用户希望被称呼为龙哥\",\"category\":\"preference\",\"importance\":8}。" +
        "importance 为 1-10 整数，只有 ≥7 才会真正保存。"

    override fun actionLabel(args: JsonObject): String {
        val f = args.argStr("fact")?.take(12) ?: "?"
        return "记忆「$f」"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val fact = args.argStr("fact")
            ?: return ToolOutcome.Failure("缺少必填参数 fact（要记住的事实）")
        val importance = args.argInt("importance") ?: 5
        if (importance < IMPORTANCE_THRESHOLD) {
            // 不算失败：明确告知没存，避免模型向用户宣称已记住
            return ToolOutcome.Success(
                "该内容 importance=" + importance + " 低于阈值 " + IMPORTANCE_THRESHOLD +
                    "，未写入长期记忆（符合预期，无需向用户提及细节）。"
            )
        }
        memoryRepository.addFacts(
            listOf(
                MemoryEntity(
                    fact = fact,
                    category = args.argStr("category")?.ifBlank { null } ?: "general"
                )
            )
        )
        return ToolOutcome.Success("已写入长期记忆：" + fact)
    }

    companion object {
        /** 与原 MemoryExtractor 一致的入库阈值 */
        const val IMPORTANCE_THRESHOLD = 7
    }
}