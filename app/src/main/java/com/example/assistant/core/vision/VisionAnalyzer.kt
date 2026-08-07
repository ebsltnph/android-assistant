package com.example.assistant.core.vision

import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ChatStream
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import com.example.assistant.core.network.dto.ContentPart
import com.example.assistant.core.storage.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * 视觉分析器：把图片（base64）+ 指令发给「识屏」能力指派的视觉模型。
 * 网络层 DTO 已支持多模态（ContentPart.image），此处只负责组装与调用。
 */
class VisionAnalyzer(
    private val providerRegistry: ProviderRegistry,
    private val promptStore: PromptStore
) {

    /** 当前生效的视觉模型档案（能力指派 → 默认档案兜底）；未配置返回 null */
    suspend fun visionProfile() = providerRegistry.profileFor(Capability.VISION)

    /** 组装视觉调用消息：系统提示词（稳定前缀）+ 图片 + 用户指令 */
    suspend fun buildMessages(imageBase64: String, instruction: String): List<ChatMessage> {
        val prompt = promptStore.prompt(PromptStore.PromptKey.SCREEN_SENSE)
        return listOf(
            ChatMessage("system", prompt),
            ChatMessage(
                "user",
                listOf(ContentPart.text(instruction), ContentPart.image(imageBase64))
            )
        )
    }

    /**
     * 非流式分析（识屏小窗快捷按钮用）。
     * 任何失败返回带 ⚠️ 的错误提示文本（小窗直接展示），不抛异常。
     */
    suspend fun analyze(imageBase64: String, instruction: String): String =
        withContext(Dispatchers.IO) {
            val profile = visionProfile() ?: return@withContext GUIDE_TEXT
            try {
                val api = providerRegistry.apiFor(profile)
                val (thinking, effort) = providerRegistry.thinkingParamsFor(profile)
                val request = ChatRequest(
                    model = profile.model,
                    messages = buildMessages(imageBase64, instruction),
                    temperature = 0.3,
                    // 4096：推理模型思考过程占 max_tokens 配额（P4 踩坑），一次分析给足
                    maxTokens = 4096,
                    thinking = thinking,
                    reasoningEffort = effort
                )
                val header = providerRegistry.authHeader(profile.apiKey)
                val response = providerRegistry.chatCompat(profile, request, header, api)
                response.choices.firstOrNull()?.message?.textContent
                    ?.takeIf { it.isNotBlank() }
                    ?: "（模型没有返回内容）"
            } catch (e: HttpException) {
                val detail = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                "⚠️ 识屏调用失败（HTTP ${e.code()}${detail?.take(120)?.let { "：$it" } ?: ""}）\n" +
                    "请确认「识屏」模型支持图片输入——DeepSeek 官方 API 不支持视觉，" +
                    "可换通义 qwen-vl / 智谱 GLM-4V / Kimi vision 等"
            } catch (e: Exception) {
                "⚠️ 识屏失败：${e.message}\n请检查网络与「识屏」模型配置"
            }
        }

    /**
     * 流式分析（聊天附件/分享图片用）。
     * 未配置视觉模型时抛 IllegalStateException(GUIDE_TEXT)，其余错误在流内抛出。
     */
    fun analyzeStream(imageBase64: String, instruction: String): Flow<ChatResponse> = flow {
        val profile = visionProfile() ?: throw IllegalStateException(GUIDE_TEXT)
        val api = providerRegistry.apiFor(profile)
        val (thinking, effort) = providerRegistry.thinkingParamsFor(profile)
        val request = ChatRequest(
            model = profile.model,
            messages = buildMessages(imageBase64, instruction),
            temperature = 0.3,
            // 4096：推理模型思考过程占 max_tokens 配额（2048 会被思考吃光，content 为空）
            maxTokens = 4096,
            stream = true,
            thinking = thinking,
            reasoningEffort = effort
        )
        val header = providerRegistry.authHeader(profile.apiKey)
        val response = providerRegistry.chatStreamCompat(profile, request, header, api)
        emitAll(ChatStream.parse(response.body()!!))
    }

    companion object {
        /** 未配置视觉模型时的引导文本（聊天/小窗通用） */
        const val GUIDE_TEXT =
            "⚠️ 识屏需要支持图片输入的模型。\n" +
                "请到「设置 → 能力指派」把「识屏（视觉）」指派给视觉模型" +
                "（如通义 qwen-vl、智谱 GLM-4V、Kimi vision、OpenAI gpt-4o），" +
                "DeepSeek 官方 API 不支持图片。"
    }
}
