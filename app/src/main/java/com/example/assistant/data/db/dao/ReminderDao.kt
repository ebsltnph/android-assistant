package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY triggerAtEpochMillis ASC")
    fun remindersFlow(): Flow<List<ReminderEntity>>

    /** 未触发的提醒（重启后重排用） */
    @Query("SELECT * FROM reminders WHERE status = 'pending' AND triggerAtEpochMillis >= :nowMillis ORDER BY triggerAtEpochMillis ASC")
    suspend fun pendingReminders(nowMillis: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE reminders SET triggerAtEpochMillis = :newTime, status = 'pending' WHERE id = :id")
    suspend fun reschedule(id: Long, newTime: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}
