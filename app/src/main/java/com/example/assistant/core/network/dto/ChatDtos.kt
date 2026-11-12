package com.example.assistant.core.network.dto

import kotlinx.serialization.EncodeDefault
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
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * OpenAI 兼容协议的请求 DTO。
 * content 支持文本与图片两种 part，满足识屏（视觉）需求。
 */

/**
 * content 字段的灵活序列化器：
 * - 发送请求：纯文本输出字符串（tool 角色要求字符串）；带图片输出数组。
 * - 解析响应：兼容字符串和数组两种形式，统一为 List<ContentPart>。
 */
@OptIn(ExperimentalSerializationApi::class)
object FlexibleContentSerializer : KSerializer<List<ContentPart>> {
    private val listSerializer: KSerializer<List<ContentPart>> = ListSerializer(ContentPart.serializer())
    override val descriptor: SerialDescriptor = listSerializer.descriptor

    private val jsonDecoder: Json = Json { ignoreUnknownKeys = true }
    private val jsonEncoder: Json = Json { encodeDefaults = false }

    override fun serialize(encoder: Encoder, value: List<ContentPart>) {
        // 纯文本（含空内容）发字符串：tool 消息、assistant tool_calls 的 content="" 都按标准形态。
        // 只有带图片时才需要数组。
        if (value.none { it.type == "image_url" }) {
            if (encoder is JsonEncoder) {
                encoder.encodeJsonElement(JsonPrimitive(value.filter { it.type == "text" }
                    .joinToString("\n") { it.text.orEmpty() }))
                return
            }
        } else if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(jsonEncoder.encodeToJsonElement(listSerializer, value))
            return
        }
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

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContentPart(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
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

// ======================= 原生 Function Calling DTO =======================

/** 请求里声明的工具（OpenAI/DeepSeek 兼容的 tools 字段） */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolDefinition(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    /** JSON Schema（type/properties/required/additionalProperties） */
    val parameters: JsonObject,
    val strict: Boolean? = null
)

/**
 * 一次工具调用/流式增量。
 * - 完整回放：id/type/function 齐全，index 不填。
 * - 流式增量：index 标识调用，id/name 可能只在首片，arguments 逐片追加。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatToolCall(
    val id: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",
    val function: FunctionCall? = null,
    val index: Int? = null
)

@Serializable
data class FunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatMessage(
    /**
     * "system" | "user" | "assistant" | "tool"。
     * 默认值仅用于解析流式响应——流式增量 delta 不含 role 字段；
     * 请求端始终通过构造函数显式传 role。
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val role: String = "assistant",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @Serializable(with = FlexibleContentSerializer::class)
    val content: List<ContentPart> = emptyList(),
    /** DeepSeek 思考模式：工具调用轮必须在后续请求完整回传 */
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    /** assistant 发起的原生工具调用（回放历史时必须保留） */
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCall>? = null,
    /** role=tool 时对应的调用 id */
    @SerialName("tool_call_id")
    val toolCallId: String? = null
) {
    constructor(role: String, text: String) : this(role, listOf(ContentPart.text(text)))

    val textContent: String
        get() = content.filter { it.type == "text" }.joinToString("\n") { it.text.orEmpty() }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseFormat(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "json_object"
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val temperature: Double = 0.7,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("max_tokens")
    val maxTokens: Int = 2048,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stream: Boolean = false,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    /**
     * 思考深度（OpenAI 通用格式："low"/"medium"/"high"）。
     * 只用通用参数：DeepSeek 的 thinking 开关格式（{"type":"enabled"/"disabled"}）
     * 是它的专属参数，中转站/OpenAI 系模型不认识会直接 HTTP 400 拒绝，
     * 故一律不发送（v1.2.x 起：思考跟随模型默认，深度可调）。
     * null = 不发送（跟随厂商/模型默认）。
     */
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    /**
     * 流式附带的用量统计开关：`{"include_usage": true}` 让厂商在流末尾补一个 usage 块
     * （用于读取"缓存命中 token"，验证提示词缓存是否真的生效）。
     * 部分中转站不认识该参数会 HTTP 400 → ProviderRegistry 会自动去掉并记住（见 effortSafeCall）。
     */
    @SerialName("stream_options")
    val streamOptions: StreamOptions? = null,
    /** 原生工具声明；null = 不带工具（后台批处理） */
    val tools: List<ToolDefinition>? = null,
    /** 固定 "auto"；DeepSeek 思考模式不支持 required/named */
    @SerialName("tool_choice")
    val toolChoice: String? = null
)

/** 流式请求选项（目前只用 include_usage） */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StreamOptions(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("include_usage")
    val includeUsage: Boolean = true
)
