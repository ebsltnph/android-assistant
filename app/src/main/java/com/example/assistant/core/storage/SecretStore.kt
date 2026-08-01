package com.example.assistant.core.storage

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.assistant.core.network.ProviderProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 密钥存储（加密）：提供商档案（含 API Key）。
 *
 * 用 EncryptedSharedPreferences（基于 Android Keystore 加密），
 * 不入 Room、不打日志。注意：该库官方已标记为 deprecated（待迁移），
 * 当前仍可用且无 GMS 依赖；未来可迁移到 Keystore + DataStore 自加密。
 */
class SecretStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secret_store",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** 读取全部提供商档案（JSON 数组），损坏时返回空列表 */
    fun loadProfiles(): List<ProviderProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ProviderProfile>>(raw)
        } catch (e: Exception) {
            Log.e(TAG, "解析提供商档案失败", e)
            emptyList()
        }
    }

    /** 整体保存档案列表（覆盖写） */
    fun saveProfiles(profiles: List<ProviderProfile>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString<List<ProviderProfile>>(profiles)).apply()
    }

    // ---- 搜索 API Key（Tavily；空串 = 用 keyless 模式） ----
    fun searchApiKey(): String = prefs.getString(KEY_SEARCH_KEY, "").orEmpty()

    fun saveSearchApiKey(key: String) {
        prefs.edit().putString(KEY_SEARCH_KEY, key.trim()).apply()
    }

    companion object {
        private const val TAG = "SecretStore"
        private const val KEY_PROFILES = "provider_profiles"
        private const val KEY_SEARCH_KEY = "search_api_key"
    }
}
