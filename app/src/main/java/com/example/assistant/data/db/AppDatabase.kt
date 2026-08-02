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
 * 种子数据（「日记」本）由 DiaryRepository.ensureSeedBooks() 在 Application 启动时确保，
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
    version = 5,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

        /** v3 → v4：提醒加"用户确认时间"列（通知点击 → App 弹窗确认后才停止 5 分钟重复） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `ackedAtEpochMillis` INTEGER"
                )
            }
        }

        /**
         * v4 → v5：① 日记条目加图片列（filesDir 路径，可空）；
         * ② 多日记本合并为单一「日记」本——新建默认本 → 工作/生活条目挪入 → 删旧本。
         * 用户自建的第三本（如「旅行」）不合并，数据保留（UI 不再暴露）。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ① 条目附图列（可空，无默认值）
                db.execSQL("ALTER TABLE `diary_entries` ADD COLUMN `imagePath` TEXT")
                // ② 新建「日记」默认本（仅当不存在，防重复迁移/重装残留）
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO `diary_books` (`name`, `isDefault`, `createdAtEpochMillis`) " +
                        "SELECT '日记', 1, $now " +
                        "WHERE NOT EXISTS (SELECT 1 FROM `diary_books` WHERE `name` = '日记')"
                )
                // ③ 把「工作」「生活」的条目挪到「日记」本（先挪再删，CASCADE 不误删数据）
                db.execSQL(
                    "UPDATE `diary_entries` SET `bookId` = " +
                        "(SELECT `id` FROM `diary_books` WHERE `name` = '日记' LIMIT 1) " +
                        "WHERE `bookId` IN (SELECT `id` FROM `diary_books` WHERE `name` IN ('工作','生活'))"
                )
                // ④ 删除两个旧本（条目已挪走，无残留）
                db.execSQL("DELETE FROM `diary_books` WHERE `name` IN ('工作','生活')")
            }
        }
    }
}
