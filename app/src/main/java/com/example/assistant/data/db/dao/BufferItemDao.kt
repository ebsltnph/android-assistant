package com.example.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.assistant.data.db.entity.BufferItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「进行中的事」缓冲区条目 DAO。
 * 所有查询按 **id 升序**返回：排序稳定 = 注入文本稳定 = 缓存前缀稳定。
 */
@Dao
interface BufferItemDao {

    @Query("SELECT * FROM buffer_items ORDER BY id ASC")
    fun itemsFlow(): Flow<List<BufferItemEntity>>

    @Query("SELECT * FROM buffer_items ORDER BY id ASC")
    suspend fun all(): List<BufferItemEntity>

    /** 参与注入的条目（归档的不在其中） */
    @Query("SELECT * FROM buffer_items WHERE status = 'active' ORDER BY id ASC")
    suspend fun active(): List<BufferItemEntity>

    @Query("SELECT * FROM buffer_items WHERE id = :id")
    suspend fun byId(id: Long): BufferItemEntity?

    @Insert
    suspend fun insert(item: BufferItemEntity): Long

    @Insert
    suspend fun insertAll(items: List<BufferItemEntity>)

    @Update
    suspend fun update(item: BufferItemEntity)

    /** 只改状态（归档 / 取消归档） */
    @Query("UPDATE buffer_items SET status = :status, updatedAtEpochMillis = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long)

    @Query("DELETE FROM buffer_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM buffer_items")
    suspend fun clearAll()
}
