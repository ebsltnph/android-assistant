package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 期间日记总结历史（日记页「期间总结」生成）。
 * 只保留最近 [MAX_KEEP] 条，新生成自动清理最旧的。
 * 与每日小结不同：不镜像系统日历，仅用于 App 内重新查看。
 */
@Entity(tableName = "period_summaries")
@Serializable
data class PeriodSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 起始时间戳（含） */
    val fromMillis: Long,
    /** 结束时间戳（开区间，即结束日期次日 0 点） */
    val toMillis: Long,
    /** 总结全文（含「📅 范围」标题行） */
    val summary: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val MAX_KEEP = 5
    }
}
