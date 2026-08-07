package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日记条目的图片（一条目可多张，DB v6 起取代旧单图 imagePath 列）。
 * 图片文件存 filesDir/diary_images（内部存储，系统清理不掉），DB 只存路径。
 * position：图片顺序（添加顺序），UI 按此排序展示。
 * 删除条目时级联删除本表记录（文件删除由 ViewModel 负责）。
 */
@Entity(
    tableName = "diary_images",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("entryId")]
)
data class DiaryImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val path: String,
    val position: Int = 0
)
