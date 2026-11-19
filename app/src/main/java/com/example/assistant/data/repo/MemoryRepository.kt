package com.example.assistant.data.repo

import com.example.assistant.data.db.dao.MemoryDao
import com.example.assistant.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val dao: MemoryDao) {

    /** 管理页用：全量记忆（新在前） */
    val memories: Flow<List<MemoryEntity>> = dao.memoriesFlow()

    /** 首页摘要用：记忆总数（不受渲染上限影响） */
    val memoryCount: Flow<Int> = dao.countFlow()

    /**
     * 注入对话用的记忆文本：全部记忆，按 id 升序渲染（顺序稳定 → 缓存前缀一致）。
     * 2026-11-16 修复：原实现只取最旧 50 条，既让新记忆永远进不了模型上下文，
     * 也会在超限后丢掉最早写入的核心事实（称呼/专业等）。
     */
    suspend fun memoryContextText(): String? {
        val list = dao.memoriesForContext()
        if (list.isEmpty()) return null
        return list.joinToString("\n") { it.fact }
    }

    suspend fun addFact(fact: String, category: String = "general"): Long =
        dao.insert(MemoryEntity(fact = fact, category = category))

    suspend fun addFacts(facts: List<MemoryEntity>) = dao.insertAll(facts)

    /** 单条编辑：更新事实内容 */
    suspend fun update(id: Long, fact: String) = dao.updateFact(id, fact)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clearAll() = dao.clearAll()
}
