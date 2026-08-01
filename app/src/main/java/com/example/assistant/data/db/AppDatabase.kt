package com.example.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.assistant.data.db.dao.DiaryDao
import com.example.assistant.data.db.dao.EventDao
import com.example.assistant.data.db.dao.MemoryDao
import com.example.assistant.data.db.dao.ReminderDao
import com.example.assistant.data.db.dao.SummaryDao
import com.example.assistant.data.db.entity.DailySummaryEntity
import com.example.assistant.data.db.entity.DiaryBookEntity
import com.example.assistant.data.db.entity.DiaryEntryEntity
import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.db.entity.MonitoredEventEntity
import com.example.assistant.data.db.entity.ReminderEntity

/**
 * 应用数据库。
 * 种子数据（生活/工作日记本）由 DiaryRepository.ensureSeedBooks() 在 Application 启动时确保，
 * 避免在 Room Callback 中二次建库的循环问题。
 */
@Database(
    entities = [
        DiaryBookEntity::class,
        DiaryEntryEntity::class,
        MemoryEntity::class,
        ReminderEntity::class,
        MonitoredEventEntity::class,
        DailySummaryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun diaryDao(): DiaryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun reminderDao(): ReminderDao
    abstract fun eventDao(): EventDao
    abstract fun summaryDao(): SummaryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "assistant.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** v1 → v2：新增每日小结表（历史小结） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_summaries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_summaries_date` ON `daily_summaries` (`date`)"
                )
            }
        }

        /** v2 → v3：事件监控加自定义规则与限定域名列 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `monitored_events` ADD COLUMN `customRule` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `monitored_events` ADD COLUMN `includeDomains` TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
