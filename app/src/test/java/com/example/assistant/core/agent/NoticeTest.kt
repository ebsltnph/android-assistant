package com.example.assistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘性通知（2026-09-17「进行中的事」）。
 *
 * 用户在界面上改长期记忆 / 缓冲区时**不能直接改注入块**——那会让提示词前缀立刻变、
 * 厂商缓存整段失效（主力模型未命中价是命中价的 50 倍）。做法是往**当前最后一轮**追加一条
 * 「[系统] …」通知：追加在尾部 = 纯延长 = 零未命中，模型下一轮就看到。
 *
 * 这里守住三件事：① 通知渲染在本轮助手消息之后（位置固定，不会被后续轮次顶着往后跑）；
 * ② "重做"（清空/替换助手消息）**不会**把通知清掉；③ 合并时能整体清干净。
 */
class NoticeTest {

    @Test
    fun `notice is rendered after the assistant messages of the last turn`() {
        val s = Session()
        val id = s.beginTurn("帮我看看这个")
        s.appendAssistant(id, com.example.assistant.core.network.dto.ChatMessage("assistant", "看完了"))
        assertTrue(s.appendNotice("[系统] 用户手动更新了长期记忆：新增了一条「在做实验」"))

        val msgs = s.allTurns().single().messages()
        assertEquals(3, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("assistant", msgs[1].role)
        assertEquals("user", msgs[2].role)
        assertTrue(msgs[2].textContent.startsWith("[系统]"))
    }

    @Test
    fun `regenerate helpers do not touch notices`() {
        val s = Session()
        val id = s.beginTurn("问题")
        s.appendAssistant(id, com.example.assistant.core.network.dto.ChatMessage("assistant", "旧回答"))
        s.appendNotice("[系统] 记忆已更新")

        // 重做：清空助手消息 / 替换最后一条助手消息
        s.clearAssistant(id)
        assertTrue(s.hasNotices())
        s.appendAssistant(id, com.example.assistant.core.network.dto.ChatMessage("assistant", "新回答"))
        s.replaceLastAssistant("新回答v2")

        assertEquals(1, s.allTurns().single().notices.size)
        assertTrue(s.allTurns().single().messages().last().textContent.startsWith("[系统]"))
    }

    @Test
    fun `appendNotice fails when there is no turn to attach to`() {
        val s = Session()
        assertFalse(s.appendNotice("[系统] 记忆已更新"))
        assertFalse(s.hasNotices())
    }

    @Test
    fun `stripNotices clears everything at the merge point`() {
        val s = Session()
        s.beginTurn("一")
        s.appendNotice("[系统] A")
        s.beginTurn("二")
        s.appendNotice("[系统] B")
        assertTrue(s.hasNotices())
        s.stripNotices()
        assertFalse(s.hasNotices())
        // 轮本身不受影响
        assertEquals(2, s.turnCount)
    }

    @Test
    fun `notice survives the persistence round trip`() {
        val s = Session()
        val id = s.beginTurn("问题")
        s.appendNotice("[系统] 用户手动更新了「进行中的事」：#id=7 已归档")

        // 模拟落盘 → 恢复（StoredTurn.notices）
        val stored = s.allTurns().single()
        val restored = Session().turnFromStored(
            id = stored.id,
            userText = stored.userText,
            imagePath = stored.imagePath,
            assistantTexts = stored.assistant.map { it.textContent },
            createdAt = stored.createdAt,
            notices = stored.notices
        )
        assertEquals(1, restored.notices.size)
        assertTrue(restored.messages().last().textContent.contains("#id=7"))
    }
}
