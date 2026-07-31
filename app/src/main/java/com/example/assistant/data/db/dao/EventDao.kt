package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.db.entity.MonitoredEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query("SELECT * FROM monitored_events ORDER BY createdAtEpochMillis ASC")
    fun eventsFlow(): Flow<List<MonitoredEventEntity>>

    @Query("SELECT * FROM monitored_events WHERE enabled = 1")
    suspend fun enabledEvents(): List<MonitoredEventEntity>

    @Insert
    suspend fun insert(event: MonitoredEventEntity): Long

    @Query("UPDATE monitored_events SET lastCheckedAtEpochMillis = :time WHERE id = :id")
    suspend fun updateChecked(id: Long, time: Long)

    @Query("UPDATE monitored_events SET lastNotifiedAtEpochMillis = :time WHERE id = :id")
    suspend fun updateNotified(id: Long, time: Long)

    @Query("UPDATE monitored_events SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM monitored_events WHERE id = :id")
    suspend fun delete(id: Long)
}
