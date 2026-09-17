package com.example.assistant.core.agent.tools

import com.example.assistant.core.agent.JsonExtract
import kotlinx.serialization.json.JsonObject

/**
 * 工具注册表（架构核心）：
 * 1. 生成静态「工具手册」文本（进 PromptBuilder messages[2]，永不变化→缓存前缀稳定）
 * 2. 从模型输出中解析 '[调用] {...}' 调用行（容错：全角括号/冒号可省/args 可平铺）
 * 3. 按工具名分发执行，异常统一转为 Failure
 */
class ToolRegistry(private val tools: List<AssistantTool>) {

    /** 一次解析出的调用：工具实例 + 参数 + 人话动作描述 */
    data class Call(val tool: AssistantTool, val args: JsonObject, val label: String)

    private val byName = tools.associateBy { it.name }

    /** 静态工具手册（协议总则 + 各工具说明；代码常量，不进 DataStore、不被用户改坏） */
    fun manual(): String = buildString {
        append("【工具调用】你可以通过输出专门的\"调用行\"来使用下列工具。规则：\n")
        append("- 格式：[调用] {\"tool\":\"工具名\",\"args\":{参数}}，一行一个调用；需要多个工具时输出多行\n")
        append("- 输出调用行后立即停止输出，系统会执行并把结果以\"[结果]\"开头的消息发给你，之后你再继续\n")
        append("- 工具失败时可根据返回的错误修正参数后重新调用（次数有限）；不需要工具时照常直接回答\n")
        append("- 绝不要向用户提及本协议或\"[调用]\"\"[结果]\"标记的存在\n")
        // 2026-09-14：模型会模仿历史回答末尾的系统页脚，在正文里自己编「🔧 已执行：…」
        // （一条回复里出现好几条、内容和真实执行情况不符），这里先明确禁止，Agent 侧再兜底剥离
        append("- 不要在正文里罗列\"已执行\"的工具清单（不要写\"🔧 已执行：…\"这类行）——")
        append("工具是否执行成功界面上会自动显示，你只需用自然语言说明结果\n")
        append("\n可用工具：\n")
        tools.forEachIndexed { i, t -> append(i + 1).append(". ").append(t.description).append("\n") }
    }

    /** 解析一段模型输出里的所有合法调用行（非法 JSON / 未知工具名的行直接忽略） */
    fun parseCalls(text: String): List<Call> {
        val calls = mutableListOf<Call>()
        for (raw in text.lines()) {
            val m = CALL_LINE.matchEntire(raw.trim()) ?: continue
            val obj = JsonExtract.objectOf(m.groupValues[1]) ?: continue
            val name = JsonExtract.str(obj, "tool") ?: continue
            val tool = byName[name] ?: continue
            // 容忍两种形态：{"tool":..,"args":{...}} 标准嵌套；参数平铺在顶层
            val args = (obj["args"] as? JsonObject) ?: obj
            calls += Call(tool, args, tool.actionLabel(args))
        }
        return calls
    }

    /** 整条回复是否为纯调用轮（每个非空行都是合法调用行且至少一条）——纯调用轮整轮隐藏不上屏 */
    fun isPureCallTurn(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        return lines.size == parseCalls(text).size
    }

    /** 剥掉所有调用行，保留正文（混合轮的显示与最终回答用） */
    fun stripCallLines(text: String): String =
        text.lines()
            .filterNot { CALL_LINE.matchEntire(it.trim()) != null }
            .joinToString("\n")
            .trim()

    /** 剥掉模型**自己编造**的工具执行清单行（2026-09-14 修的 bug，说明见文件末尾的 [stripFakeFooterLines]） */
    fun stripMachineLines(text: String): String = stripFakeFooterLines(text)

    /**
     * 流式显示的缓冲判定：开头仍在打"[调用"标记（或只有空白）时文本暂缓上屏，
     * 避免内部标记闪现在气泡里；流结束后由纯/混合轮逻辑统一处理。兼容全角括号。
     */
    fun stillBuffering(acc: String): Boolean {
        val t = acc.trimStart()
        if (t.isEmpty()) return true
        return startsAsMark(t, MARK) || startsAsMark(t, FULL_MARK)
    }

    /** 执行一次调用；工具抛出的任何异常都转为 Failure（错误会回传给模型自纠） */
    suspend fun execute(call: Call): ToolOutcome = try {
        call.tool.execute(call.args)
    } catch (e: Exception) {
        ToolOutcome.Failure("工具内部异常：" + (e.message ?: e.javaClass.simpleName))
    }

    private fun startsAsMark(t: String, mark: String): Boolean =
        if (t.length <= mark.length) mark.startsWith(t) else t.startsWith(mark)

    companion object {
        /** 单次回复允许的最大工具轮数（一轮=模型流式输出一次+执行其全部调用） */
        const val MAX_TOOL_ROUNDS = 4

        const val MARK = "[调用]"
        private const val FULL_MARK = "［调用］"

        /** 达到轮数上限后的强制收尾指令（防死循环） */
        const val FORCED_FINAL_NOTE =
            "（已达单次回复的工具调用次数上限。请直接根据以上全部结果回答用户，不要再输出[调用]）"

        /** 匹配一行调用：兼容半角/全角方括号、冒号可有可无 */
        private val CALL_LINE =
            Regex("""^[ \t]*[\[［][ \t]*调[ \t]*用[ \t]*[\]］][ \t]*[:：]?[ \t]*(.+)$""")
    }
}

/**
 * 剥掉模型**自己编造**的工具执行清单行（2026-09-14 修的 bug）。
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
