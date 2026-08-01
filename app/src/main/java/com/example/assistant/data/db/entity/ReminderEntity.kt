package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 定时提醒。
 * repeatRule：null = 一次性；"daily" = 每天；"weekly" = 每周（同星期几）
 * status："pending" | "fired" | "cancelled"
 * ackedAtEpochMillis：用户手动确认时间（通知点击 → App 弹窗确认）。
 * 判断"本次触发是否已确认"：ackedAt >= triggerAtEpochMillis（重复提醒每天触发后需重新确认）。
 * 未确认的提醒触发后每 5 分钟重复通知，直到确认（见 ReminderReceiver / ReminderScheduler）。
 */
@Entity(
    tableName = "reminders",
    indices = [Index("triggerAtEpochMillis")]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAtEpochMillis: Long,
    val repeatRule: String? = null,
    val status: String = "pending",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val ackedAtEpochMillis: Long? = null
)
