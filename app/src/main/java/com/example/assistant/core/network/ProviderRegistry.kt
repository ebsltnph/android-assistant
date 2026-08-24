package com.example.assistant.core.network

import com.example.assistant.core.network.dto.ChatRequest
import com.example.assistant.core.network.dto.ChatResponse
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.flow.first
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
     * 已知不支持 reasoning_effort 参数的 (baseUrl|model) 集合。
     * 内存缓存即可（App 重启后第一次调用会重新探测一次并自动降级，用户无感）。
     */
    private val effortUnsupported = mutableSetOf<String>()

    /** 每个模型最近一次请求的思考参数实际状态（设置页展示用）：unset / sent:<值> / stripped */
    private val effortStatus = mutableMapOf<String, String>()

    /** 该模型最近一次请求的思考参数状态；null = 本会话还没发生过相关请求 */
    fun effortStatusFor(profile: ProviderProfile): String? = effortStatus[statusKey(profile)]

    private fun statusKey(profile: ProviderProfile) = "${profile.normalizedBaseUrl()}|${profile.model}"

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
     * 某档案的思考深度（per-provider）。
     * - 档案自己的 reasoningEffort 非 "default" 时用之；
     * - 否则 fallback 到旧全局设置（兼容 2026-08-02 之前的用户配置，无需迁移）。
     * - 只返回 OpenAI 通用参数 reasoning_effort（"low"/"medium"/"high"）；
     *   不返回任何 DeepSeek 专属的 thinking 字段（v1.2.x 起，见 ChatRequest.reasoningEffort 注释）。
     * - "default"（或 null）→ 不发送，跟随厂商/模型默认。
     */
    suspend fun reasoningEffortFor(profile: ProviderProfile): String? {
        val effort = profile.reasoningEffort.takeIf { it != "default" }
            ?: settingsStore.reasoningEffort.first()
        return effort.takeIf { it != "default" }
    }

    /**
     * 思考参数兼容调用：极少数模型/中转站不认识 reasoning_effort 参数或某些档位值，
     * 带参调用会回 HTTP 400。自动降级梯子：
     * 1. 带 reasoning_effort 正常调用；
     * 2. HTTP 400 且错误指向该参数 → 先把 xhigh 降为 high（部分模型只认到 high）；
     * 3. 仍不行 → 整个参数去掉重试，并把该 (baseUrl|model) 记入内存缓存，
     *    后续调用直接发降级形态不再试错（App 重启后重新探测一次，用户无感）。
     * 每次实际发出的形态记录进 [effortStatus]（设置页展示"是否真的生效"）。
     * 请求本身不带思考参数时直接调用（零开销、零风险）。
     */
    suspend fun <T> effortSafeCall(
        profile: ProviderProfile,
        request: ChatRequest,
        header: String,
        api: OpenAiChatApi,
        call: suspend (String, ChatRequest) -> T
    ): T {
        val key = statusKey(profile)
        var req = if (key in effortUnsupported && request.reasoningEffort != null) {
            effortStatus[key] = "stripped"
            request.copy(reasoningEffort = null)
        } else {
            effortStatus[key] = request.reasoningEffort?.let { "sent:$it" } ?: "unset"
            request
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
                // 只对"参数不认识/值非法"类 400 降级；其他错误原样抛给上层
                if (code != 400 || body.isNullOrBlank() || !looksLikeReasoningParamProblem(body)) throw e
                when {
                    // 第一级：xhigh 超出模型支持范围 → 降到 high 再试一次
                    req.reasoningEffort == "xhigh" -> {
                        req = req.copy(reasoningEffort = "high")
                        effortStatus[key] = "sent:high(xhigh降级)"
                    }
                    // 第二级：整个参数去掉并记住该模型不支持
                    req.reasoningEffort != null -> {
                        effortUnsupported += key
                        effortStatus[key] = "stripped"
                        req = req.copy(reasoningEffort = null)
                    }
                    else -> throw e // 已无参数可降级
                }
            }
        }
    }

    /** 非流式调用兼容包装（api.chat 的直接替代） */
    suspend fun chatCompat(
        profile: ProviderProfile,
        request: ChatRequest,
        header: String,
        api: OpenAiChatApi
    ): ChatResponse = effortSafeCall(profile, request, header, api) { h, r -> api.chat(h, r) }

    /** 流式调用兼容包装（api.chatStream + isSuccessful 检查 的直接替代） */
    suspend fun chatStreamCompat(
        profile: ProviderProfile,
        request: ChatRequest,
        header: String,
        api: OpenAiChatApi
    ): Response<ResponseBody> = effortSafeCall(profile, request, header, api) { h, r ->
        val resp = api.chatStream(h, r)
        if (!resp.isSuccessful) {
            // 流式接口不抛异常，非 2xx 在这里转抛，由 effortSafeCall 判断是否降级重试
            throw ApiHttpException(resp.code(), resp.errorBody()?.string())
        }
        resp
    }

    /** 错误信息是否指向 reasoning_effort 参数不被认识/值非法（各家 400 文案不同，宽松匹配） */
    private fun looksLikeReasoningParamProblem(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("unknown parameter") || b.contains("unknown_parameter") ||
            (b.contains("reasoning_effort") &&
                (b.contains("parameter") || b.contains("invalid") || b.contains("value")))
    }
}

/**
 * 流式接口非 2xx 响应转抛的异常（chatStream 返回 Response 不抛异常，见 chatStreamCompat）。
 * message 保持 "HTTP <code>：<body>" 格式，上层错误显示不变。
 */
class ApiHttpException(val code: Int, val body: String?) : Exception("HTTP $code：${body ?: ""}")
