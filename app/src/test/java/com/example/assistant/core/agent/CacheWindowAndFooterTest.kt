package com.example.assistant.core.agent

import com.example.assistant.core.agent.tools.stripFakeFooterLines
import com.example.assistant.core.network.dto.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：2026-09-14 用户报告的三件事。
 *
 * 1) 上下文窗口回落：原来只有"轮数到上限"才回落到下限。长期记忆被改、静态前缀变了、
 *    字符软上限被触发……这些同样让厂商缓存的前缀整段失效，也该回落到下限重新起跑。
 * 2) 一条回复里出现好几条「🔧 已执行：…」（工具实际只执行了一次）——
 *    根因是系统页脚被写进会话历史，模型学着在正文里自己编。
 * 3) 状态行的缓存命中率改为**整轮对话**（多次请求）的合计，而不是最后一次请求。
 */
class CacheWindowAndFooterTest {

    // ---- 1：窗口回落到下限的三种触发条件 ----

    @Test
    fun `prefix change falls back to min turns`() {
        val s = Session()
        repeat(8) { s.beginTurn("第 $it 句话") }
        // 首次请求只登记指纹，窗口不动（此时还没有"上一次"可比）
        assertEquals(8, s.buildContext(5, 20, 0, "sig-A").turns)
        // 指纹没变 → 窗口照旧（只是延长）
        val same = s.buildContext(5, 20, 0, "sig-A")
        assertEquals(8, same.turns)
        assertNull(same.windowReset)
        // 指纹变了（长期记忆/提示词被改）→ 回落到下限 5 轮，并给出原因
        val changed = s.buildContext(5, 20, 0, "sig-B")
        assertEquals(5, changed.turns)
        assertEquals(Session.RESET_PREFIX, changed.windowReset)
        // 之后继续延长（5 → 6 …），不会每次都重置
        s.beginTurn("新的一轮")
        assertNull(s.buildContext(5, 20, 0, "sig-B").windowReset)
    }

    @Test
    fun `max turns still falls back to min turns`() {
        val s = Session()
        repeat(4) { s.beginTurn("第 $it 句话") }
        val ctx = s.buildContext(2, 3, 0)
        assertEquals(2, ctx.turns)
        assertEquals(Session.RESET_MAX_TURNS, ctx.windowReset)
    }

    @Test
    fun `soft cap falls back to min turns instead of just enough`() {
        val s = Session()
        repeat(8) { s.beginTurn("x".repeat(100)) }   // 每轮约 118 字（含时间戳前缀）
        // 上限 400 字：8 轮远超 → 不是"丢到刚好装下"，而是直接回落到下限 3 轮
        val ctx = s.buildContext(3, 20, 400)
        assertEquals(3, ctx.turns)
        assertTrue(ctx.trimmedBySoftCap)
        assertEquals(Session.RESET_SOFT_CAP, ctx.windowReset)
    }

    @Test
    fun `soft cap below min keeps dropping to one turn`() {
        val s = Session()
        repeat(8) { s.beginTurn("x".repeat(300)) }
        // 下限 3 轮自身也超限 → 继续丢，但至少留最近 1 轮
        val ctx = s.buildContext(3, 20, 200)
        assertEquals(1, ctx.turns)
        assertTrue(ctx.trimmedBySoftCap)
    }

    // ---- 2：模型仿写的「已执行」清单行 ----

    @Test
    fun `model written footer lines are stripped`() {
        val text = "设好了。\n\n" +
            "🔧 已执行：日记「今晚去上经济学原理讨论班」\n" +
            "🔧 已执行：提醒「乐团排练」9月19日 18:00、提醒「乐团排练」9月20日 14:00\n" +
            "已执行：记忆「用户每周六晚 18:00」"
        assertEquals("设好了。", stripFakeFooterLines(text))
    }

    @Test
    fun `normal prose mentioning execution is kept`() {
        val text = "今天的已执行计划是跑步三公里。\n程序已经执行完毕，没有报错。"
        assertEquals(text, stripFakeFooterLines(text))
    }

    // ---- 3：一轮对话的用量合计 ----

    @Test
    fun `usage is summed across the requests of one turn`() {
        val first = Usage(promptTokens = 1000, promptCacheHitTokens = 800)
        val second = Usage(promptTokens = 2000, promptCacheHitTokens = 1900)
        val sum = accumulateUsage(accumulateUsage(null, first), second)!!
        assertEquals(3000, sum.promptTokens)
        assertEquals(2700, sum.cachedTokens)
    }

    @Test
    fun `openai style cached tokens are summed too`() {
        val deepseek = Usage(promptTokens = 1000, promptCacheHitTokens = 500)
        val openai = Usage(
            promptTokens = 4000,
            promptTokensDetails = com.example.assistant.core.network.dto.PromptTokensDetails(3000)
        )
        val sum = accumulateUsage(accumulateUsage(null, deepseek), openai)!!
        assertEquals(5000, sum.promptTokens)
        assertEquals(3500, sum.cachedTokens)
        // 界面状态行用的是同一套口径（合计后算百分比）
        val status = com.example.assistant.feature.chat.ContextStatus(
            promptTokens = sum.promptTokens,
            cachedTokens = sum.cachedTokens
        )
        assertEquals(70, status.cacheHitPercent)
    }

    @Test
    fun `usage stays null when the provider reports nothing`() {
        assertNull(accumulateUsage(null, null))
        assertEquals(1200, accumulateUsage(null, Usage(promptTokens = 1200))!!.promptTokens)
    }
}
