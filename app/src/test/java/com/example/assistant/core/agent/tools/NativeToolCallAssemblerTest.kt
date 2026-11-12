package com.example.assistant.core.agent.tools

import com.example.assistant.core.network.dto.ChatToolCall
import com.example.assistant.core.network.dto.FunctionCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolCallAssemblerTest {

    private fun call(
        index: Int?,
        id: String? = null,
        name: String? = null,
        args: String? = null
    ) = ChatToolCall(
        id = id.orEmpty(),
        type = "function",
        function = if (name == null && args == null) null else FunctionCall(name, args),
        index = index
    )

    @Test
    fun `arguments are appended across chunks`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, "call_1", "web_search", "{\"query\":"))
            accept(call(0, args = "\"Kotlin\"}"))
        }.finish()
        assertTrue(a.canExecute)
        assertEquals("{\"query\":\"Kotlin\"}", a.calls.single().arguments)
        assertEquals("web_search", a.calls.single().name)
    }

    @Test
    fun `empty continuation identity does not erase established id or name`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, "call_1", "web_search", "{}"))
            accept(call(0, "", "", ""))
            accept(call(0, id = null, name = null, args = ""))
        }.finish()
        assertTrue(a.canExecute)
        assertEquals("call_1", a.calls.single().id)
        assertEquals("web_search", a.calls.single().name)
    }

    @Test
    fun `parallel calls are independent by index`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, "a", "web_search", "{\"query\":\"x\""))
            accept(call(1, "b", "read_webpage", "{\"url\":\"https"))
            accept(call(0, "", "", "}"))
            accept(call(1, args = "://e.com\"}"))
        }.finish()
        assertTrue(a.canExecute)
        assertEquals(listOf("a", "b"), a.calls.map { it.id })
        assertEquals("{\"query\":\"x\"}", a.calls[0].arguments)
        assertEquals("{\"url\":\"https://e.com\"}", a.calls[1].arguments)
    }

    @Test
    fun `missing index can still start a new call by new non-empty id`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(null, "a", "web_search", "{}"))
            accept(call(null, "b", "read_webpage", "{}"))
        }.finish()
        assertEquals(2, a.calls.size)
        assertEquals(listOf("a", "b"), a.calls.map { it.id })
    }

    @Test
    fun `missing index args are rejected when parallel calls exist`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, id = "a", name = "web_search", args = "{\"query\":"))
            accept(call(1, id = "b", name = "read_webpage", args = "{\"url\":"))
            accept(call(index = null, args = "\"x\"}"))
        }.finish()
        assertFalse(a.canExecute)
        assertTrue(a.protocolErrors.any { it.contains("缺少 index") })
    }

    @Test
    fun `missing identity blocks execution`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, id = "", name = "web_search", args = "{}"))
        }.finish()
        assertFalse(a.canExecute)
        assertTrue(a.protocolErrors.any { it.contains("缺少 id") })
    }

    @Test
    fun `duplicate ids block execution`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, "same", "web_search", "{}"))
            accept(call(1, "same", "read_webpage", "{}"))
        }.finish()
        assertFalse(a.canExecute)
        assertTrue(a.protocolErrors.any { it.contains("重复") })
    }

    @Test
    fun `invalid argument json is kept but parse returns null`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, "a", "web_search", "{\"query\":"))
        }.finish()
        assertTrue(a.canExecute)
        assertNull(a.calls.single().parsedArgs())
        assertEquals("{\"query\":", a.calls.single().arguments)
    }

    @Test
    fun `empty arguments parse as empty object`() {
        val a = NativeToolCallAssembler().apply {
            accept(call(0, "a", "list_reminders", ""))
        }.finish()
        assertEquals(0, a.calls.single().parsedArgs()!!.size)
    }

    @Test
    fun `reset clears previous stream`() {
        val assembler = NativeToolCallAssembler().apply {
            accept(call(0, "a", "web_search", "{}"))
            reset()
            accept(call(0, "b", "read_webpage", "{}"))
        }
        assertEquals(listOf("b"), assembler.finish().calls.map { it.id })
    }
}
