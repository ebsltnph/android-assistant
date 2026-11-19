package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    /**
     * 管理页/首页用：**全量**记忆，新在前。
     * 不能 LIMIT 50——否则第 51 条起的新记忆既不出现在列表、首页计数也停在 50，
     * 表现为「工具返回成功但记忆没写进去」（2026-11-16 修复）。
     */
    @Query("SELECT * FROM memories ORDER BY id DESC")
    fun memoriesFlow(): Flow<List<MemoryEntity>>

    /**
     * 注入对话用：**全部**记忆，按 id 升序 → 渲染顺序稳定、利于缓存前缀。
     * 不做条数截断：记忆条目都很短（本机 50+ 条合计约 3k 字符），
     * 截最旧会丢称呼/专业等早期核心事实，截最新又会让新记忆进不了上下文。
     */
    @Query("SELECT * FROM memories ORDER BY id ASC")
    suspend fun memoriesForContext(): List<MemoryEntity>

    /** 首页只显示总数：避免为了一个数字把全量记忆都读进内存 */
    @Query("SELECT COUNT(*) FROM memories")
    fun countFlow(): Flow<Int>

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
