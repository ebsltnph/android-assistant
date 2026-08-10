package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 每日小结历史：每个日期只保留最新一条（重复生成时覆盖更新）。
 * 同时镜像写入系统日历（CalendarProvider），供日历 App 查看。
 */
@Entity(
    tableName = "daily_summaries",
    indices = [Index(value = ["date"], unique = true)]
)
@Serializable
data class DailySummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 日期 yyyy-MM-dd（唯一，每天一条） */
    val date: String,
    val summary: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
