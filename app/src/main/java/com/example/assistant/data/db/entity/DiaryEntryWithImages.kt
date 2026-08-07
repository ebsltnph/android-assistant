package com.example.assistant.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * 日记条目 + 其图片列表（@Relation 一次查询带回，UI 直接展示）。
 * 注意：Room 的 @Relation 查询必须标 @Transaction（DAO 里已标注）。
 */
data class DiaryEntryWithImages(
    @Embedded val entry: DiaryEntryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "entryId"
    )
    val images: List<DiaryImageEntity>
)
