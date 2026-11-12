package com.example.assistant.core.agent.tools

import com.example.assistant.core.agent.JsonExtract
import com.example.assistant.core.network.dto.ChatToolCall
import kotlinx.serialization.json.JsonObject

/**
 * 原生 Function Calling 的流式累计器（纯 Kotlin，可单测）。
 *
 * 各家 SSE 的共同规则：同一 index 的 `function.arguments` 分片需要**按到达顺序拼接**；
 * `id`/`name` 可能只在首片出现，后续分片可能重复空串或 null——空值绝不能抹掉已建立的身份
 * （DeepSeek Harness 在生产中踩过这个坑，空身份会让整个会话失效）。
 */
class NativeToolCallAssembler {

    data class Call(
        val id: String,
        val name: String,
        /** 原始 JSON 字符串，解析失败时也保留，便于回放与给模型纠错 */
        val arguments: String,
        val order: Int
    ) {
        /** 参数解析；失败返回 null（不静默替换原文） */
        fun parsedArgs(): JsonObject? = JsonExtract.objectOf(arguments.ifBlank { "{}" })
    }

    data class Batch(
        val calls: List<Call>,
        /** 无法安全执行/无法配对结果的协议错误（缺 id/name、重复 id） */
        val protocolErrors: List<String>
    ) {
        val hasCalls: Boolean get() = calls.isNotEmpty()
        val canExecute: Boolean get() = protocolErrors.isEmpty() && calls.isNotEmpty()
    }

    private data class Acc(
        val order: Int,
        var index: Int,
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private val accs = mutableListOf<Acc>()
    private val protocolErrors = mutableListOf<String>()
    private var nextOrder = 0

    /** 重新开始一轮流（同一个 assembler 可跨流复用，但每次必须 reset） */
    fun reset() {
        accs.clear()
        protocolErrors.clear()
        nextOrder = 0
    }

    /** 接收一个流式增量；index 缺失时按 id/顺序做保守归并 */
    fun accept(delta: ChatToolCall?) {
        if (delta == null) return
        val id = delta.id.orEmpty().trim()
        val name = delta.function?.name.orEmpty().trim()
        val args = delta.function?.arguments.orEmpty()
        val wireIndex = delta.index

        val target: Acc = when {
            wireIndex != null -> accs.firstOrNull { it.index == wireIndex }
                ?: newAcc(wireIndex)
            id.isNotEmpty() -> accs.firstOrNull { it.id == id }
                ?: accs.lastOrNull()?.takeIf { it.id.isBlank() }
                ?: newAcc(null)
            else -> {
                // 并行调用时，没有 index 也没有 id 的续片无法判断属于哪一个调用。
                // 宁可中止本轮，也不能把参数偷偷拼到错误的工具上。
                if (accs.size > 1) {
                    protocolErrors += "并行工具调用的参数续片缺少 index，无法确定归属"
                    return
                }
                accs.lastOrNull() ?: newAcc(null)
            }
        }

        if (id.isNotEmpty()) {
            if (target.id.isBlank()) {
                target.id = id
            } else if (target.id != id) {
                protocolErrors += "同一工具调用分片出现冲突 id：${target.id} / $id"
            }
        }
        if (name.isNotEmpty()) {
            if (target.name.isBlank()) target.name = name
            else if (target.name != name) {
                protocolErrors += "同一工具调用分片出现冲突 name：${target.name} / $name"
            }
        }
        if (args.isNotEmpty()) target.arguments.append(args)
    }

    fun acceptAll(deltas: List<ChatToolCall>?) {
        deltas.orEmpty().forEach(::accept)
    }

    /**
     * 结束本轮并校验：
     * - 缺 id 或 name：无法生成可配对的 tool 结果，整批不可执行；
     * - 重复 id：结果会被错误配对，整批不可执行；
     * - 参数 JSON 非法属于单调用错误，交给 Agent 生成对应 call_id 的失败结果，不阻断其他调用。
     */
    fun finish(): Batch {
        val ordered = accs.sortedBy { it.order }
        ordered.forEachIndexed { i, a ->
            if (a.id.isBlank()) protocolErrors += "第 ${i + 1} 个工具调用缺少 id"
            if (a.name.isBlank()) protocolErrors += "第 ${i + 1} 个工具调用缺少 name"
        }
        val dup = ordered.filter { it.id.isNotBlank() }
            .groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        dup.forEach { protocolErrors += "重复的 tool_call_id：$it" }

        val calls = ordered.map {
            Call(
                id = it.id,
                name = it.name,
                arguments = it.arguments.toString(),
                order = it.order
            )
        }
        return Batch(calls, protocolErrors.distinct())
    }

    private fun newAcc(wireIndex: Int?): Acc {
        val acc = Acc(order = nextOrder++, index = wireIndex ?: nextOrder - 1)
        accs += acc
        return acc
    }
}
