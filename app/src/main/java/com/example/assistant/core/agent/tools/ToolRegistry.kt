package com.example.assistant.core.agent.tools

import com.example.assistant.core.network.dto.FunctionDefinition
import com.example.assistant.core.network.dto.ToolDefinition
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * 工具注册表（原生 Function Calling 版）：
 * 1. 生成稳定的 `tools` 声明（JSON Schema）与 schema 指纹；
 * 2. 按工具名分发执行，异常统一转为 Failure；
 * 3. 不再解析模型正文里的任何调用语法——调用只来自结构化 `tool_calls`。
 */
class ToolRegistry(private val tools: List<AssistantTool>) {

    private val byName = tools.associateBy { it.name }

    /** 发给模型的工具声明；顺序固定，描述/schema 不包含易变内容 */
    fun definitions(): List<ToolDefinition> = tools.map { tool ->
        ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters
            )
        )
    }

    /**
     * 工具 schema 的稳定指纹。工具定义走 wire 的 tools 字段，不属于 messages，
     * 但厂商的提示词缓存通常也覆盖工具声明；schema 一变应让会话窗口回落一次，
     * 由 PromptBuilder.prefixSignature 统一纳入。
     */
    fun schemaSignature(): String {
        val raw = buildString {
            tools.forEach { tool ->
                append(tool.name).append('\u0000')
                    .append(tool.description).append('\u0000')
                    .append(tool.parameters).append('\u0001')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun find(name: String): AssistantTool? = byName[name]

    /** 执行一次调用；工具抛出的任何异常都转为 Failure（协程取消除外） */
    suspend fun execute(tool: AssistantTool, args: JsonObject): ToolOutcome = try {
        tool.execute(args)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutcome.Failure("工具内部异常：" + (e.message ?: e.javaClass.simpleName))
    }

    /** 剥掉模型自己编造的工具执行清单行（历史页脚模仿的旧坑，继续保留保护） */
    fun stripMachineLines(text: String): String = stripFakeFooterLines(text)

    companion object {
        /** 单次回复允许的最大工具轮数（一轮=模型流式输出一次+执行其全部调用） */
        const val MAX_TOOL_ROUNDS = 4

        /** 达到轮数上限后的强制收尾指令（防死循环） */
        const val FORCED_FINAL_NOTE =
            "（已达单次回复的工具调用次数上限。请不要再调用任何工具，直接根据以上全部结果回答用户。）"
    }
}

/**
 * 剥掉模型自己编造的工具执行清单行（2026-09-14 修的 bug）。
 *
 * 根因：系统会在每条回答末尾自动附一行「🔧 已执行：…」，而这条回答被原样写进会话历史，
 * 模型下一轮看到自己以前"写过"这种清单，就开始照着模仿——一条回复里冒出好几条、
 * 内容还是它自己拼的（和真实执行情况不符），用户看到的现象就是"工具被执行了好多次"。
 *
 * 对策三件套：① 页脚只用于界面展示，**不进会话历史**（Agent.finish 的 body 字段）；
 * ② 历史里已经存下的旧页脚在恢复会话时也剥掉；③ 模型仿写的行从正文/历史里一并去掉。
 *
 * 只删"整行看起来就是机器清单"的行，正文里的正常叙述不受影响。
 */
fun stripFakeFooterLines(text: String): String =
    text.lines()
        .filterNot { isMachineLine(it.trim()) }
        .joinToString("\n")
        .trim()

/** 该行是否为机器风格的状态/清单行（🔧 开头，或以"已执行：…"开头，"已执行"单独成行） */
private fun isMachineLine(line: String): Boolean {
    if (line.isEmpty()) return false
    if (MACHINE_PREFIXES.any { line.startsWith(it) }) return true
    return FOOTER_LINE.matches(line)
}

/** 机器状态行的行首标记（界面上工具状态/页脚用的图标，正文里出现即视为仿写） */
private val MACHINE_PREFIXES = listOf("🔧", "🛠", "⚙")

/** "已执行：…" / "已执行" 清单行（页脚固定写法；半角全角冒号都认） */
private val FOOTER_LINE = Regex("""^已执行[ \t]*[:：].*$|^已执行$""")
