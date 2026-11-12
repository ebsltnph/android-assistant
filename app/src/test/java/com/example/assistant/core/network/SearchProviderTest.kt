package com.example.assistant.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SearchProviderTest {

    @Test
    fun `deepseek query maps time range and domains to official syntax`() {
        val query = buildDeepSeekQuery(
            WebSearchRequest(
                query = "DeepSeek 发布",
                topic = "news",
                timeRange = "week",
                includeDomains = "api-docs.deepseek.com, github.com"
            ),
            today = LocalDate.of(2026, 11, 13)
        )
        assertEquals(
            "DeepSeek 发布 after:2026-11-06 site:api-docs.deepseek.com OR site:github.com",
            query
        )
    }

    @Test
    fun `deepseek query leaves plain query unchanged`() {
        assertEquals(
            "天气",
            buildDeepSeekQuery(WebSearchRequest("天气"), LocalDate.of(2026, 11, 13))
        )
    }

    @Test
    fun `deepseek query enforces unicode length`() {
        validateDeepSeekQuery("a".repeat(128))
        val error = runCatching { validateDeepSeekQuery("a".repeat(129)) }.exceptionOrNull()
        assertTrue(error is WebApiException)
        assertEquals(ToolErrorKind.INVALID, ToolErrorKind.of(error as WebApiException))
    }

    @Test
    fun `deepseek result prefers summary then snippet`() {
        val summary = DeepSeekSearchResult(
            title = "标题", url = "https://a.example", snippet = "短", summary = "长摘要",
            date = "2026-11-01"
        ).toSearchResult()
        assertEquals("长摘要", summary.content)
        assertEquals(WebProvider.DEEPSEEK, summary.provider)
        assertEquals("2026-11-01", summary.publishedAt)

        val snippet = DeepSeekSearchResult(
            title = "", url = "https://b.example", snippet = "只有短摘要", summary = ""
        ).toSearchResult()
        assertEquals("只有短摘要", snippet.content)
        assertEquals("https://b.example", snippet.title)
    }

    @Test
    fun `tavily result maps to unified model`() {
        val result = TavilyResult("标题", "https://x.example", "摘要", 0.9).toSearchResult()
        assertEquals(WebProvider.TAVILY, result.provider)
        assertEquals("摘要", result.content)
        assertEquals(0.9, result.score, 0.0001)
    }

    @Test
    fun `http status maps to actionable failure kinds`() {
        assertEquals(WebFailureKind.AUTH, mapWebHttpFailure(WebProvider.TAVILY, "搜索", 401, null).kind)
        assertEquals(WebFailureKind.PAYMENT, mapWebHttpFailure(WebProvider.DEEPSEEK, "搜索", 402, null).kind)
        assertEquals(WebFailureKind.POLICY, mapWebHttpFailure(WebProvider.DEEPSEEK, "抓取", 403, null).kind)
        assertEquals(WebFailureKind.NOT_FOUND, mapWebHttpFailure(WebProvider.DEEPSEEK, "抓取", 404, null).kind)
        assertEquals(WebFailureKind.RATE_LIMIT, mapWebHttpFailure(WebProvider.TAVILY, "搜索", 429, null).kind)
        assertEquals(WebFailureKind.SERVER, mapWebHttpFailure(WebProvider.TAVILY, "搜索", 503, null).kind)
        assertEquals(WebFailureKind.INVALID_REQUEST, mapWebHttpFailure(WebProvider.DEEPSEEK, "搜索", 400, null).kind)
    }

    @Test
    fun `tavily domains are sent as an array`() {
        assertEquals(
            listOf("a.com", "b.com", "c.com"),
            parseTavilyDomains(" a.com, b.com，c.com；a.com ")
        )
        assertEquals(null, parseTavilyDomains("  ,， "))
    }

    @Test
    fun `retry-after and html details are bounded`() {
        val e = mapWebHttpFailure(
            WebProvider.TAVILY, "搜索", 429, "<html>rate limited</html>", retryAfter = "3"
        )
        assertEquals(3L, e.retryAfterSeconds)
        assertTrue(e.retryable)
        assertTrue(e.message!!.contains("HTML 错误页"))
        assertEquals(
            WebProvider.TAVILY,
            WebProvider.fromStorage("removed-provider")
        )
    }

    /** 测试辅助：把异常 kind 转成稳定枚举字符串，避免依赖 Kotlin enum 显示名 */
    private enum class ToolErrorKind {
        INVALID, OTHER;

        companion object {
            fun of(e: WebApiException): ToolErrorKind =
                if (e.kind == WebFailureKind.INVALID_REQUEST) INVALID else OTHER
        }
    }
}
