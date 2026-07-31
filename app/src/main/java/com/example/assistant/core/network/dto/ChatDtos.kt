package com.example.assistant.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容协议的请求 DTO。
 * content 支持文本与图片两种 part，满足识屏（视觉）需求。
 */

@Serializable
data class ContentPart(
    val type: String = "text", // "text" | "image_url"
    val text: String? = null,
    @SerialName("image_url")
    val imageUrl: ImageUrl? = null
) {
    companion object {
        fun text(text: String) = ContentPart(type = "text", text = text)

        /** base64 图片，url 形如 data:image/png;base64,xxx */
        fun image(base64: String, mimeType: String = "image/png") =
            ContentPart(type = "image_url", imageUrl = ImageUrl("data:$mimeType;base64,$base64"))
    }
}

@Serializable
data class ImageUrl(val url: String)

@Serializable
data class ChatMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: List<ContentPart> = emptyList()
) {
    constructor(role: String, text: String) : this(role, listOf(ContentPart.text(text)))

    val textContent: String
        get() = content.filter { it.type == "text" }.joinToString("\n") { it.text.orEmpty() }
}

@Serializable
data class ResponseFormat(val type: String = "json_object")

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 2048,
    val stream: Boolean = false,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null
)
