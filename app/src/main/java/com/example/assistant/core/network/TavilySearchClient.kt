package com.example.assistant.core.network

import com.example.assistant.core.storage.SecretStore
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
import java.util.concurrent.TimeUnit

/**
 * Tavily 搜索 API（默认提供商）：
 * - 不填 API Key 时使用 keyless 模式（免注册、有限流）；
 * - 填 Key 后使用该 Key 的额度；
 * - 与抓取共用同一个 Key/keyless 状态。
 *
 * 失败不会回退到其他提供商。
 */
class TavilySearchClient(private val secretStore: SecretStore) : SearchClient {

    override val provider = WebProvider.TAVILY

    private val api: TavilyApi = createTavilyApi(readTimeoutSeconds = 30)

    override suspend fun search(request: WebSearchRequest): List<SearchResult> {
        val key = secretStore.searchApiKey().trim()
        return try {
            val response = withWebReadRetry(provider, "搜索") {
                try {
                    api.search(
                        auth = if (key.isNotBlank()) "Bearer $key" else "",
                        accessMode = if (key.isBlank()) "keyless" else null,
                        body = TavilyRequest(
                            query = request.query,
                            maxResults = request.maxResults,
                            topic = request.topic,
                            timeRange = request.timeRange,
                            includeDomains = parseTavilyDomains(request.includeDomains)
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
}

internal fun TavilyResult.toSearchResult(): SearchResult = SearchResult(
    title = title.ifBlank { url },
    url = url,
    content = content,
    score = score,
    provider = WebProvider.TAVILY
)

/** Tavily 的 include_domains 是 string[]，不是逗号串 */
internal fun parseTavilyDomains(raw: String?): List<String>? =
    raw?.split(',', '，', ';', '；')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }

/** Tavily 请求体 */
@Serializable
data class TavilyRequest(
    val query: String,
    @SerialName("max_results")
    val maxResults: Int = 5,
    /** "general" | "news" | "finance" */
    val topic: String = "general",
    /** "day" | "week" | "month" | "year" */
    @SerialName("time_range")
    val timeRange: String? = null,
    /** 只返回这些域名下的结果（官方类型是 string[]） */
    @SerialName("include_domains")
    val includeDomains: List<String>? = null
)

/** Tavily 响应 */
@Serializable
data class TavilyResponse(
    val query: String = "",
    val results: List<TavilyResult> = emptyList()
)

@Serializable
data class TavilyResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val score: Double = 0.0
)

/** Tavily 接口（独立于 OpenAI 兼容契约）：搜索 /search + 网页抽取 /extract */
interface TavilyApi {
    @POST("search")
    suspend fun search(
        @Header("Authorization") auth: String,
        @Header("X-Tavily-Access-Mode") accessMode: String?,
        @Body body: TavilyRequest
    ): TavilyResponse

    @POST("extract")
    suspend fun extract(
        @Header("Authorization") auth: String,
        @Header("X-Tavily-Access-Mode") accessMode: String?,
        @Body body: TavilyExtractRequest
    ): TavilyExtractResponse
}

private fun createTavilyApi(readTimeoutSeconds: Long): TavilyApi =
    Retrofit.Builder()
        .baseUrl("https://api.tavily.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // 抓正文比搜索慢，读超时由调用方放宽
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(
            Json { ignoreUnknownKeys = true }
                .asConverterFactory("application/json".toMediaType())
        )
        .build()
        .create(TavilyApi::class.java)

// ======================= 网页正文抽取（/extract，read_webpage 工具用） =======================

@Serializable
data class TavilyExtractRequest(val urls: List<String>)

@Serializable
data class TavilyExtractResponse(
    val results: List<TavilyExtractResult> = emptyList(),
    @SerialName("failed_results")
    val failedResults: List<TavilyFailedResult> = emptyList()
)

@Serializable
data class TavilyExtractResult(
    val url: String = "",
    val title: String? = null,
    @SerialName("raw_content")
    val rawContent: String? = null
)

@Serializable
data class TavilyFailedResult(val url: String = "", val error: String? = null)

/**
 * Tavily Extract 实现：抓取并清洗网页正文。
 * 与搜索同一服务/Key（keyless 模式同样支持），反爬严格的页面可能拿不到。
 */
class TavilyExtractClient(private val secretStore: SecretStore) : PageReader {

    override val provider = WebProvider.TAVILY

    private val api: TavilyApi = createTavilyApi(readTimeoutSeconds = 45)

    override suspend fun extract(url: String): ExtractedPage {
        val key = secretStore.searchApiKey().trim()
        return try {
            val response = withWebReadRetry(provider, "抓取") {
                try {
                    api.extract(
                        auth = if (key.isNotBlank()) "Bearer $key" else "",
                        accessMode = if (key.isBlank()) "keyless" else null,
                        body = TavilyExtractRequest(urls = listOf(url))
                    )
                } catch (e: Throwable) {
                    throw webFailureOf(provider, "抓取", e)
                }
            }
            val first = response.results.firstOrNull()
            val content = first?.rawContent?.trim()
            if (content.isNullOrBlank()) {
                val reason = response.failedResults.firstOrNull()?.error?.takeIf { it.isNotBlank() }
                    ?: "页面没有可提取的正文"
                throw WebApiException(
                    provider = provider,
                    operation = "抓取",
                    kind = WebFailureKind.NOT_FOUND,
                    detail = reason
                )
            }
            ExtractedPage(
                title = first.title?.takeIf { it.isNotBlank() },
                content = content,
                provider = provider
            )
        } catch (e: Throwable) {
            throw webFailureOf(provider, "抓取", e)
        }
    }
}
