package com.example.assistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolLeakFilterTest {

    @Test
    fun `normal prose is not withheld or considered a leak`() {
        assertFalse(ProtocolLeakFilter.shouldWithhold("你好，我来帮你"))
        assertFalse(ProtocolLeakFilter.hasLeak("你好，我来帮你"))
        assertFalse(ProtocolLeakFilter.shouldWithhold("<b>普通 HTML</b>"))
    }

    @Test
    fun `leading marker variants are withheld`() {
        assertTrue(ProtocolLeakFilter.shouldWithhold(""))
        assertTrue(ProtocolLeakFilter.shouldWithhold("["))
        assertTrue(ProtocolLeakFilter.shouldWithhold("[调用] {\"tool\":\"x\"}"))
        assertTrue(ProtocolLeakFilter.shouldWithhold("<｜DSM"))
        assertTrue(ProtocolLeakFilter.shouldWithhold("<|tool▁calls▁begin|>"))
    }

    @Test
    fun `dsml tool block is stripped entirely`() {
        val raw = """
            先说一句。
            <｜DSML｜tool_calls>
            <｜DSML｜invoke name="web_search">
            <｜DSML｜parameter name="query" string="true">x</｜DSML｜parameter>
            </｜DSML｜invoke>
            </｜DSML｜tool_calls>
            后面还有正文。
        """.trimIndent()
        assertTrue(ProtocolLeakFilter.hasLeak(raw))
        val clean = ProtocolLeakFilter.strip(raw)
        assertEquals("先说一句。\n\n后面还有正文。", clean)
        assertFalse(clean.contains("DSML", ignoreCase = true))
    }

    @Test
    fun `double bar dsml dialect is also stripped`() {
        val raw = """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="web_search">
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>
            正常回答
        """.trimIndent()
        val clean = ProtocolLeakFilter.strip(raw)
        assertEquals("正常回答", clean)
        assertFalse(clean.contains("DSML", ignoreCase = true))
    }

    @Test
    fun `legacy call line is removed`() {
        val clean = ProtocolLeakFilter.strip(
            "[调用] {\"tool\":\"web_search\",\"args\":{\"query\":\"x\"}}\n正常回答"
        )
        assertEquals("正常回答", clean)
    }

    @Test
    fun `legacy multiline payload does not leak json`() {
        val raw = """
            先说明一句
            [调用] {
              "tool":"web_search",
              "args":{"query":"x"}
            }
            正常回答
        """.trimIndent()
        val clean = ProtocolLeakFilter.strip(raw)
        assertFalse(clean.contains("\"tool\""))
        assertFalse(clean.contains("[调用]"))
        assertTrue(clean.contains("先说明一句"))
        assertTrue(clean.contains("正常回答"))
    }

    @Test
    fun `special token is removed with its full suffix`() {
        val raw = "<|tool▁calls▁begin|>正常回答"
        val clean = ProtocolLeakFilter.strip(raw)
        assertEquals("正常回答", clean)
        assertFalse(clean.contains("calls▁begin"))
    }

    @Test
    fun `plain angle brackets are preserved`() {
        assertEquals("<b>加粗</b>", ProtocolLeakFilter.strip("<b>加粗</b>"))
    }
}
