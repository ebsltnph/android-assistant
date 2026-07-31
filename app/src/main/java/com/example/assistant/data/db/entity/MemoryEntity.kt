package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 长期记忆：模型认为重要的关于用户的事实。
 * 每次对话注入系统上下文（渲染上限 50 条，排序稳定保证缓存前缀一致）。
 */
@Entity(
    tableName = "memories",
    indices = [Index("category")]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String = "general",
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
