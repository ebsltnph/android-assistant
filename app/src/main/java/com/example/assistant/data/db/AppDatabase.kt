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
import com.example.assistant.data.db.entity.DiaryImageEntity
import com.example.assistant.data.db.entity.EventHitEntity
import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.db.entity.MonitoredEventEntity
import com.example.assistant.data.db.entity.PeriodSummaryEntity
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
        DiaryImageEntity::class,
        MemoryEntity::class,
        ReminderEntity::class,
        MonitoredEventEntity::class,
        EventHitEntity::class,
        DailySummaryEntity::class,
        PeriodSummaryEntity::class
    ],
    version = 7,
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
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7
                )
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

        /**
         * v5 → v6：① 日记图片改为独立表（一条目多张）——旧单图列数据迁入；
         * ② 新增事件监控触发历史表（event_hits）。
         * 旧 imagePath 列保留不删（SQLite DROP COLUMN 在旧设备不可用，且无副作用）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ① 日记图片表（外键级联：删条目自动删图片记录）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diary_images` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entryId` INTEGER NOT NULL, " +
                        "`path` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(`entryId`) REFERENCES `diary_entries`(`id`) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_diary_images_entryId` ON `diary_images` (`entryId`)"
                )
                // 老单图数据迁入新表（position 0 = 顺序第一张）
                db.execSQL(
                    "INSERT INTO `diary_images` (`entryId`, `path`, `position`) " +
                        "SELECT `id`, `imagePath`, 0 FROM `diary_entries` " +
                        "WHERE `imagePath` IS NOT NULL AND `imagePath` != ''"
                )
                // ② 事件监控触发历史表（外键级联：删事件自动删历史）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_hits` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`eventId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL DEFAULT '', " +
                        "`url` TEXT NOT NULL DEFAULT '', " +
                        "`content` TEXT NOT NULL DEFAULT '', " +
                        "`hitAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`eventId`) REFERENCES `monitored_events`(`id`) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_hits_eventId` ON `event_hits` (`eventId`)"
                )
            }
        }

        /** v6 → v7：新增期间日记总结历史表（日记页「期间总结」，保留最近 5 条） */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `period_summaries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fromMillis` INTEGER NOT NULL, " +
                        "`toMillis` INTEGER NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL)"
                )
            }
        }
    }
}
