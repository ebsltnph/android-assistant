package com.example.assistant.core.network.dto

import com.example.assistant.core.agent.tools.AssistantTool
import com.example.assistant.core.agent.tools.NativeToolCallAssembler
import com.example.assistant.core.agent.tools.ToolOutcome
import com.example.assistant.core.agent.tools.ToolRegistry
import com.example.assistant.core.network.ChatStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生 Function Calling 的 wire 形态回归：
 * ApiClient 的 encodeDefaults=false，关键常量字段由 @EncodeDefault 强制写出。
 */
class ChatWireProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun `plain text content is a json string`() {
        val element = json.encodeToJsonElement(ChatMessage.serializer(), ChatMessage("user", "你好"))
            .jsonObject
        assertEquals("user", element["role"]!!.jsonPrimitive.content)
        assertEquals("你好", element["content"]!!.jsonPrimitive.content)
        assertFalse(element.containsKey("reasoning_content"))
        assertFalse(element.containsKey("tool_calls"))
        assertFalse(element.containsKey("tool_call_id"))
    }

    @Test
    fun `image content stays an array with type`() {
        val message = ChatMessage("user", listOf(ContentPart.text("看图"), ContentPart.image("abc")))
        val element = json.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject
        val content = element["content"] as JsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant tool call replay keeps reasoning and tool calls`() {
        val assistant = ChatMessage(
            role = "assistant",
            content = emptyList(),
            reasoningContent = "先查一下",
            toolCalls = listOf(
                ChatToolCall(
                    id = "call_1",
                    type = "function",
                    function = FunctionCall("web_search", "{\"query\":\"DeepSeek\"}")
                )
            )
        )
        val element = json.encodeToJsonElement(ChatMessage.serializer(), assistant).jsonObject
        assertEquals("assistant", element["role"]!!.jsonPrimitive.content)
        assertEquals("", element["content"]!!.jsonPrimitive.content)
        assertEquals("先查一下", element["reasoning_content"]!!.jsonPrimitive.content)
        val calls = element["tool_calls"] as JsonArray
        assertEquals("function", calls[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", calls[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(calls[0].jsonObject.containsKey("index"))
    }

    @Test
    fun `tool result uses tool role and call id`() {
        val msg = ChatMessage(
            role = "tool",
            content = listOf(ContentPart.text("搜索完成")),
            toolCallId = "call_1"
        )
        val element = json.encodeToJsonElement(ChatMessage.serializer(), msg).jsonObject
        assertEquals("tool", element["role"]!!.jsonPrimitive.content)
        assertEquals("搜索完成", element["content"]!!.jsonPrimitive.content)
        assertEquals("call_1", element["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request emits tools and tool choice`() {
        val registry = ToolRegistry(listOf(fakeTool("web_search")))
        val req = ChatRequest(
            model = "deepseek-flash",
            messages = listOf(ChatMessage("user", "搜一下")),
            tools = registry.definitions(),
            toolChoice = "auto"
        )
        val element = json.encodeToJsonElement(ChatRequest.serializer(), req).jsonObject
        assertEquals("auto", element["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(false, element["stream"]!!.jsonPrimitive.content.toBoolean())
        val tools = element["tools"] as JsonArray
        val function = tools[0].jsonObject["function"]!!.jsonObject
        assertEquals("web_search", function["name"]!!.jsonPrimitive.content)
        assertTrue(function.containsKey("parameters"))
        assertEquals("function", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stream chunk decodes tool call argument deltas`() {
        val raw = """
            {"choices":[{"index":0,"delta":{"tool_calls":[
              {"index":0,"id":"call_1","type":"function",
               "function":{"name":"web_search","arguments":"{\"query\":"}}
            ]},"finish_reason":null}]}
        """.trimIndent()
        val response = json.decodeFromString(ChatResponse.serializer(), raw)
        val delta = response.choices.single().delta
        assertEquals("call_1", delta!!.toolCalls!!.single().id)
        assertEquals("web_search", delta.toolCalls!!.single().function!!.name)
        assertEquals("{\"query\":", delta.toolCalls!!.single().function!!.arguments)
        assertEquals(0, delta.toolCalls!!.single().index)
    }

    @Test
    fun `non streaming response decodes tool calls and reasoning`() {
        val raw = """
            {"choices":[{"message":{
              "role":"assistant","content":null,"reasoning_content":"想一想",
              "tool_calls":[{"id":"call_1","type":"function",
                "function":{"name":"web_search","arguments":"{\"query\":\"x\"}"}}]
            },"finish_reason":"tool_calls"}]}
        """.trimIndent()
        val message = json.decodeFromString(ChatResponse.serializer(), raw)
            .choices.single().message!!
        assertEquals("想一想", message.reasoningContent)
        assertEquals("call_1", message.toolCalls!!.single().id)
        assertEquals("{\"query\":\"x\"}", message.toolCalls!!.single().function!!.arguments)
    }

    @Test
    fun `all built-in tool schemas are objects with properties`() {
        val names = listOf(
            "web_search", "read_webpage", "read_diary", "read_diary_image",
            "update_diary", "list_reminders", "speak", "set_reminder",
            "write_memory", "write_diary", "monitor_event", "screen_sense", "update_buffer"
        )
        val registry = ToolRegistry(names.map(::fakeTool))
        val definitions = registry.definitions()
        assertEquals(13, definitions.size)
        definitions.forEach { definition ->
            val schema = definition.function.parameters
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertTrue(schema.containsKey("properties"))
            assertEquals(
                false,
                schema["additionalProperties"]!!.jsonPrimitive.content.toBoolean()
            )
        }
        // 指纹稳定且非空
        assertEquals(registry.schemaSignature(), registry.schemaSignature())
        assertEquals(64, registry.schemaSignature().length)
    }

    @Test
    fun `schema fingerprint changes when description changes`() {
        val a = ToolRegistry(listOf(fakeTool("web_search", "说明 A")))
        val b = ToolRegistry(listOf(fakeTool("web_search", "说明 B")))
        assertFalse(a.schemaSignature() == b.schemaSignature())
    }

    @Test
    fun `sse parser and assembler work together for streamed tool calls`() = runBlocking {
        val sse = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":"}}]}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"DeepSeek\"}"}}]}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":3}}

            data: [DONE]
        """.trimIndent()
        val chunks = ChatStream.parse(sse.toResponseBody("text/event-stream".toMediaType())).toList()
        val assembler = NativeToolCallAssembler()
        chunks.forEach { assembler.acceptAll(it.choices.firstOrNull()?.delta?.toolCalls) }
        val batch = assembler.finish()
        assertTrue(batch.canExecute)
        assertEquals("call_1", batch.calls.single().id)
        assertEquals("web_search", batch.calls.single().name)
        assertEquals("{\"query\":\"DeepSeek\"}", batch.calls.single().arguments)
        assertEquals(12, chunks.last().usage!!.promptTokens)
    }

    @Test
    fun `function definition has no accidental strict flag`() {
        val def = FunctionDefinition("web_search", "desc", JsonObject(emptyMap()))
        assertNotNull(def)
        assertFalse(json.encodeToString(def).contains("strict"))
    }

    private fun fakeTool(name: String, description: String = "工具 $name"): AssistantTool =
        object : AssistantTool {
            override val name = name
            override val description = description
            override suspend fun execute(args: JsonObject): ToolOutcome =
                ToolOutcome.Success("ok")
        }
}
