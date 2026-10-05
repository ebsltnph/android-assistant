package com.example.assistant.core.agent

import com.example.assistant.core.agent.tools.UpdateDiaryTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：2026-09-11 用户报告的两个 bug。
 *
 * 1) 历史图片保留设成「仅当前轮」（keep=0）时，当前轮的图片也发不出去
 *    —— `enforceImageRetention` 原来写 `dropLast(keep)`，keep=0 时 dropLast(0) 是**整个列表**。
 * 2) `update_diary` 只靠 id 定位，模型记错编号就会默默改掉另一条日记
 *    —— 现在必须复述该条当前正文（old_content）并核对通过。
 */
class SessionAndDiaryGuardTest {

    // ---- bug 1：图片保留策略 ----

    @Test
    fun `keep 0 keeps the current image`() {
        val s = Session()
        s.beginTurn("看图", listOf("/tmp/a.jpg"))
        s.enforceImageRetention(0)
        assertEquals(listOf("/tmp/a.jpg"), s.lastTurn()!!.imagePaths)
    }

    @Test
    fun `keep 0 keeps all images of the current turn`() {
        val s = Session()
        s.beginTurn("图1", listOf("/tmp/a.jpg"))
        s.beginTurn("图2", listOf("/tmp/b.jpg", "/tmp/c.jpg"))
        s.enforceImageRetention(0)
        assertEquals(emptyList<String>(), s.allTurns()[0].imagePaths)
        assertEquals(listOf("/tmp/b.jpg", "/tmp/c.jpg"), s.allTurns()[1].imagePaths)
    }

    @Test
    fun `keep 0 drops the image once a text turn starts`() {
        val s = Session()
        s.beginTurn("图1", listOf("/tmp/a.jpg"))
        s.enforceImageRetention(0)
        s.beginTurn("那张图里写了什么")
        s.enforceImageRetention(0)
        assertEquals(emptyList<String>(), s.allTurns()[0].imagePaths)
    }

    @Test
    fun `legacy keep 1 is treated as current-turn-only`() {
        // 旧的"最近 1 张"中间档已删除：任何正数都按「仅当前轮」处理（防止升级后行为错乱）
        val s = Session()
        s.beginTurn("图1", listOf("/tmp/a.jpg"))
        s.beginTurn("那张图里写了什么")
        s.enforceImageRetention(1)
        assertEquals(emptyList<String>(), s.allTurns()[0].imagePaths)
    }

    @Test
    fun `keep 0 drops tool-injected images on later turns`() {
        // 工具注入的日记图片（read_diary_image）与用户图片同一套保留策略
        val s = Session()
        val id = s.beginTurn("看看日记里的图")
        s.appendToolResult(id, "[结果] 图片已附上", listOf("/tmp/d1.jpg", "/tmp/d2.jpg"))
        s.enforceImageRetention(0)   // 当前轮：保留
        assertEquals(1, s.lastTurn()!!.assistantImagePaths.count { it.isNotEmpty() })
        s.beginTurn("下一轮")
        s.enforceImageRetention(0)   // 历史轮：清空
        assertEquals(0, s.allTurns()[0].assistantImagePaths.count { it.isNotEmpty() })
    }

    @Test
    fun `keep -1 keeps tool-injected images across turns`() {
        val s = Session()
        val id = s.beginTurn("看看日记里的图")
        s.appendToolResult(id, "[结果] 图片已附上", listOf("/tmp/d1.jpg"))
        s.beginTurn("下一轮")
        s.enforceImageRetention(-1)
        assertEquals(1, s.allTurns()[0].assistantImagePaths.count { it.isNotEmpty() })
    }

    @Test
    fun `assistant and assistantImagePaths stay aligned`() {
        val s = Session()
        val id = s.beginTurn("问题")
        s.appendAssistant(id, com.example.assistant.core.network.dto.ChatMessage("assistant", "回答"))
        s.appendToolResult(id, "[结果] 有图", listOf("/tmp/d1.jpg"))
        s.appendAssistant(id, com.example.assistant.core.network.dto.ChatMessage("assistant", "最终"))
        val t = s.allTurns().single()
        assertEquals(t.assistant.size, t.assistantImagePaths.size)
        assertEquals(3, t.assistant.size)
        assertEquals(listOf("/tmp/d1.jpg"), t.assistantImagePaths[1])
    }

    @Test
    fun `keep -1 keeps everything`() {
        val s = Session()
        s.beginTurn("图1", listOf("/tmp/a.jpg"))
        s.beginTurn("图2", listOf("/tmp/b.jpg"))
        s.beginTurn("图3", listOf("/tmp/c.jpg"))
        s.enforceImageRetention(-1)
        assertEquals(3, s.allTurns().count { it.imagePaths.isNotEmpty() })
    }

    // ---- bug 2：改日记前的正文核对 ----

    private val actual = "新的损耗测定：从准直器之前到最后接收，损耗约 0.6 dB；偏振控制器损耗为 0.2 dBm。"

    @Test
    fun `exact content matches`() {
        assertTrue(UpdateDiaryTool.contentMatches(actual, actual))
    }

    @Test
    fun `whitespace differences are tolerated`() {
        assertTrue(UpdateDiaryTool.contentMatches("  新的损耗测定：从准直器之前到最后接收，损耗约 0.6 dB；\n偏振控制器损耗为 0.2 dBm。  ", actual))
    }

    @Test
    fun `truncated list copy (long prefix) matches`() {
        // 取 40 字：归一化会去掉空白，门槛 30 是在**去空白后**比较的
        assertTrue(UpdateDiaryTool.contentMatches(actual.take(40), actual))
    }

    @Test
    fun `wrong entry content is rejected`() {
        assertFalse(UpdateDiaryTool.contentMatches("今天豆汁儿局唱了 5 个小时，到凌晨三点多。", actual))
    }

    @Test
    fun `too short prefix is rejected`() {
        assertFalse(UpdateDiaryTool.contentMatches(actual.take(10), actual))
    }

    @Test
    fun `blank content is rejected`() {
        assertFalse(UpdateDiaryTool.contentMatches("   ", actual))
    }
}
