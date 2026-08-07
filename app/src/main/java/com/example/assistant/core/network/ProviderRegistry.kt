package com.example.assistant.core.network

import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * 提供商注册表：按能力（对话/识屏/分类）解析当前生效的提供商档案，
 * 并按 baseUrl 缓存 Retrofit 实例。
 *
 * 解析优先级：能力指派档案 -> 默认档案 -> null（未配置）。
 * 能力指派读自 DataStore（不能主线程同步读），此处做内存缓存 + invalidate。
 */
class ProviderRegistry(
    private val secretStore: SecretStore,
    private val settingsStore: SettingsStore
) {

    private val apiCache = mutableMapOf<String, OpenAiChatApi>()
    private var assignmentCache: Map<Capability, String>? = null
    /**
     * 已知不支持思考参数的 (baseUrl|model) → 降级状态：
     * 1 = 只去掉 thinking（保留 reasoning_effort）；2 = 两者都去掉。
     * 内存缓存即可（App 重启后第一次调用会重新探测一次并自动降级，用户无感）。
     */
    private val thinkingUnsupported = mutableMapOf<String, Int>()

    fun allProfiles(): List<ProviderProfile> = secretStore.loadProfiles()

    fun defaultProfile(): ProviderProfile? = allProfiles().firstOrNull { it.isDefault }

    /** 按能力取档案 */
    suspend fun profileFor(capability: Capability): ProviderProfile? {
        val profiles = allProfiles()
        val assignments = assignmentCache ?: run {
            Capability.entries.associateWith { settingsStore.currentProfileIdFor(it) }.also {
                assignmentCache = it
            }
        }
        val assigned = assignments[capability].let { id ->
            profiles.firstOrNull { it.id == id }
        }
        if (assigned != null) return assigned
        return profiles.firstOrNull { it.isDefault }
    }

    /** 取某档案对应的 API 实例（按 baseUrl 缓存） */
    fun apiFor(profile: ProviderProfile): OpenAiChatApi =
        apiCache.getOrPut(profile.normalizedBaseUrl()) {
            ApiClient.create(profile.normalizedBaseUrl() + "/")
        }

    fun authHeader(apiKey: String) = "Bearer $apiKey"

    /** 档案或指派变更后调用：清 API 与指派缓存 */
    fun invalidate() {
        apiCache.clear()
        assignmentCache = null
    }

    /**
     * 某档案的思考参数（per-provider）。
     * - 档案自己的 thinkingMode/reasoningEffort 非 "default" 时用之；
     * - 否则 fallback 到旧全局设置（兼容 2026-08-02 之前的用户配置，无需迁移）。
     * - thinking："on"/"off" 发 DeepSeek 格式 {"type":"enabled"/"disabled"}，"default" 不发
     * - reasoningEffort："low"/"medium"/"high" 直接透传（OpenAI 兼容格式），"default" 不发
     */
    suspend fun thinkingParamsFor(profile: ProviderProfile): Pair<JsonObject?, String?> {
        val mode = profile.thinkingMode.takeIf { it != "default" } ?: settingsStore.thinkingMode.first()
        val effort = profile.reasoningEffort.takeIf { it != "default" }
            ?: settingsStore.reasoningEffort.first()
        val thinking = when (mode) {
            "on" -> buildJsonObject { put("type", JsonPrimitive("enabled")) }
            "off" -> buildJsonObject { put("type", JsonPrimitive("disabled")) }
            else -> null
        }
        return thinking to effort.takeIf { it != "default" }
    }

    /**
     * 思考参数兼容调用：部分模型/中转站不认识 thinking（DeepSeek 格式）或
     * reasoning_effort（OpenAI 格式）参数，带参调用会回 HTTP 400
     * "Unknown parameter: 'thinking'"（如中转站的 gpt-5.6luna）。
     * 自动降级策略：
     * 1. 带思考参数正常调用；
     * 2. HTTP 400 且错误信息明确指向未知参数 → 逐级降级重试（先去掉 thinking，
     *    仍报错再去掉 reasoning_effort），最多两级；
     * 3. 降级成功后把该 (baseUrl|model) 记入内存缓存，后续调用直接发降级形态
     *    不再试错（App 重启后重新探测一次，用户无感）。
     * 请求本身不带思考参数时直接调用（零开销、零风险）。
     */
    suspend fun <T> thinkingSafeCall(
        profile: ProviderProfile,
        request: ChatRequest,
        header: String,
        api: OpenAiChatApi,
        call: suspend (String, ChatRequest) -> T
    ): T {
        if (request.thinking == null && request.reasoningEffort == null) return call(header, request)

        val key = "${profile.normalizedBaseUrl()}|${profile.model}"
        var req = when (thinkingUnsupported[key]) {
            1 -> request.copy(thinking = null) // 已知 thinking 不支持：去掉它，保留 effort
            2 -> request.copy(thinking = null, reasoningEffort = null) // 两个都不支持：全去掉
            else -> request
        }
        while (true) {
            try {
                return call(header, req)
            } catch (e: Exception) {
                val code = when (e) {
                    is HttpException -> e.code()
                    is ApiHttpException -> e.code
                    else -> throw e
                }
                val body = when (e) {
                    is HttpException -> try {
                        e.response()?.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    is ApiHttpException -> e.body
                    else -> null
                }
                // 只对"不认识参数"类 400 降级；其他错误原样抛给上层
                if (code != 400 || body.isNullOrBlank() || !looksLikeUnknownParameter(body)) throw e
                // 逐级降级：先去掉 thinking（记为 1），再去掉 reasoning_effort（记为 2）
                val next = when {
                    req.thinking != null -> {
                        thinkingUnsupported[key] = 1
                        req.copy(thinking = null)
                    }
                    req.reasoningEffort != null -> {
                        thinkingUnsupported[key] = 2
                        req.copy(reasoningEffort = null)
                    }
                    else -> null
                } ?: throw e
                req = next
            }
        }
    }

    /** 非流式调用兼容包装（api.chat 的直接替代） */
    suspend fun chatCompat(
        profile: ProviderProfile,
        request: ChatRequest,
        header: String,
        api: OpenAiChatApi
    ): ChatResponse = thinkingSafeCall(profile, request, header, api) { h, r -> api.chat(h, r) }

    /** 流式调用兼容包装（api.chatStream + isSuccessful 检查 的直接替代） */
    suspend fun chatStreamCompat(
        profile: ProviderProfile,
        request: ChatRequest,
        header: String,
        api: OpenAiChatApi
    ): Response<ResponseBody> = thinkingSafeCall(profile, request, header, api) { h, r ->
        val resp = api.chatStream(h, r)
        if (!resp.isSuccessful) {
            // 流式接口不抛异常，非 2xx 在这里转抛，由 thinkingSafeCall 判断是否降级重试
            throw ApiHttpException(resp.code(), resp.errorBody()?.string())
        }
        resp
    }

    /** 错误信息是否指向"不认识请求参数"（各家 400 文案不同，宽松匹配） */
    private fun looksLikeUnknownParameter(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("unknown parameter") || b.contains("unknown_parameter") ||
            (b.contains("thinking") && b.contains("parameter")) ||
            (b.contains("reasoning_effort") && b.contains("parameter"))
    }
}

/**
 * 流式接口非 2xx 响应转抛的异常（chatStream 返回 Response 不抛异常，见 chatStreamCompat）。
 * message 保持 "HTTP <code>：<body>" 格式，上层错误显示不变。
 */
class ApiHttpException(val code: Int, val body: String?) : Exception("HTTP $code：${body ?: ""}")
