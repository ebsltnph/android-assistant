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

    /** 已过期但仍 pending 的僵尸提醒（清理用） */
    @Query("SELECT * FROM reminders WHERE status = 'pending' AND triggerAtEpochMillis < :nowMillis")
    suspend fun stalePending(nowMillis: Long): List<ReminderEntity>

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

    /** 清理已触发且过期的提醒（一次性提醒触发后自动从列表消失） */
    @Query("DELETE FROM reminders WHERE status = 'fired' AND triggerAtEpochMillis < :beforeMillis")
    suspend fun deleteFiredBefore(beforeMillis: Long)

    /** 清理已过期但未触发的僵尸提醒（如日期已过的测试残留） */
    @Query("DELETE FROM reminders WHERE status = 'pending' AND triggerAtEpochMillis < :nowMillis")
    suspend fun deleteStalePending(nowMillis: Long)
}
