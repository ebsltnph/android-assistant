package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.assistant.data.db.entity.DailySummaryEntity
import com.example.assistant.data.db.entity.PeriodSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {

    /** 按日期 upsert：同一天重复生成只保留最新一条（date 唯一索引） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DailySummaryEntity)

    /** 全部历史小结，日期倒序 */
    @Query("SELECT * FROM daily_summaries ORDER BY date DESC")
    fun allFlow(): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries WHERE date = :date LIMIT 1")
    suspend fun byDate(date: String): DailySummaryEntity?

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT 1")
    suspend fun latest(): DailySummaryEntity?

    // ---- 期间总结历史（日记页「期间总结」） ----

    @Insert
    suspend fun insertPeriodSummary(summary: PeriodSummaryEntity): Long

    @Query("SELECT * FROM period_summaries ORDER BY createdAtEpochMillis DESC")
    fun periodSummariesFlow(): Flow<List<PeriodSummaryEntity>>

    /** 只保留最新 [keep] 条（id 不在最新 keep 条之内的删掉） */
    @Query(
        "DELETE FROM period_summaries WHERE id NOT IN " +
            "(SELECT id FROM period_summaries ORDER BY createdAtEpochMillis DESC, id DESC LIMIT :keep)"
    )
    suspend fun prunePeriodSummaries(keep: Int)

    // ---- 备份/恢复用 ----

    @Query("SELECT * FROM daily_summaries ORDER BY id ASC")
    suspend fun all(): List<DailySummaryEntity>

    @Insert
    suspend fun insertAll(summaries: List<DailySummaryEntity>)

    @Query("DELETE FROM daily_summaries")
    suspend fun clearAll()
}
