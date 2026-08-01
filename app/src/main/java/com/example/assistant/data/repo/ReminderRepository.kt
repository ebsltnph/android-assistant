package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.ReminderDao
import com.example.assistant.data.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

class ReminderRepository(private val dao: ReminderDao) {

    val reminders: Flow<List<ReminderEntity>> = dao.remindersFlow()

    suspend fun add(title: String, triggerAtEpochMillis: Long, repeatRule: String? = null): Long =
        dao.insert(ReminderEntity(title = title, triggerAtEpochMillis = triggerAtEpochMillis, repeatRule = repeatRule))

    suspend fun pending(nowMillis: Long): List<ReminderEntity> = dao.pendingReminders(nowMillis)

    suspend fun byId(id: Long): ReminderEntity? = dao.byId(id)

    suspend fun markFired(id: Long) = dao.updateStatus(id, "fired")

    suspend fun reschedule(id: Long, newTimeMillis: Long) = dao.reschedule(id, newTimeMillis)

    suspend fun cancel(id: Long) = dao.updateStatus(id, "cancelled")

    suspend fun delete(id: Long) = dao.delete(id)

    /** 用户确认提醒（通知点击 → App 弹窗 → 确认） */
    suspend fun ack(id: Long) = dao.ack(id, System.currentTimeMillis())

    /** 已触发但未确认的提醒（重启/开机后恢复 5 分钟重复闹钟） */
    suspend fun unackedFiredPending(nowMillis: Long): List<ReminderEntity> = dao.unackedFiredPending(nowMillis)

    /** 清理已触发且触发时间早于 beforeMillis 的一次性提醒（App 启动时调用） */
    suspend fun cleanupFired(beforeMillis: Long) = dao.deleteFiredBefore(beforeMillis)

    /** 已过期但仍 pending 的僵尸提醒（App 启动时取消闹钟后删除）——只清已确认过的 */
    suspend fun stalePending(nowMillis: Long): List<ReminderEntity> = dao.stalePending(nowMillis)

    suspend fun deleteStalePending(nowMillis: Long) = dao.deleteStalePending(nowMillis)
}
