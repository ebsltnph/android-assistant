package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.EventDao
import com.example.assistant.data.db.entity.EventHitEntity
import com.example.assistant.data.db.entity.MonitoredEventEntity
import kotlinx.coroutines.flow.Flow

class EventRepository(private val dao: EventDao) {

    val events: Flow<List<MonitoredEventEntity>> = dao.eventsFlow()

    suspend fun add(
        displayName: String, searchQuery: String, conditionKeywords: String = "",
        customRule: String = "", includeDomains: String = "", pollHours: Int = 24
    ): Long =
        dao.insert(
            MonitoredEventEntity(
                displayName = displayName,
                searchQuery = searchQuery,
                conditionKeywords = conditionKeywords,
                customRule = customRule,
                includeDomains = includeDomains,
                pollHours = pollHours
            )
        )

    suspend fun enabledEvents(): List<MonitoredEventEntity> = dao.enabledEvents()

    suspend fun eventById(id: Long): MonitoredEventEntity? = dao.byId(id)

    suspend fun markChecked(id: Long, time: Long) = dao.updateChecked(id, time)

    suspend fun markNotified(id: Long, time: Long) = dao.updateNotified(id, time)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun updatePollHours(id: Long, pollHours: Int) = dao.updatePollHours(id, pollHours)

    suspend fun updateConfig(
        id: Long, displayName: String, searchQuery: String,
        conditionKeywords: String, customRule: String, includeDomains: String
    ) = dao.updateConfig(id, displayName, searchQuery, conditionKeywords, customRule, includeDomains)

    suspend fun delete(id: Long) = dao.delete(id)

    // ---- 触发历史（DB v6） ----

    /** 记录一次命中（保留最近 [EventDao.MAX_HITS_PER_EVENT] 条，旧的自动清理） */
    suspend fun addHit(eventId: Long, title: String, url: String, content: String) {
        dao.insertHit(
            EventHitEntity(
                eventId = eventId,
                title = title.take(200),
                url = url,
                content = content.take(2000),
                hitAtEpochMillis = System.currentTimeMillis()
            )
        )
        dao.trimHits(eventId)
    }

    /** 某事件的触发历史（新→旧） */
    fun hitsFor(eventId: Long): Flow<List<EventHitEntity>> = dao.hitsFlow(eventId)
}
