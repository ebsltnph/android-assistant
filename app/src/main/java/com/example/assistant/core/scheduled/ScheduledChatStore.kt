package com.example.assistant.core.scheduled

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 定时对话配置的 DataStore（单一 JSON 数组键；配置量小，不做 Room 表与迁移） */
private val Context.scheduledChatDataStore by preferencesDataStore(
    name = "scheduled_chats",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class ScheduledChatStore(context: Context) {

    private val dataStore = context.applicationContext.scheduledChatDataStore

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 全部配置（按创建/保存顺序） */
    val schedules: Flow<List<ScheduledChat>> = dataStore.data.map { prefs ->
        decode(prefs[KEY_SCHEDULES])
    }

    suspend fun all(): List<ScheduledChat> = schedules.first()

    suspend fun byId(id: Long): ScheduledChat? = all().firstOrNull { it.id == id }

    /**
     * 新增或更新（id <= 0 视为新增，自动分配自增 id）。
     * 返回真正落库的配置（新增时带上了分配的 id/createdAt）。
     */
    suspend fun upsert(item: ScheduledChat): ScheduledChat {
        var saved = item
        dataStore.edit { prefs ->
            val list = decode(prefs[KEY_SCHEDULES]).toMutableList()
            val idx = if (item.id > 0) list.indexOfFirst { it.id == item.id } else -1
            val now = System.currentTimeMillis()
            saved = if (idx >= 0) {
                val old = list[idx]
                // 编辑页可能开着很久：触发时间/上次触发已被 Worker 更新，保存时不能回滚。
                // 任何配置字段变化都刷新 configChangedAt（避免"改到刚过去的时刻"立刻补跑）。
                val changed = old.name != item.name ||
                    old.prompt != item.prompt ||
                    old.repeat != item.repeat ||
                    old.weekday != item.weekday ||
                    old.minuteOfDay != item.minuteOfDay ||
                    old.enabled != item.enabled
                item.copy(
                    createdAt = old.createdAt.takeIf { it > 0L } ?: item.createdAt,
                    lastTriggeredAt = maxOf(old.lastTriggeredAt, item.lastTriggeredAt),
                    configChangedAt = if (changed) now else old.configChangedAt
                ).also { list[idx] = it }
            } else {
                item.copy(
                    id = (list.maxOfOrNull { it.id } ?: 0L) + 1,
                    createdAt = item.createdAt.takeIf { it > 0L } ?: now,
                    lastTriggeredAt = 0L,
                    configChangedAt = now
                ).also { list += it }
            }
            prefs[KEY_SCHEDULES] = json.encodeToString(list)
        }
        return saved
    }

    suspend fun delete(id: Long) {
        dataStore.edit { prefs ->
            val list = decode(prefs[KEY_SCHEDULES]).filterNot { it.id == id }
            prefs[KEY_SCHEDULES] = json.encodeToString(list)
        }
    }

    /** 备份恢复：整体替换（空列表 = 清空）；恢复后不立即补跑，统一视为刚改过配置 */
    suspend fun replaceAll(items: List<ScheduledChat>) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[KEY_SCHEDULES] = json.encodeToString(
                items.map { it.sanitized().copy(configChangedAt = now) }
            )
        }
    }

    /** 标记某条最近一次触发时刻（仅展示；不改变排程） */
    suspend fun markTriggered(id: Long, at: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            val list = decode(prefs[KEY_SCHEDULES]).map {
                if (it.id == id) it.copy(lastTriggeredAt = at) else it
            }
            prefs[KEY_SCHEDULES] = json.encodeToString(list)
        }
    }

    private fun decode(raw: String?): List<ScheduledChat> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<ScheduledChat>>(raw)
                .map { it.sanitized() }
                .distinctBy { it.id }
                .sortedBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 恢复/解析时的防御：非法值收敛到可用范围，避免坏配置把闹钟排到意外时刻 */
    private fun ScheduledChat.sanitized(): ScheduledChat = copy(
        name = name.take(60),
        prompt = prompt.ifBlank { ScheduledChat.DEFAULT_PROMPT },
        repeat = if (repeat == ScheduledChat.REPEAT_WEEKLY) {
            ScheduledChat.REPEAT_WEEKLY
        } else {
            ScheduledChat.REPEAT_DAILY
        },
        weekday = weekday.coerceIn(1, 7),
        minuteOfDay = minuteOfDay.coerceIn(0, 24 * 60 - 1),
        // 旧数据没有 configChangedAt：按创建时间算（补跑条件仍可成立）
        configChangedAt = configChangedAt.takeIf { it > 0L } ?: createdAt
    )

    companion object {
        private val KEY_SCHEDULES = stringPreferencesKey("schedules_json")
    }
}
