package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 长期记忆：模型认为重要的关于用户的事实。
 * 每次对话注入系统上下文的**全部**条目（按 id 升序渲染，顺序稳定保证缓存前缀一致）；
 * 管理页同样展示全量，不再有 50 条截断。
 */
@Entity(
    tableName = "memories",
    indices = [Index("category")]
)
@Serializable
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String = "general",
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
