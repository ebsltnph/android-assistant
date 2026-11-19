package com.example.assistant.core.agent.tools

import com.example.assistant.data.db.dao.MemoryDao
import com.example.assistant.data.db.entity.MemoryEntity
import com.example.assistant.data.repo.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * write_memory 的入库语义（2026-11-16 修复）：
 * - 模型主动调用但漏传 importance 时，不能再按 5 静默丢弃（表现为"成功却查不到"）；
 * - 显式低 importance 仍按阈值过滤，且工具行与反馈都要说明没写。
 */
class WriteMemoryToolTest {

    private class FakeMemoryDao : MemoryDao {
        val inserted = mutableListOf<MemoryEntity>()

        override fun memoriesFlow(): Flow<List<MemoryEntity>> = flowOf(inserted.toList())
        override suspend fun memoriesForContext(): List<MemoryEntity> = inserted.toList()
        override fun countFlow(): Flow<Int> = flowOf(inserted.size)
        override suspend fun allMemoriesFull(): List<MemoryEntity> = inserted.toList()

        override suspend fun insert(memory: MemoryEntity): Long {
            val id = (inserted.maxOfOrNull { it.id } ?: 0L) + 1
            inserted += memory.copy(id = id)
            return id
        }

        override suspend fun insertAll(memories: List<MemoryEntity>) {
            memories.forEach { inserted += it.copy(id = (inserted.maxOfOrNull { m -> m.id } ?: 0L) + 1) }
        }

        override suspend fun updateFact(id: Long, fact: String) {}
        override suspend fun delete(id: Long) {}
        override suspend fun clearAll() {}
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun tool(dao: FakeMemoryDao) = WriteMemoryTool(MemoryRepository(dao))

    @Test
    fun `missing importance still saves`() = runBlocking {
        val dao = FakeMemoryDao()
        val outcome = tool(dao).execute(args("""{"fact":"用户常用 Kotlin"}"""))
        assertTrue(outcome is ToolOutcome.Success)
        assertEquals(1, dao.inserted.size)
        assertEquals("用户常用 Kotlin", dao.inserted.first().fact)
    }

    @Test
    fun `explicit low importance is filtered`() = runBlocking {
        val dao = FakeMemoryDao()
        val outcome = tool(dao).execute(args("""{"fact":"今天吃了面条","importance":3}"""))
        assertTrue(outcome is ToolOutcome.Success)
        assertTrue((outcome as ToolOutcome.Success).feedback.contains("未写入长期记忆"))
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun `low importance label warns before execution`() {
        val label = tool(FakeMemoryDao())
            .actionLabel(args("""{"fact":"今天吃了面条","importance":3}"""))
        assertTrue(label.contains("不会保存"))
    }
}
