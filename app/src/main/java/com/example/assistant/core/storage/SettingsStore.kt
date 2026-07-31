package com.example.assistant.core.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.assistant.core.network.Capability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

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

    /** 每日日记总结时间（24h 小时，默认 21:00） */
    val dailySummaryHour: Flow<Int> = dataStore.data.map { it[KEY_SUMMARY_HOUR] ?: 21 }

    /** 清晨简报时间（默认 7:30，存分钟数） */
    val briefingMinuteOfDay: Flow<Int> = dataStore.data.map { it[KEY_BRIEFING_MINUTES] ?: 7 * 60 + 30 }

    /** 免打扰：开始/结束（分钟数，如 23*60=1380 / 7*60=420） */
    val quietStartMinute: Flow<Int> = dataStore.data.map { it[KEY_QUIET_START] ?: 23 * 60 }
    val quietEndMinute: Flow<Int> = dataStore.data.map { it[KEY_QUIET_END] ?: 7 * 60 }

    suspend fun setDailySummaryHour(hour: Int) = dataStore.edit { it[KEY_SUMMARY_HOUR] = hour }
    suspend fun setBriefingMinuteOfDay(minutes: Int) = dataStore.edit { it[KEY_BRIEFING_MINUTES] = minutes }
    suspend fun setQuietWindow(startMinute: Int, endMinute: Int) = dataStore.edit {
        it[KEY_QUIET_START] = startMinute
        it[KEY_QUIET_END] = endMinute
    }

    companion object {
        private const val TAG = "SettingsStore"
        private val KEY_TTS = booleanPreferencesKey("tts_enabled")
        private val KEY_SUMMARY_HOUR = intPreferencesKey("daily_summary_hour")
        private val KEY_BRIEFING_MINUTES = intPreferencesKey("briefing_minutes")
        private val KEY_QUIET_START = intPreferencesKey("quiet_start_minute")
        private val KEY_QUIET_END = intPreferencesKey("quiet_end_minute")

        private fun capabilityKey(c: Capability) = stringPreferencesKey("capability_${c.name}")
    }
}
