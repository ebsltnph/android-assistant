package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.MemoryDao
import com.example.assistant.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val dao: MemoryDao) {

    val memories: Flow<List<MemoryEntity>> = dao.memoriesFlow()

    /** 注入对话用的记忆文本（按 id 升序、最多 50 条 → 排序稳定保证缓存前缀一致） */
    suspend fun memoryContextText(): String? {
        val list = dao.allMemories()
        if (list.isEmpty()) return null
        return list.joinToString("\n") { it.fact }
    }

    suspend fun addFact(fact: String, category: String = "general"): Long =
        dao.insert(MemoryEntity(fact = fact, category = category))

    suspend fun addFacts(facts: List<MemoryEntity>) = dao.insertAll(facts)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clearAll() = dao.clearAll()
}
