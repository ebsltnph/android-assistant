package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.db.entity.EventHitEntity
import com.example.assistant.data.db.entity.MonitoredEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query("SELECT * FROM monitored_events ORDER BY createdAtEpochMillis ASC")
    fun eventsFlow(): Flow<List<MonitoredEventEntity>>

    @Query("SELECT * FROM monitored_events WHERE enabled = 1")
    suspend fun enabledEvents(): List<MonitoredEventEntity>

    @Query("SELECT * FROM monitored_events WHERE id = :id")
    suspend fun byId(id: Long): MonitoredEventEntity?

    @Insert
    suspend fun insert(event: MonitoredEventEntity): Long

    @Query("UPDATE monitored_events SET lastCheckedAtEpochMillis = :time WHERE id = :id")
    suspend fun updateChecked(id: Long, time: Long)

    @Query("UPDATE monitored_events SET lastNotifiedAtEpochMillis = :time WHERE id = :id")
    suspend fun updateNotified(id: Long, time: Long)

    @Query("UPDATE monitored_events SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 更新轮询周期（小时） */
    @Query("UPDATE monitored_events SET pollHours = :pollHours WHERE id = :id")
    suspend fun updatePollHours(id: Long, pollHours: Int)

    /** 更新展示名/搜索词/条件关键词/自定义规则/限定域名 */
    @Query(
        "UPDATE monitored_events SET displayName = :displayName, searchQuery = :searchQuery, " +
            "conditionKeywords = :conditionKeywords, customRule = :customRule, includeDomains = :includeDomains WHERE id = :id"
    )
    suspend fun updateConfig(
        id: Long, displayName: String, searchQuery: String,
        conditionKeywords: String, customRule: String, includeDomains: String
    )

    @Query("DELETE FROM monitored_events WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- 触发历史（DB v6） ----

    @Insert
    suspend fun insertHit(hit: EventHitEntity)

    /** 某事件的触发历史（新→旧，最多 [MAX_HITS_PER_EVENT] 条） */
    @Query("SELECT * FROM event_hits WHERE eventId = :eventId ORDER BY hitAtEpochMillis DESC, id DESC LIMIT :limit")
    fun hitsFlow(eventId: Long, limit: Int = MAX_HITS_PER_EVENT): Flow<List<EventHitEntity>>

    /** 新增命中时清掉最旧的记录（只保留最近 [MAX_HITS_PER_EVENT] 条） */
    @Query(
        "DELETE FROM event_hits WHERE eventId = :eventId AND id NOT IN " +
            "(SELECT id FROM event_hits WHERE eventId = :eventId ORDER BY hitAtEpochMillis DESC, id DESC LIMIT :limit)"
    )
    suspend fun trimHits(eventId: Long, limit: Int = MAX_HITS_PER_EVENT)

    // ---- 备份/恢复用 ----

    @Query("SELECT * FROM monitored_events ORDER BY id ASC")
    suspend fun allEvents(): List<MonitoredEventEntity>

    @Query("SELECT * FROM event_hits ORDER BY id ASC")
    suspend fun allHits(): List<EventHitEntity>

    @Insert
    suspend fun insertAll(events: List<MonitoredEventEntity>)

    @Insert
    suspend fun insertAllHits(hits: List<EventHitEntity>)

    @Query("DELETE FROM event_hits")
    suspend fun clearAllHits()

    @Query("DELETE FROM monitored_events")
    suspend fun clearAll()

    companion object {
        /** 每个事件保留的触发历史条数上限（防无限增长；用户要求精简只看最近几条） */
        const val MAX_HITS_PER_EVENT = 5
    }
}
