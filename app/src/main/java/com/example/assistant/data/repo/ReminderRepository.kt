package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.ReminderDao
import com.example.assistant.data.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

class ReminderRepository(private val dao: ReminderDao) {

    val reminders: Flow<List<ReminderEntity>> = dao.remindersFlow()

    suspend fun add(title: String, triggerAtEpochMillis: Long, repeatRule: String? = null): Long =
        dao.insert(ReminderEntity(title = title, triggerAtEpochMillis = triggerAtEpochMillis, repeatRule = repeatRule))

    suspend fun pending(nowMillis: Long): List<ReminderEntity> = dao.pendingReminders(nowMillis)

    suspend fun markFired(id: Long) = dao.updateStatus(id, "fired")

    suspend fun reschedule(id: Long, newTimeMillis: Long) = dao.reschedule(id, newTimeMillis)

    suspend fun cancel(id: Long) = dao.updateStatus(id, "cancelled")

    suspend fun delete(id: Long) = dao.delete(id)
}
