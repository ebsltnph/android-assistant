package com.example.assistant.core.agent

import com.example.assistant.core.network.dto.ChatMessage
import com.example.assistant.core.network.dto.ChatToolCall
import com.example.assistant.core.network.dto.ContentPart
import com.example.assistant.core.network.dto.FunctionCall
import com.example.assistant.core.storage.StoredAssistantMeta
import com.example.assistant.core.storage.StoredChat
import com.example.assistant.core.storage.StoredToolCall
import com.example.assistant.core.storage.StoredTurn
import com.example.assistant.core.storage.toStoredToolCall
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionPersistenceTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `native tool call and tool result survive snapshot round trip`() {
        val assistant = ChatMessage(
            role = "assistant",
            content = emptyList(),
            reasoningContent = "需要先搜索",
            toolCalls = listOf(
                ChatToolCall(
                    id = "call_1",
                    type = "function",
                    function = FunctionCall("web_search", "{\"query\":\"DeepSeek\"}")
                )
            )
        )
        val toolResult = ChatMessage(
            role = "tool",
            content = listOf(ContentPart.text("搜索结果")),
            toolCallId = "call_1"
        )
        val final = ChatMessage("assistant", "最终回答")

        val stored = StoredTurn(
            id = 7,
            userText = "[2026-11-13 10:00] 搜一下",
            assistant = listOf(assistant.textContent, toolResult.textContent, final.textContent),
            assistantRoles = listOf(assistant.role, toolResult.role, final.role),
            assistantMeta = listOf(assistant, toolResult, final).map { msg ->
                StoredAssistantMeta(
                    role = msg.role,
                    reasoningContent = msg.reasoningContent.orEmpty(),
                    toolCalls = msg.toolCalls.orEmpty().map { it.toStoredToolCall() },
                    toolCallId = msg.toolCallId.orEmpty()
                )
            }
        )

        // 文件级 round trip（模拟重启）
        val snapshot = StoredChat(protocolVersion = 2, turns = listOf(stored))
        val decoded = json.decodeFromString<StoredChat>(json.encodeToString(snapshot))
        val st = decoded.turns.single()

        val session = Session()
        val rebuilt = session.turnFromStored(
            id = st.id,
            userText = st.userText,
            assistantTexts = st.assistant,
            assistantRoles = st.assistantRoles,
            assistantMeta = st.assistantMeta,
            stripLegacyProtocol = false
        )

        assertEquals(3, rebuilt.assistant.size)
        val a = rebuilt.assistant[0]
        assertEquals("assistant", a.role)
        assertEquals("需要先搜索", a.reasoningContent)
        assertEquals("call_1", a.toolCalls!!.single().id)
        assertEquals("web_search", a.toolCalls!!.single().function!!.name)
        assertEquals(
            "{\"query\":\"DeepSeek\"}",
            a.toolCalls!!.single().function!!.arguments
        )

        val t = rebuilt.assistant[1]
        assertEquals("tool", t.role)
        assertEquals("call_1", t.toolCallId)
        assertEquals("搜索结果", t.textContent)

        // 发出给模型的形态：tool-only assistant 不能被过滤掉，且原生字段都在
        val messages = rebuilt.messages()
        assertEquals(4, messages.size) // user + assistant + tool + final
        assertEquals("call_1", messages[1].toolCalls!!.single().id)
        assertEquals("call_1", messages[2].toolCallId)
        assertEquals("最终回答", messages[3].textContent)
    }

    @Test
    fun `legacy text tool intermediates are dropped from model context only`() {
        val rebuilt = Session().turnFromStored(
            id = 1,
            userText = "[2026-11-13 10:00] 旧会话",
            assistantTexts = listOf(
                "[调用] {\"tool\":\"web_search\",\"args\":{\"query\":\"x\"}}",
                "[结果] 搜索完成",
                "最终回答",
                "另一个完整回答"
            ),
            assistantRoles = listOf("assistant", "user", "assistant", "assistant"),
            stripLegacyProtocol = true
        )
        assertEquals(2, rebuilt.assistant.size)
        assertEquals(listOf("最终回答", "另一个完整回答"), rebuilt.assistant.map { it.textContent })
    }

    @Test
    fun `legacy mixed prose call round is dropped after detection on any line`() {
        val rebuilt = Session().turnFromStored(
            id = 1,
            userText = "q",
            assistantTexts = listOf(
                "我先查一下。\n[调用] {\"tool\":\"web_search\",\"args\":{}}",
                "[结果] 结果",
                "最终"
            ),
            assistantRoles = listOf("assistant", "user", "assistant"),
            stripLegacyProtocol = true
        )
        assertEquals(listOf("最终"), rebuilt.assistant.map { it.textContent })
    }

    @Test
    fun `new snapshots do not drop user-role replay messages`() {
        val rebuilt = Session().turnFromStored(
            id = 1,
            userText = "看图",
            assistantTexts = listOf("图片", "最终回答"),
            assistantRoles = listOf("user", "assistant"),
            stripLegacyProtocol = false
        )
        assertEquals(2, rebuilt.assistant.size)
        assertEquals("user", rebuilt.assistant[0].role)
        assertEquals("最终回答", rebuilt.assistant[1].textContent)
    }

    @Test
    fun `char weight includes reasoning and tool arguments`() {
        val turn = Session.Turn(
            id = 1,
            userText = "[2026-11-13 10:00] q",
            assistant = mutableListOf(
                ChatMessage(
                    role = "assistant",
                    content = emptyList(),
                    reasoningContent = "很长的思考".repeat(20),
                    toolCalls = listOf(
                        ChatToolCall(
                            id = "call_123",
                            type = "function",
                            function = FunctionCall("web_search", "{\"query\":\"" + "x".repeat(100) + "\"}")
                        )
                    )
                ),
                ChatMessage(
                    role = "tool",
                    content = listOf(ContentPart.text("结果".repeat(30))),
                    toolCallId = "call_123"
                )
            )
        )
        val plainTextWeight = turn.userText!!.length + turn.assistant.sumOf { it.textContent.length }
        assertTrue(turn.charWeight() > plainTextWeight + 200)
    }

    @Test
    fun `stored tool call keeps malformed arguments verbatim`() {
        val meta = StoredAssistantMeta(
            role = "assistant",
            toolCalls = listOf(StoredToolCall("call_1", "web_search", "{\"query\":")),
            reasoningContent = ""
        )
        val rebuilt = Session().turnFromStored(
            id = 1,
            userText = "q",
            assistantTexts = listOf(""),
            assistantRoles = listOf("assistant"),
            assistantMeta = listOf(meta)
        )
        assertNotNull(rebuilt.assistant.single().toolCalls)
        assertEquals("{\"query\":", rebuilt.assistant.single().toolCalls!!.single().function!!.arguments)
        assertNull(rebuilt.assistant.single().reasoningContent)
    }

    @Test
    fun `stored chat defaults to legacy protocol version`() {
        val decoded = json.decodeFromString<StoredChat>("""{"turns":[]}""")
        assertEquals(1, decoded.protocolVersion)
    }
}
