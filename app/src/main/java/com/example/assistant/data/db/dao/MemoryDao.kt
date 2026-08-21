package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    /** 按 id 升序（排序稳定 → 缓存前缀稳定），最多 50 条 */
    @Query("SELECT * FROM memories ORDER BY id ASC LIMIT 50")
    fun memoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY id ASC LIMIT 50")
    suspend fun allMemories(): List<MemoryEntity>

    /** 备份用：全量记忆（不含 LIMIT——备份不能丢 50 条之后的内容） */
    @Query("SELECT * FROM memories ORDER BY id ASC")
    suspend fun allMemoriesFull(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Insert
    suspend fun insertAll(memories: List<MemoryEntity>)

    /** 单条编辑：更新事实内容（分类/时间不变） */
    @Query("UPDATE memories SET fact = :fact WHERE id = :id")
    suspend fun updateFact(id: Long, fact: String)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}
