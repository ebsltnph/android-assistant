package com.example.assistant.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI 兼容协议的响应 DTO（流式与非流式共用） */

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int = 0,
    /** 非流式响应的完整消息 */
    val message: ChatMessage? = null,
    /** 流式响应中每个增量片段 */
    val delta: ChatMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)
