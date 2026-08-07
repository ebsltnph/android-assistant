package com.example.assistant.core.storage

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.assistant.core.network.Capability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * settings DataStore。corruptionHandler：关机/断电可能损坏 preferences 文件——
 * 损坏时**重置为空**而不是抛 CorruptionException 崩溃进程
 * （"重启后快速打开 App 闪退"的根因之一）。
 */
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * 普通设置存储（DataStore，明文即可——不含任何密钥）。
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    // ---- 能力指派：capability -> profileId（空串 = 未指派，回退默认档案） ----
    fun profileIdFor(capability: Capability): Flow<String> =
        dataStore.data.map { it[capabilityKey(capability)] ?: "" }

    suspend fun setProfileIdFor(capability: Capability, profileId: String) {
        dataStore.edit { it[capabilityKey(capability)] = profileId }
    }

    suspend fun currentProfileIdFor(capability: Capability): String =
        profileIdFor(capability).first()

    // ---- 通用设置 ----
    val ttsEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_TTS] ?: false }
    suspend fun setTtsEnabled(v: Boolean) = dataStore.edit { it[KEY_TTS] = v }

    /** 每日日记总结时间（分钟数，默认 21:00=1260，可精确到分钟） */
    val dailySummaryMinute: Flow<Int> = dataStore.data.map { it[KEY_SUMMARY_MINUTE] ?: 21 * 60 }

    /** 清晨简报时间（默认 7:30，存分钟数，可精确到分钟） */
    val briefingMinuteOfDay: Flow<Int> = dataStore.data.map { it[KEY_BRIEFING_MINUTES] ?: 7 * 60 + 30 }

    /** 免打扰：开始/结束（分钟数，如 23*60=1380 / 7*60=420） */
    val quietStartMinute: Flow<Int> = dataStore.data.map { it[KEY_QUIET_START] ?: 23 * 60 }
    val quietEndMinute: Flow<Int> = dataStore.data.map { it[KEY_QUIET_END] ?: 7 * 60 }

    suspend fun setDailySummaryMinute(minute: Int) = dataStore.edit { it[KEY_SUMMARY_MINUTE] = minute }
    suspend fun setBriefingMinuteOfDay(minutes: Int) = dataStore.edit { it[KEY_BRIEFING_MINUTES] = minutes }
    suspend fun setQuietWindow(startMinute: Int, endMinute: Int) = dataStore.edit {
        it[KEY_QUIET_START] = startMinute
        it[KEY_QUIET_END] = endMinute
    }

    // ---- 高级设置：思考开关与深度（"default" = 不发送参数，跟随厂商/模型默认） ----
    /** 思考模式："default" | "on" | "off" */
    val thinkingMode: Flow<String> = dataStore.data.map { it[KEY_THINKING_MODE] ?: "default" }
    suspend fun setThinkingMode(v: String) = dataStore.edit { it[KEY_THINKING_MODE] = v }

    /** 思考深度："default" | "low" | "medium" | "high" */
    val reasoningEffort: Flow<String> = dataStore.data.map { it[KEY_REASONING_EFFORT] ?: "default" }
    suspend fun setReasoningEffort(v: String) = dataStore.edit { it[KEY_REASONING_EFFORT] = v }

    // ---- P6：悬浮球 ----
    /** 悬浮球开关（默认关；开着 → 前台服务常驻，开机自动恢复） */
    val floatingBallEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_FLOATING_BALL] ?: false }
    suspend fun setFloatingBallEnabled(v: Boolean) = dataStore.edit { it[KEY_FLOATING_BALL] = v }

    // ---- 对话 ----
    /** 聊天上下文长度（对话轮数，默认 10；范围 5-50 由设置页 UI 约束） */
    val conversationMaxTurns: Flow<Int> = dataStore.data.map { it[KEY_MAX_TURNS] ?: 10 }
    suspend fun setConversationMaxTurns(v: Int) = dataStore.edit { it[KEY_MAX_TURNS] = v }

    // ---- 秘密功能：对话历史记录（数字分身素材，只存用户消息） ----
    /** 记录开关（默认开：只在本机保存用户发出的对话内容，供未来提取用户特征） */
    val secretLogEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_SECRET_LOG] ?: true }
    suspend fun setSecretLogEnabled(v: Boolean) = dataStore.edit { it[KEY_SECRET_LOG] = v }

    companion object {
        private const val TAG = "SettingsStore"
        private val KEY_TTS = booleanPreferencesKey("tts_enabled")
        private val KEY_SUMMARY_MINUTE = intPreferencesKey("daily_summary_minute")
        private val KEY_BRIEFING_MINUTES = intPreferencesKey("briefing_minutes")
        private val KEY_QUIET_START = intPreferencesKey("quiet_start_minute")
        private val KEY_QUIET_END = intPreferencesKey("quiet_end_minute")
        private val KEY_THINKING_MODE = stringPreferencesKey("thinking_mode")
        private val KEY_REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        private val KEY_FLOATING_BALL = booleanPreferencesKey("floating_ball_enabled")
        private val KEY_MAX_TURNS = intPreferencesKey("conversation_max_turns")
        private val KEY_SECRET_LOG = booleanPreferencesKey("secret_log_enabled")

        private fun capabilityKey(c: Capability) = stringPreferencesKey("capability_${c.name}")
    }
}
