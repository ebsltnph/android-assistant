package com.example.assistant.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 官方 Search / Fetch 原始 JSON API（可选付费提供商）。
 *
 * - Search: POST https://api.deepseek.com/v1/tools/search
 * - Fetch:  POST https://api.deepseek.com/v1/tools/fetch
 * - 与 DeepSeek 对话 API 相同的 Bearer 鉴权；不经过模型生成答案。
 *
 * 只请求官方端点；不会把第三方网关的 Key 发到这里（Key 由 WebAccessRegistry 解析）。
 * 失败不会回退到 Tavily。
 */
class DeepSeekWebClient(
    /** 每次操作前现取 Key；null/空 = 未配置（直接给出配置错误） */
    private val apiKeyProvider: suspend () -> String?
) : SearchClient, PageReader {

    override val provider = WebProvider.DEEPSEEK

    private val api: DeepSeekWebApi = Retrofit.Builder()
        .baseUrl(DEEPSEEK_BASE_URL)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.asConverterFactory("application/json".toMediaType())
        )
        .build()
        .create(DeepSeekWebApi::class.java)

    override suspend fun search(request: WebSearchRequest): List<SearchResult> {
        val key = keyFor("搜索")
        val query = buildDeepSeekQuery(request)
        validateDeepSeekQuery(query)
        return try {
            val response = withWebReadRetry(provider, "搜索") {
                try {
                    api.search(
                        auth = "Bearer $key",
                        body = DeepSeekSearchRequest(
                            query = query,
                            count = request.maxResults.coerceIn(1, 50),
                            needContent = false
                        )
                    )
                } catch (e: Throwable) {
                    throw webFailureOf(provider, "搜索", e)
                }
            }
            response.results.map { it.toSearchResult() }
        } catch (e: Throwable) {
            throw webFailureOf(provider, "搜索", e)
        }
    }

    override suspend fun extract(url: String): ExtractedPage {
        validateDeepSeekUrl(url)
        val key = keyFor("抓取")
        return try {
            val response = withWebReadRetry(provider, "抓取") {
                try {
                    api.fetch(
                        auth = "Bearer $key",
                        body = DeepSeekFetchRequest(url)
                    )
                } catch (e: Throwable) {
                    throw webFailureOf(provider, "抓取", e)
                }
            }
            val content = response.content.trim()
            if (content.isEmpty()) {
                throw WebApiException(
                    provider = provider,
                    operation = "抓取",
                    kind = WebFailureKind.NOT_FOUND,
                    detail = "DeepSeek Fetch 返回了空正文"
                )
            }
            ExtractedPage(
                title = response.title.takeIf { it.isNotBlank() },
                content = content,
                provider = provider
            )
        } catch (e: Throwable) {
            throw webFailureOf(provider, "抓取", e)
        }
    }

    private suspend fun keyFor(operation: String): String {
        val key = apiKeyProvider()?.trim().orEmpty()
        if (key.isEmpty()) {
            throw WebApiException(
                provider = provider,
                operation = operation,
                kind = WebFailureKind.CONFIG,
                detail = "请在设置的联网搜索卡片里填写 DeepSeek Web Key，" +
                    "或把「对话」指到一个 Base URL 为 api.deepseek.com 的 DeepSeek 档案"
            )
        }
        return key
    }

    companion object {
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/"

        /** 官方 query 上限：1–128 个 Unicode 字符 */
        const val QUERY_MAX_CHARS = 128

        /** 官方 Fetch URL 上限 */
        const val URL_MAX_CHARS = 8192
    }
}

// ======================= 请求映射（纯函数，便于单测） =======================

/**
 * 把现有 topic/timeRange/includeDomains 语义翻译成 DeepSeek Search 的查询语法。
 * DeepSeek 没有 topic、time_range、include_domains 等请求字段（未知字段会被忽略），
 * 因此日期与域名只能用官方支持的操作符表达。
 */
internal fun buildDeepSeekQuery(
    request: WebSearchRequest,
    today: LocalDate = LocalDate.now()
): String {
    val parts = mutableListOf(request.query.trim())
    val days = when (request.timeRange) {
        "day" -> 1L
        "week" -> 7L
        "month" -> 30L
        "year" -> 365L
        else -> null
    }
    if (days != null) {
        parts += "after:" + today.minusDays(days).toString()
    }
    val domains = request.includeDomains
        ?.split(',', '，', ';', '；')
        ?.map { it.trim().removePrefix("site:") }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    if (domains.isNotEmpty()) {
        parts += domains.joinToString(" OR ") { "site:$it" }
    }
    return parts.joinToString(" ")
}

/** 官方按 Unicode code point 计数；空/超过 128 直接给可修正错误 */
internal fun validateDeepSeekQuery(query: String) {
    val count = query.codePointCount(0, query.length)
    if (query.isBlank()) {
        throw WebApiException(
            provider = WebProvider.DEEPSEEK,
            operation = "搜索",
            kind = WebFailureKind.INVALID_REQUEST,
            detail = "搜索词不能为空"
        )
    }
    if (count > DeepSeekWebClient.QUERY_MAX_CHARS) {
        throw WebApiException(
            provider = WebProvider.DEEPSEEK,
            operation = "搜索",
            kind = WebFailureKind.INVALID_REQUEST,
            detail = "搜索词（含日期/域名操作符）共 $count 字，超过官方 128 字上限；请缩短搜索词"
        )
    }
}

internal fun validateDeepSeekUrl(url: String) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw WebApiException(
            provider = WebProvider.DEEPSEEK,
            operation = "抓取",
            kind = WebFailureKind.INVALID_REQUEST,
            detail = "URL 必须以 http:// 或 https:// 开头"
        )
    }
    if (url.length > DeepSeekWebClient.URL_MAX_CHARS) {
        throw WebApiException(
            provider = WebProvider.DEEPSEEK,
            operation = "抓取",
            kind = WebFailureKind.INVALID_REQUEST,
            detail = "URL 超过官方 ${DeepSeekWebClient.URL_MAX_CHARS} 字符上限"
        )
    }
}

internal fun DeepSeekSearchResult.toSearchResult(): SearchResult {
    val summary = summary.trim()
        .ifBlank { snippet.trim() }
        .ifBlank { content.trim() }
    return SearchResult(
        title = title.ifBlank { url },
        url = url,
        content = summary,
        score = score,
        publishedAt = date?.takeIf { it.isNotBlank() },
        provider = WebProvider.DEEPSEEK
    )
}

// ======================= Wire DTO =======================

@Serializable
internal data class DeepSeekSearchRequest(
    val query: String,
    val count: Int = 5,
    @SerialName("need_content")
    val needContent: Boolean = false
)

@Serializable
internal data class DeepSeekSearchResponse(
    val results: List<DeepSeekSearchResult> = emptyList()
)

@Serializable
internal data class DeepSeekSearchResult(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val summary: String = "",
    val content: String = "",
    val date: String? = null,
    @SerialName("rerank_score")
    val score: Double = 0.0
)

@Serializable
internal data class DeepSeekFetchRequest(val url: String)

@Serializable
internal data class DeepSeekFetchResponse(
    val title: String = "",
    val content: String = ""
)

internal interface DeepSeekWebApi {
    @POST("v1/tools/search")
    suspend fun search(
        @Header("Authorization") auth: String,
        @Body body: DeepSeekSearchRequest
    ): DeepSeekSearchResponse

    @POST("v1/tools/fetch")
    suspend fun fetch(
        @Header("Authorization") auth: String,
        @Body body: DeepSeekFetchRequest
    ): DeepSeekFetchResponse
}
