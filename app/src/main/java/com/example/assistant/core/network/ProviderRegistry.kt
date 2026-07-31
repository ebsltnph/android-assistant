package com.example.assistant.core.network

import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore

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
}
