package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.EventDao
import com.example.assistant.data.db.entity.MonitoredEventEntity
import kotlinx.coroutines.flow.Flow

class EventRepository(private val dao: EventDao) {

    val events: Flow<List<MonitoredEventEntity>> = dao.eventsFlow()

    suspend fun add(displayName: String, searchQuery: String, conditionKeywords: String = "", pollHours: Int = 24): Long =
        dao.insert(
            MonitoredEventEntity(
                displayName = displayName,
                searchQuery = searchQuery,
                conditionKeywords = conditionKeywords,
                pollHours = pollHours
            )
        )

    suspend fun enabledEvents(): List<MonitoredEventEntity> = dao.enabledEvents()

    suspend fun markChecked(id: Long, time: Long) = dao.updateChecked(id, time)

    suspend fun markNotified(id: Long, time: Long) = dao.updateNotified(id, time)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: Long) = dao.delete(id)
}
