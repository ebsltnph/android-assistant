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
    /**
     * @deprecated DB v6 起图片改存 diary_images 表（一条目多张），本列不再写入新值。
     * 保留列与字段是为了迁移兼容（老数据已迁入 diary_images 表），新代码一律用图片表。
     */
    @Suppress("DEPRECATION")
    @Deprecated("用 DiaryImageEntity（diary_images 表）代替")
    val imagePath: String? = null
)
