package com.example.assistant.core.agent.tools

import com.example.assistant.core.agent.ScreenAction
import com.example.assistant.core.vision.ScreenSenseController
import kotlinx.serialization.json.JsonObject

/**
 * 识屏工具：主模型判断用户想分析当前屏幕时发起截屏流程（取代原 llmClassify 的识屏分支）。
 * 授权/截屏/小窗展示由既有链路完成；浮动面板的通知由 ChatViewModel 监听
 * ScreenSenseController.requests 统一转发（关键词路径与工具路径同源，无需特殊处理）。
 */
class ScreenSenseTool(private val controller: ScreenSenseController) : AssistantTool {

    override val name = "screen_sense"
    override val description =
        "screen_sense(action)：截取用户当前屏幕并交给视觉模型分析（提取文字/翻译/描述）。" +
        "用户提到「看看屏幕」「屏幕上写了什么」「翻译这个页面」等时使用。" +
        "args 示例：{\"action\":\"extract\"}；action 可选 extract（提取文字）/translate（翻译）/analysis（按要求分析），缺省 extract。" +
        "调用后系统会弹授权窗口，请告知用户正在准备识屏。"

    override fun actionLabel(args: JsonObject): String = "识屏"

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val action = when (args.argStr("action")?.lowercase()) {
            "translate" -> ScreenAction.TRANSLATE
            "analysis", "full_analysis", "analyze" -> ScreenAction.FULL_ANALYSIS
            else -> ScreenAction.EXTRACT_TEXT
        }
        controller.requestScreenSense(action)
        return ToolOutcome.Success(
            "识屏流程已启动：等待用户在系统弹窗中授权。请简短告知用户正在准备识屏、结果出来后会自动显示。"
        )
    }
}