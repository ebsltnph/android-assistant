package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件监控的触发历史（DB v6）：每次命中通知时记录一条，App 内可查看。
 * 删除监控事件时级联删除（onDelete CASCADE）。
 * 每事件最多保留 [EventDao.MAX_HITS_PER_EVENT] 条（新增时清理最旧的）。
 */
@Entity(
    tableName = "event_hits",
    foreignKeys = [
        ForeignKey(
            entity = MonitoredEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("eventId")]
)
data class EventHitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    /** 命中结果标题（搜索结果标题） */
    val title: String = "",
    /** 命中结果链接 */
    val url: String = "",
    /** 命中内容摘要（搜索结果正文摘要，本地关键词命中时用标题兜底） */
    val content: String = "",
    val hitAtEpochMillis: Long = System.currentTimeMillis()
)
