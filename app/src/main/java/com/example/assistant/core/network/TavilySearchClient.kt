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
 * Tavily 搜索 API（专为 AI 助手设计，返回清洗后的内容摘要）。
 * - 免费 1000 次/月；不填 API Key 时自动用 keyless 模式（免注册、有限流，自用够）
 * - Base URL: https://api.tavily.com，端点 POST /search
 */
class TavilySearchClient(private val secretStore: SecretStore) : SearchClient {

    private val api: TavilyApi = Retrofit.Builder()
        .baseUrl("https://api.tavily.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(Json { ignoreUnknownKeys = true }
            .asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TavilyApi::class.java)

    override suspend fun search(
        query: String,
        topic: String,
        timeRange: String?,
        maxResults: Int,
        includeDomains: String?
    ): List<SearchResult> {
        val key = secretStore.searchApiKey()
        val auth = if (key.isNotBlank()) "Bearer $key" else ""
        // keyless 模式：未填 key 时用该头替代 Authorization（Tavily 官方支持）
        val accessMode = if (key.isBlank()) "keyless" else null
        val response = api.search(
            auth = auth,
            accessMode = accessMode,
            body = TavilyRequest(
                query = query,
                maxResults = maxResults,
                topic = topic,
                timeRange = timeRange,
                includeDomains = includeDomains
            )
        )
        return response.results.map { SearchResult(it.title, it.url, it.content, it.score) }
    }
}

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
    /** 只返回这些域名下的结果（逗号分隔） */
    @SerialName("include_domains")
    val includeDomains: String? = null
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

/** Tavily 接口（独立于 OpenAI 兼容契约） */
interface TavilyApi {
    @POST("search")
    suspend fun search(
        @Header("Authorization") auth: String,
        @Header("X-Tavily-Access-Mode") accessMode: String?,
        @Body body: TavilyRequest
    ): TavilyResponse
}
