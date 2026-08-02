package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 日记条目：带准确时间戳，归属于某个日记本 */
@Entity(
    tableName = "diary_entries",
    foreignKeys = [
        ForeignKey(
            entity = DiaryBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("createdAtEpochMillis")]
)
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val content: String,
    /** 来源："voice" 语音记录 | "text" 文字 | "chat" 聊天转存 */
    val source: String = "text",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** 条目附图（filesDir/diary_images 下的 JPEG 路径），null = 无图 */
    val imagePath: String? = null
)
