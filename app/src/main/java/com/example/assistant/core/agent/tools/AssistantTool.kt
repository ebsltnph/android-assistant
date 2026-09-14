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

    /**
     * 只读工具（查询类：读日记 / 读提醒 / 读网页 / 搜索）——**不产生副作用**。
     *
     * Agent 的「同参数调用结果复用」备忘对这类工具必须关掉：模型先 read 再改再 read
     * （参数一模一样）时，如果复用第一次的旧结果，模型看到的就是改动前的数据，
     * 会误判"没改成功"（用户实测到的困惑来源）。只读工具重跑一次成本很低，正确性优先。
     */
    val readOnly: Boolean get() = false

    /** 执行工具；抛出的异常会被 ToolRegistry 捕获转为 Failure */
    suspend fun execute(args: JsonObject): ToolOutcome
}

// ---- args 容错读取（模型可能把数字写成字符串、数组写成逗号串）----

fun JsonObject.argStr(key: String): String? =
    JsonExtract.str(this, key)?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.argInt(key: String): Int? = JsonExtract.int(this, key)

/** Long 读取（数据库 id 用；模型可能写 "12" / 12 / "12.0"） */
fun JsonObject.argLong(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
}

/** 布尔读取（模型可能写 true/"true"/1） */
fun JsonObject.argBool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return when (p.contentOrNull?.trim()?.lowercase()) {
        "true", "1", "yes", "是" -> true
        "false", "0", "no", "否" -> false
        else -> null
    }
}

/** 模型是否传了这个键（用于区分"没传"与"传了空数组=清空"） */
fun JsonObject.hasArg(key: String): Boolean = this.containsKey(key)

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
