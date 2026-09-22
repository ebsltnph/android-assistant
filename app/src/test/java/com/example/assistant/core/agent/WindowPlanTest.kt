package com.example.assistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口计划 [Session.planWindow] 与执行 [Session.buildContext] 的分离（2026-09-17）。
 *
 * 为什么要拆：回落（裁剪）会把轮从会话里**物理删除**，而「上下文整理」压缩轮恰恰要用那些
 * **即将被删掉的轮**当输入。所以流程必须是：planWindow（只读）→ 压缩 + 合并快照 →
 * buildContext(plan) 真正裁剪。这里守住"planWindow 不改状态"和"plan 与实际裁剪一致"两条。
 */
class WindowPlanTest {

    @Test
    fun `planWindow is read only and predicts the max-turns drop`() {
        val s = Session()
        repeat(8) { s.beginTurn("第 $it 句话") }

        val plan = s.planWindow(2, 3, 0)

        assertEquals(Session.WindowTrigger.MAX_TURNS, plan.trigger)
        assertEquals(Session.RESET_MAX_TURNS, plan.reason)
        assertEquals(2, plan.keptTurns)
        assertEquals(6, plan.willDrop.size)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), plan.willDrop.map { it.id })
        // 关键：只读 —— 会话里仍然是 8 轮
        assertEquals(8, s.turnCount)
    }

    @Test
    fun `buildContext applies exactly the planned drop`() {
        val s = Session()
        repeat(8) { s.beginTurn("第 $it 句话") }
        val plan = s.planWindow(2, 3, 0)

        val ctx = s.buildContext(2, 3, 0, null, plan)

        assertEquals(2, ctx.turns)
        assertEquals(Session.RESET_MAX_TURNS, ctx.windowReset)
        assertEquals(2, s.turnCount)
        // 留下的是最近两轮（计划里没被丢的那些）
        assertTrue(s.allTurns().map { it.id } == listOf(6L, 7L))
    }

    @Test
    fun `still no drop while under the limits`() {
        val s = Session()
        repeat(4) { s.beginTurn("第 $it 句话") }
        val plan = s.planWindow(2, 20, 0)
        assertEquals(Session.WindowTrigger.NONE, plan.trigger)
        assertTrue(plan.willDrop.isEmpty())
        assertNull(plan.reason)
        assertEquals(4, plan.keptTurns)
    }

    @Test
    fun `prefix change plans a fall back to the min turns`() {
        val s = Session()
        repeat(8) { s.beginTurn("第 $it 句话") }
        s.buildContext(5, 20, 0, "sig-A")   // 第一次只登记指纹
        val plan = s.planWindow(5, 20, 0, "sig-B")
        assertEquals(Session.WindowTrigger.PREFIX, plan.trigger)
        assertEquals(Session.RESET_PREFIX, plan.reason)
        assertEquals(3, plan.willDrop.size)   // 8 → 5
        assertEquals(5, plan.keptTurns)
        assertEquals(8, s.turnCount)
    }

    @Test
    fun `soft cap plans a fall back to the min turns`() {
        val s = Session()
        repeat(8) { s.beginTurn("x".repeat(100)) }
        val plan = s.planWindow(3, 20, 400)
        assertEquals(Session.WindowTrigger.SOFT_CAP, plan.trigger)
        assertTrue(plan.trimmedBySoftCap)
        assertEquals(3, plan.keptTurns)
        assertEquals(5, plan.willDrop.size)
    }

    @Test
    fun `soft cap below the min keeps dropping down to one turn`() {
        val s = Session()
        repeat(8) { s.beginTurn("x".repeat(300)) }
        val plan = s.planWindow(3, 20, 200)
        assertEquals(1, plan.keptTurns)
        assertEquals(7, plan.willDrop.size)
        assertTrue(plan.trimmedBySoftCap)
    }

    @Test
    fun `reason override wins for the compaction label`() {
        val s = Session()
        repeat(8) { s.beginTurn("第 $it 句话") }
        val plan = s.planWindow(2, 3, 0)
        val ctx = s.buildContext(2, 3, 0, null, plan, Session.RESET_COMPACTED)
        assertEquals(Session.RESET_COMPACTED, ctx.windowReset)
    }
}
