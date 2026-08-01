package com.example.assistant.core.network.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * OpenAI 兼容协议的请求 DTO。
 * content 支持文本与图片两种 part，满足识屏（视觉）需求。
 */

/**
 * content 字段的灵活序列化器：
 * - 发送请求：始终输出数组形式（text/image_url part），兼容视觉模型
 * - 解析响应：兼容两种形式——字符串（DeepSeek 等大多数厂商）
 *   和数组（OpenAI 多模态规范），解析为 List<ContentPart> 供统一读取
 */
@OptIn(ExperimentalSerializationApi::class)
object FlexibleContentSerializer : KSerializer<List<ContentPart>> {
    private val listSerializer: KSerializer<List<ContentPart>> = ListSerializer(ContentPart.serializer())
    override val descriptor: SerialDescriptor = listSerializer.descriptor

    private val jsonDecoder: Json = Json { ignoreUnknownKeys = true }

    override fun serialize(encoder: Encoder, value: List<ContentPart>) {
        listSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<ContentPart> {
        if (decoder !is JsonDecoder) return listSerializer.deserialize(decoder)
        val element: JsonElement = decoder.decodeJsonElement()
        return when (element) {
            // 响应形如 "content":"连接成功"
            is JsonPrimitive -> {
                // 注意 JsonNull 也是 JsonPrimitive 子类：推理模型思考阶段的
                // content 常为 null（内容在 reasoning_content），此时视为空文本
                if (element is kotlinx.serialization.json.JsonNull) emptyList()
                else listOf(ContentPart.text(element.content))
            }
            // 响应形如 "content":[{"type":"text","text":"..."}]
            is JsonArray -> element.mapNotNull { part ->
                try {
                    jsonDecoder.decodeFromJsonElement(ContentPart.serializer(), part)
                } catch (_: Exception) {
                    null // 个别 part 结构异常时跳过，不阻塞整条消息
                }
            }
            else -> emptyList()
        }
    }
}

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
    /**
     * "system" | "user" | "assistant"。
     * 默认值仅用于解析流式响应——流式增量 delta 不含 role 字段；
     * 请求端始终通过构造函数显式传 role。
     */
    val role: String = "assistant",
    @Serializable(with = FlexibleContentSerializer::class)
    val content: List<ContentPart> = emptyList(),
    /** DeepSeek 等推理模型返回的思考过程（可选字段，仅响应端使用） */
    @SerialName("reasoning_content")
    val reasoningContent: String? = null
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
    val responseFormat: ResponseFormat? = null,
    /**
     * 思考开关（DeepSeek 格式：{"type":"enabled"} / {"type":"disabled"}）。
     * null = 不发送（跟随厂商/模型默认）。
     */
    val thinking: JsonElement? = null,
    /** 思考深度（OpenAI 格式："low"/"medium"/"high"，DeepSeek 兼容）。null = 不发送 */
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null
)
