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

    /** 每日小结自动任务开关（默认开；关闭后不自动生成，手动仍可用） */
    val dailySummaryEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_SUMMARY_ENABLED] ?: true }

    /** 清晨简报时间（默认 7:30，存分钟数，可精确到分钟） */
    val briefingMinuteOfDay: Flow<Int> = dataStore.data.map { it[KEY_BRIEFING_MINUTES] ?: 7 * 60 + 30 }

    /** 清晨简报自动任务开关（默认开；关闭后不自动推送） */
    val briefingEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BRIEFING_ENABLED] ?: true }

    /** 免打扰：开始/结束（分钟数，如 23*60=1380 / 7*60=420） */
    val quietStartMinute: Flow<Int> = dataStore.data.map { it[KEY_QUIET_START] ?: 23 * 60 }
    val quietEndMinute: Flow<Int> = dataStore.data.map { it[KEY_QUIET_END] ?: 7 * 60 }

    suspend fun setDailySummaryMinute(minute: Int) = dataStore.edit { it[KEY_SUMMARY_MINUTE] = minute }
    suspend fun setDailySummaryEnabled(v: Boolean) = dataStore.edit { it[KEY_SUMMARY_ENABLED] = v }
    suspend fun setBriefingMinuteOfDay(minutes: Int) = dataStore.edit { it[KEY_BRIEFING_MINUTES] = minutes }
    suspend fun setBriefingEnabled(v: Boolean) = dataStore.edit { it[KEY_BRIEFING_ENABLED] = v }
    suspend fun setQuietWindow(startMinute: Int, endMinute: Int) = dataStore.edit {
        it[KEY_QUIET_START] = startMinute
        it[KEY_QUIET_END] = endMinute
    }

    // ---- 高级设置：思考深度（"default" = 不发送参数，跟随厂商/模型默认） ----
    // 2026-08-07 起只保留深度（OpenAI 通用参数 reasoning_effort）；思考开关删除了——
    // DeepSeek 的 thinking 开关格式是它家专属参数，中转站模型不认识会 HTTP 400
    /** 思考深度："default" | "low" | "medium" | "high" */
    val reasoningEffort: Flow<String> = dataStore.data.map { it[KEY_REASONING_EFFORT] ?: "default" }
    suspend fun setReasoningEffort(v: String) = dataStore.edit { it[KEY_REASONING_EFFORT] = v }

    // ---- v1.4.1：识屏框选 ----
    /** 识屏后先弹出选区层手动框选（**默认开**；关闭 = 老行为，直接识别整张截图） */
    val screenSenseRegionEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_SCREEN_SENSE_REGION] ?: true }
    suspend fun setScreenSenseRegionEnabled(v: Boolean) =
        dataStore.edit { it[KEY_SCREEN_SENSE_REGION] = v }

    // ---- P6：悬浮球 ----
    /** 悬浮球开关（默认关；开着 → 前台服务常驻，开机自动恢复） */
    val floatingBallEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_FLOATING_BALL] ?: false }
    suspend fun setFloatingBallEnabled(v: Boolean) = dataStore.edit { it[KEY_FLOATING_BALL] = v }

    // ---- v1.5.x：点悬浮球自动开始语音输入 ----
    /** 悬浮球语音输入方式：ime=键盘语音引导（默认）| system=系统听写 | remote=远程识别 API。
     *  system 在荣耀 X50 GT 上不可用（厂商识别服务对第三方静默失效，实测）。 */
    val panelVoiceMode: Flow<String> =
        dataStore.data.map { it[KEY_PANEL_VOICE_MODE] ?: "ime" }
    suspend fun setPanelVoiceMode(v: String) =
        dataStore.edit { it[KEY_PANEL_VOICE_MODE] = v }



    // ---- v1.5.x：悬浮球外观 ----
    /** 悬浮球中央 emoji 图标（空串 = 未用 emoji）。与自定义图片互斥：图片优先 */
    val bubbleIconEmoji: Flow<String> =
        dataStore.data.map { it[KEY_BUBBLE_ICON_EMOJI] ?: "" }
    suspend fun setBubbleIconEmoji(v: String) =
        dataStore.edit { it[KEY_BUBBLE_ICON_EMOJI] = v }

    /** 悬浮球自定义图片（应用私有目录内的绝对路径；空串 = 未用图片，优先级高于 emoji） */
    val bubbleIconImagePath: Flow<String> =
        dataStore.data.map { it[KEY_BUBBLE_ICON_IMAGE] ?: "" }
    suspend fun setBubbleIconImagePath(v: String) =
        dataStore.edit { it[KEY_BUBBLE_ICON_IMAGE] = v }

    // ---- 对话 ----
    /** 聊天上下文长度（对话轮数，默认 10；范围 5-50 由设置页 UI 约束） */
    val conversationMaxTurns: Flow<Int> = dataStore.data.map { it[KEY_MAX_TURNS] ?: 10 }
    suspend fun setConversationMaxTurns(v: Int) = dataStore.edit { it[KEY_MAX_TURNS] = v }

    // ---- 日记标签词汇表（用户自定义；AI 只能从这份列表里选 0-3 个） ----
    /** 标签列表，逗号分隔。默认：工作、生活、待办、经验 */
    val diaryTagsCsv: Flow<String> = dataStore.data.map { it[KEY_DIARY_TAGS] ?: DEFAULT_DIARY_TAGS_CSV }
    suspend fun setDiaryTagsCsv(csv: String) = dataStore.edit { it[KEY_DIARY_TAGS] = csv }

    /**
     * v1.4.0（发布前调整）默认标签改为通用词汇表。
     * 若用户从未自定义（存的是旧版默认值），启动时自动升级为新默认；
     * 用户改过的列表则原样保留。
     */
    suspend fun migrateLegacyDiaryTagsDefaultIfNeeded() {
        dataStore.edit { prefs ->
            if (prefs[KEY_DIARY_TAGS] == LEGACY_DEFAULT_DIARY_TAGS_CSV) {
                prefs[KEY_DIARY_TAGS] = DEFAULT_DIARY_TAGS_CSV
            }
        }
    }

    // ---- 秘密功能：对话历史记录（数字分身素材，只存用户消息） ----
    /** 记录开关（**默认关**：用户手动开启后才记录；关闭不清空已有记录） */
    val secretLogEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_SECRET_LOG] ?: false }
    suspend fun setSecretLogEnabled(v: Boolean) = dataStore.edit { it[KEY_SECRET_LOG] = v }

    // ---- v1.3 定期自动备份 ----
    /** 自动备份开关（默认关；开启后 WorkManager 周期执行，写公共「下载」目录） */
    val autoBackupEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_AUTO_BACKUP] ?: false }
    suspend fun setAutoBackupEnabled(v: Boolean) = dataStore.edit { it[KEY_AUTO_BACKUP] = v }

    /** 自动备份间隔（天，默认 7；可选 1/3/7） */
    val autoBackupIntervalDays: Flow<Int> = dataStore.data.map { it[KEY_AUTO_BACKUP_INTERVAL] ?: 7 }
    suspend fun setAutoBackupIntervalDays(v: Int) = dataStore.edit { it[KEY_AUTO_BACKUP_INTERVAL] = v }

    companion object {
        private const val TAG = "SettingsStore"
        private val KEY_TTS = booleanPreferencesKey("tts_enabled")
        private val KEY_SUMMARY_MINUTE = intPreferencesKey("daily_summary_minute")
        private val KEY_SUMMARY_ENABLED = booleanPreferencesKey("daily_summary_enabled")
        private val KEY_BRIEFING_MINUTES = intPreferencesKey("briefing_minutes")
        private val KEY_BRIEFING_ENABLED = booleanPreferencesKey("briefing_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_start_minute")
        private val KEY_QUIET_END = intPreferencesKey("quiet_end_minute")
        private val KEY_REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        private val KEY_FLOATING_BALL = booleanPreferencesKey("floating_ball_enabled")
        private val KEY_PANEL_VOICE_MODE = stringPreferencesKey("panel_voice_mode")
        private val KEY_BUBBLE_ICON_EMOJI = stringPreferencesKey("bubble_icon_emoji")
        private val KEY_BUBBLE_ICON_IMAGE = stringPreferencesKey("bubble_icon_image_path")
        private val KEY_SCREEN_SENSE_REGION = booleanPreferencesKey("screen_sense_region_enabled")
        private val KEY_MAX_TURNS = intPreferencesKey("conversation_max_turns")
        private val KEY_DIARY_TAGS = stringPreferencesKey("diary_tags_csv")
        private val KEY_SECRET_LOG = booleanPreferencesKey("secret_log_enabled")
        private val KEY_AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
        private val KEY_AUTO_BACKUP_INTERVAL = intPreferencesKey("auto_backup_interval_days")

        private fun capabilityKey(c: Capability) = stringPreferencesKey("capability_${c.name}")

        /** 默认日记标签词汇表（用户可增删） */
        const val DEFAULT_DIARY_TAGS_CSV = "工作,生活,待办,经验"

        /** v1.4.0 旧版默认标签；仅用于启动时迁移未自定义的用户 */
        private const val LEGACY_DEFAULT_DIARY_TAGS_CSV = "AI与开发,物理学习与科研,生活,待办,经验"
    }
}
