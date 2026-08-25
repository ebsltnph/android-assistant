package com.example.assistant.core.agent.tools

import com.example.assistant.core.speech.TtsManager
import kotlinx.serialization.json.JsonObject

/**
 * 朗读工具：主模型自主判断何时把内容读出声给用户听。
 * 关键约束写进 description——text 必须是模型**提炼后的口语短文**，
 * 不是照搬完整回复（用户确认的设计：读哪些、读多长都由模型决定）。
 */
class SpeakTool(private val ttsManager: TtsManager) : AssistantTool {

    override val name = "speak"
    override val description =
        "speak(text)：把内容用语音朗读给用户听。" +
        "只在用户明确要求「读出来 / 念给我听」，或回答很短且明显适合口头告知时才调用，不要每条回复都调用。" +
        "text 必须是提炼后的口语短文：去掉 markdown 符号、列表、公式和代码，把要点压缩成一两句话，" +
        "不是照搬回复全文。args 示例：{\"text\":\"明天上午十点开会，我已经记好了\"}"

    override fun actionLabel(args: JsonObject): String {
        val t = args.argStr("text")?.take(10) ?: "?"
        return "朗读「$t…」"
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val text = args.argStr("text")
            ?: return ToolOutcome.Failure("缺少必填参数 text（要朗读的口语短文）")
        val ok = ttsManager.speak(text)
        return if (ok) ToolOutcome.Success("已开始朗读。不要在正文里重复朗读内容。")
        else ToolOutcome.Failure(TtsManager.UNAVAILABLE_HINT)
    }
}