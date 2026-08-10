package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 新闻类事件监控：周期搜索指定话题，命中条件时通知。
 * conditionKeywords：逗号分隔的命中关键词（可空 = 有相关结果即通知，交给 LLM 判断）
 * customRule：用户自定义判断规则（如"只关注某更新文档的变更"），非空时 LLM 判断必须遵守
 * includeDomains：逗号分隔的限定域名（只搜这些来源，如 api-docs.deepseek.com）
 */
@Entity(tableName = "monitored_events")
@Serializable
data class MonitoredEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val searchQuery: String,
    val conditionKeywords: String = "",
    /** 自定义判断规则（LLM 判断时附加），空 = 不附加 */
    val customRule: String = "",
    /** 限定搜索来源域名（逗号分隔），空 = 不限 */
    val includeDomains: String = "",
    val pollHours: Int = 24,
    val enabled: Boolean = true,
    val lastCheckedAtEpochMillis: Long = 0,
    val lastNotifiedAtEpochMillis: Long = 0,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
