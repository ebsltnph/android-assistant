package com.example.assistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台生成失败的处理（2026-09-23 用户要求）：
 * **输出失败原因 + 15 分钟后自动重试一次（只一次）**。
 *
 * 这里守两件事：① 失败时给用户的文案必须带原因；② 只有"重试有用"的失败才注明会自动重试
 * （未配置模型这种重试也没用的，不要给用户"马上会好"的错觉）。
 */
class GenerationOutcomeTest {

    private val failed = GenerationOutcome.Failed(
        reason = "HTTP 500：上游超时",
        fallback = "今天写了 3 条日记，点开「日记」页看看吧\n（生成失败：HTTP 500：上游超时）"
    )

    @Test
    fun `failure text keeps the reason and announces the retry`() {
        val text = failed.noticeText(willRetry = true)
        assertTrue(text.contains("生成失败：HTTP 500：上游超时"))
        assertTrue(text.contains("15 分钟后会自动重试一次"))
    }

    @Test
    fun `no retry note when the retry is not scheduled`() {
        val text = failed.noticeText(willRetry = false)
        assertTrue(text.contains("生成失败"))
        assertFalse(text.contains("自动重试"))
    }

    @Test
    fun `unconfigured model is marked as not retryable`() {
        val out = GenerationOutcome.Failed("未配置对话模型", "（未配置对话模型，请到「设置」添加）", retryable = false)
        assertFalse(out.retryable)
        assertFalse(out.noticeText(willRetry = out.retryable).contains("自动重试"))
    }

    @Test
    fun `only one automatic retry is allowed`() {
        // 首次运行 runAttemptCount = 0 → 可以重试；重试那次 = 1 → 不再重试
        fun willRetry(attempt: Int) = attempt < GENERATION_MAX_RETRY
        assertTrue(willRetry(0))
        assertFalse(willRetry(1))
        assertFalse(willRetry(2))
    }

    @Test
    fun `success carries the text and no data is silent`() {
        assertEquals("正文", (GenerationOutcome.Ok("正文") as GenerationOutcome.Ok).text)
        assertEquals(GenerationOutcome.NoData, GenerationOutcome.NoData)
    }
}
