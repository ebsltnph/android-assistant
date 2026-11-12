package com.example.assistant.core.network

import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 联网搜索/抓取的动态入口。
 *
 * 搜索与抓取分别读取设置中的提供商；每次操作现读，选中哪个就只用哪个。
 * 凭据解析与 UI 文案也在这里，避免调用方各拼一套。
 */
class WebAccessRegistry(
    private val secretStore: SecretStore,
    private val settingsStore: SettingsStore,
    private val providerRegistry: ProviderRegistry
) {

    private val tavilySearch = TavilySearchClient(secretStore)
    private val tavilyPage = TavilyExtractClient(secretStore)
    private val deepSeek = DeepSeekWebClient { resolveDeepSeekKey() }

    /** 当前搜索提供商（设置里未保存/损坏 → Tavily） */
    suspend fun currentSearchProvider(): WebProvider = settingsStore.searchProvider.first()

    /** 当前抓取提供商（独立于搜索） */
    suspend fun currentFetchProvider(): WebProvider = settingsStore.fetchProvider.first()

    suspend fun currentSearchClient(): SearchClient = searchClientFor(currentSearchProvider())

    suspend fun currentPageReader(): PageReader = pageReaderFor(currentFetchProvider())

    suspend fun searchClientFor(provider: WebProvider): SearchClient = when (provider) {
        WebProvider.TAVILY -> tavilySearch
        WebProvider.DEEPSEEK -> deepSeek
    }

    suspend fun pageReaderFor(provider: WebProvider): PageReader = when (provider) {
        WebProvider.TAVILY -> tavilyPage
        WebProvider.DEEPSEEK -> deepSeek
    }

    /** 设置页展示：DeepSeek 的 Key 目前从哪里来 */
    suspend fun deepSeekCredentialDescription(): String {
        if (secretStore.deepSeekWebApiKey().isNotBlank()) return "使用专用 DeepSeek Web Key"
        val chat = providerRegistry.profileFor(Capability.CHAT)
        if (chat != null && chat.isOfficialDeepSeekEndpoint() && chat.apiKey.isNotBlank()) {
            return "复用对话档案「${chat.name}」的 Key"
        }
        return "未配置（填写专用 Key，或把「对话」指到官方 DeepSeek 档案）"
    }

    /** 测试搜索：明确指定提供商，不读取/切换当前选择，也绝不回退 */
    suspend fun probeSearch(provider: WebProvider): WebProbeResult {
        val started = System.nanoTime()
        return try {
            val results = searchClientFor(provider).search(
                WebSearchRequest(query = "DeepSeek API 文档", maxResults = 1)
            )
            val message = if (results.isEmpty()) {
                "${provider.displayName} 搜索接口连接成功（HTTP 200，返回 0 条）"
            } else {
                "${provider.displayName} 搜索接口连接成功（HTTP 200，返回 ${results.size} 条；" +
                    "首条：${results.first().title.take(40)}）"
            }
            WebProbeResult(true, message, elapsedMs(started))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            WebProbeResult(false, e.message ?: "搜索测试失败", elapsedMs(started))
        }
    }

    /** 测试抓取：固定轻量页面；Tavily keyless / DeepSeek 各走各的预算 */
    suspend fun probeFetch(provider: WebProvider): WebProbeResult {
        val started = System.nanoTime()
        return try {
            val page = pageReaderFor(provider).extract("https://example.com")
            WebProbeResult(
                true,
                "${provider.displayName} 抓取接口连接成功（HTTP 200，正文 ${page.content.length} 字）",
                elapsedMs(started)
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            WebProbeResult(false, e.message ?: "抓取测试失败", elapsedMs(started))
        }
    }

    /**
     * DeepSeek Web 凭据：
     * 1. 专用 Key（用户显式填写）；
     * 2. 当前「对话」档案为 api.deepseek.com 时复用。
     *
     * 不自动挑“任意一个官方 DeepSeek 档案”——那可能用错账号计费。
     */
    private suspend fun resolveDeepSeekKey(): String? {
        secretStore.deepSeekWebApiKey().trim().takeIf { it.isNotEmpty() }?.let { return it }

        val chat = providerRegistry.profileFor(Capability.CHAT)
        if (chat != null && chat.isOfficialDeepSeekEndpoint() && chat.apiKey.isNotBlank()) {
            return chat.apiKey
        }
        return null
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L
}
