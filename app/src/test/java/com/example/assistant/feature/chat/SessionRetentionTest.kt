package com.example.assistant.feature.chat

import com.example.assistant.core.storage.ChatSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「会话记录保留」自动清理的判定（2026-09-17 用户要求）。
 *
 * 用户拍板的规则：**同时满足两条才删**——
 *  ① 该轮创建时刻超过保留天数；
 *  ② 该轮已经不在给模型的上下文里（还在窗口里的先留着，否则下一次请求的提示词前缀就变了，
 *     厂商缓存整段失效）。
 */
class SessionRetentionTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 3600_000L

    @Test
    fun `expired and out of context is deleted`() {
        val created = mapOf(
            1L to now - 9 * day,   // 过期 + 已不在上下文 → 删
            2L to now - 2 * day    // 未过期
        )
        val ids = expiredTurnIds(created, contextTurnIds = setOf(2L), cutoffMillis = now - 7 * day)
        assertEquals(setOf(1L), ids)
    }

    @Test
    fun `expired but still in context is kept`() {
        val created = mapOf(1L to now - 30 * day)
        // 还在上下文窗口里（用户明确要求：不得为了清理而破坏缓存前缀）
        val ids = expiredTurnIds(created, contextTurnIds = setOf(1L), cutoffMillis = now - 7 * day)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `out of context but not expired is kept`() {
        val created = mapOf(1L to now - day)
        val ids = expiredTurnIds(created, contextTurnIds = emptySet(), cutoffMillis = now - 7 * day)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `unknown timestamp is never deleted`() {
        // createdAt = 0（旧快照解析不出时间）→ 宁可留着也不误删
        val created = mapOf(1L to 0L, 2L to -1L)
        val ids = expiredTurnIds(created, contextTurnIds = emptySet(), cutoffMillis = now)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `exact cutoff boundary is expired`() {
        val created = mapOf(1L to now - 7 * day)
        val ids = expiredTurnIds(created, contextTurnIds = emptySet(), cutoffMillis = now - 7 * day)
        assertEquals(setOf(1L), ids)
    }

    @Test
    fun `old snapshot falls back to the timestamp prefix`() {
        // 旧快照没有 createdAt 字段：从用户消息的 [yyyy-MM-dd HH:mm] 前缀还原
        val at = ChatSessionStore.parseStampedAt("[2026-09-11 10:30] 帮我记一下")
        assertTrue(at > 0L)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        assertEquals("2026-09-11 10:30", fmt.format(java.util.Date(at)))
        // 助手独占轮（没有用户消息）解析不出来 → 0 = 保留
        assertEquals(0L, ChatSessionStore.parseStampedAt(null))
        assertEquals(0L, ChatSessionStore.parseStampedAt("📔 已记入日记本"))
    }

    @Test
    fun `orphan messages from an old snapshot get the oldest known turn time`() {
        // 旧快照里"轮已被裁掉、也没存过时刻"的孤儿消息：用现存最旧一轮的时刻当上界估计
        // （真实时刻只会更早 ⇒ 年龄被低估 ⇒ 只会删得更晚，不会误删）
        val oldest = now - 3 * day
        assertEquals(oldest, estimateLegacyCreatedAt(listOf(now - day, oldest, now), savedAt = now))
        // 一轮都没有 → 退回快照保存时刻（同样是上界估计，只会删得更晚）
        assertEquals(now, estimateLegacyCreatedAt(emptyList(), savedAt = now))
        // 全 0（时间未知）也不能当成"非常老"，退回 savedAt
        assertEquals(now, estimateLegacyCreatedAt(listOf(0L, -5L), savedAt = now))
    }
}
