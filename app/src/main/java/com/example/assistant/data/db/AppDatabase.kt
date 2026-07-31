package com.example.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.assistant.data.db.dao.DiaryDao
import com.example.assistant.data.db.dao.EventDao
import com.example.assistant.data.db.dao.MemoryDao
import com.example.assistant.data.db.dao.ReminderDao
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
        MonitoredEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun diaryDao(): DiaryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun reminderDao(): ReminderDao
    abstract fun eventDao(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "assistant.db")
                .build()
    }
}
