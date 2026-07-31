package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 新闻类事件监控：周期搜索指定话题，命中条件时通知。
 * conditionKeywords：逗号分隔的命中关键词（可空 = 有相关结果即通知，交给 LLM 判断）
 */
@Entity(tableName = "monitored_events")
data class MonitoredEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val searchQuery: String,
    val conditionKeywords: String = "",
    val pollHours: Int = 24,
    val enabled: Boolean = true,
    val lastCheckedAtEpochMillis: Long = 0,
    val lastNotifiedAtEpochMillis: Long = 0,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
