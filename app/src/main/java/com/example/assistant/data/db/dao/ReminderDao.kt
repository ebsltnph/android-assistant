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

    /** 已过期但仍 pending 的僵尸提醒（清理用）——只清**已确认过**的（未确认的还在 5 分钟重复通知流程中） */
    @Query("SELECT * FROM reminders WHERE status = 'pending' AND triggerAtEpochMillis < :nowMillis AND ackedAtEpochMillis IS NOT NULL")
    suspend fun stalePending(nowMillis: Long): List<ReminderEntity>

    /** 已触发（时间已到）但**未确认**的提醒：重启/开机后恢复 5 分钟重复闹钟用 */
    @Query("SELECT * FROM reminders WHERE status = 'pending' AND triggerAtEpochMillis < :nowMillis AND (ackedAtEpochMillis IS NULL OR ackedAtEpochMillis < triggerAtEpochMillis)")
    suspend fun unackedFiredPending(nowMillis: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE reminders SET triggerAtEpochMillis = :newTime, status = 'pending' WHERE id = :id")
    suspend fun reschedule(id: Long, newTime: Long)

    /** 用户确认提醒（通知点击 → App 弹窗 → 确认） */
    @Query("UPDATE reminders SET ackedAtEpochMillis = :time WHERE id = :id")
    suspend fun ack(id: Long, time: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    /** 清理已触发且过期的提醒（一次性提醒确认后从列表消失） */
    @Query("DELETE FROM reminders WHERE status = 'fired' AND triggerAtEpochMillis < :beforeMillis")
    suspend fun deleteFiredBefore(beforeMillis: Long)

    /** 清理已过期但未触发的僵尸提醒（如日期已过的测试残留）——只清已确认过的 */
    @Query("DELETE FROM reminders WHERE status = 'pending' AND triggerAtEpochMillis < :nowMillis AND ackedAtEpochMillis IS NOT NULL")
    suspend fun deleteStalePending(nowMillis: Long)

    // ---- 备份/恢复用 ----

    @Query("SELECT * FROM reminders ORDER BY id ASC")
    suspend fun all(): List<ReminderEntity>

    @Insert
    suspend fun insertAll(reminders: List<ReminderEntity>)

    @Query("DELETE FROM reminders")
    suspend fun clearAll()
}
