package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 日记本（如：生活、工作） */
@Entity(tableName = "diary_books")
data class DiaryBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
