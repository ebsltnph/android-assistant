package com.example.assistant.core.network

import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
     * 高级设置里的思考参数（所有请求统一应用）。
     * - thinking："default" 不发；on/off 发 DeepSeek 格式 {"type":"enabled"/"disabled"}
     * - reasoningEffort："default" 不发；low/medium/high 直接透传（OpenAI 兼容格式）
     */
    suspend fun thinkingParams(): Pair<JsonObject?, String?> {
        val thinking = when (settingsStore.thinkingMode.first()) {
            "on" -> buildJsonObject { put("type", JsonPrimitive("enabled")) }
            "off" -> buildJsonObject { put("type", JsonPrimitive("disabled")) }
            else -> null
        }
        val effort = settingsStore.reasoningEffort.first().takeIf { it != "default" }
        return thinking to effort
    }
}
