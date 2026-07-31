package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 定时提醒。
 * repeatRule：null = 一次性；"daily" = 每天；"weekly" = 每周（同星期几）
 * status："pending" | "fired" | "cancelled"
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
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
