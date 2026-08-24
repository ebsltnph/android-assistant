package com.example.assistant.core.agent.tools

import com.example.assistant.core.agent.JsonExtract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具执行结果：feedback / error 都会以「[结果]」消息回传给模型——
 * 成功给确认信息与数据，失败给**可纠正的错误原因**（模型可修参数重试）。
 */
sealed interface ToolOutcome {
    data class Success(val feedback: String) : ToolOutcome
    data class Failure(val error: String) : ToolOutcome
}

/**
 * 助手工具（架构核心抽象）：主聊天模型在回复中输出
 * '[调用] {"tool":"名称","args":{...}}' 行发起调用，
 * ToolRegistry 统一解析分发、本地执行。
 * 新增能力 = 新增一个实现类并在 AppContainer 注册，解析器与回路零改动。
 */
interface AssistantTool {
    /** 工具名（模型调用时的 "tool" 字段，snake_case） */
    val name: String

    /** 给模型看的一行说明：功能 + 参数格式（进静态工具手册） */
    val description: String

    /** 执行状态提示 / 回答页脚用的人话动作描述（如「搜索：xxx」） */
    fun actionLabel(args: JsonObject): String = name

    /** 执行工具；抛出的异常会被 ToolRegistry 捕获转为 Failure */
    suspend fun execute(args: JsonObject): ToolOutcome
}

// ---- args 容错读取（模型可能把数字写成字符串、数组写成逗号串）----

fun JsonObject.argStr(key: String): String? =
    JsonExtract.str(this, key)?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.argInt(key: String): Int? = JsonExtract.int(this, key)

fun JsonObject.argStrList(key: String): List<String> {
    val arr = this[key] as? JsonArray
    if (arr != null) {
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
    }
    return argStr(key)
        ?.split(",", "，", "、")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
}
