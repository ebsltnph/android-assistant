package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.SummaryDao
import com.example.assistant.data.db.entity.DailySummaryEntity
import com.example.assistant.data.db.entity.PeriodSummaryEntity
import kotlinx.coroutines.flow.Flow

class SummaryRepository(private val dao: SummaryDao) {

    /** 全部历史小结（日期倒序） */
    val summaries: Flow<List<DailySummaryEntity>> = dao.allFlow()

    /** 保存当天小结（同日期覆盖，保证一天只有最新一条） */
    suspend fun saveToday(summary: String, date: String) {
        dao.upsert(DailySummaryEntity(date = date, summary = summary))
    }

    suspend fun byDate(date: String): DailySummaryEntity? = dao.byDate(date)

    suspend fun latest(): DailySummaryEntity? = dao.latest()

    // ---- 期间总结历史（日记页「期间总结」，保留最近 5 条） ----

    val periodSummaries: Flow<List<PeriodSummaryEntity>> = dao.periodSummariesFlow()

    suspend fun savePeriodSummary(fromMillis: Long, toMillis: Long, summary: String) {
        dao.insertPeriodSummary(
            PeriodSummaryEntity(fromMillis = fromMillis, toMillis = toMillis, summary = summary)
        )
        dao.prunePeriodSummaries(PeriodSummaryEntity.MAX_KEEP)
    }
}
