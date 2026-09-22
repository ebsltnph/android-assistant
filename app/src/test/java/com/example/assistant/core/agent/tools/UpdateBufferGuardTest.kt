package com.example.assistant.core.agent.tools

import com.example.assistant.data.db.dao.BufferItemDao
import com.example.assistant.data.db.entity.BufferItemEntity
import com.example.assistant.data.repo.BufferRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * update_buffer 的双重闸门（2026-09-17）：
 *  ① description 里写死"绝不能主动调用"（给模型看）；
 *  ② **应用侧硬拦截**：不是压缩轮就回 Failure——模型手贱也写不进去，只浪费一轮，不损坏数据。
 *
 * 另外验证条目改写/新增/归档/删除的行为，以及显式 noop（"这批没什么可记的"）。
 */
class UpdateBufferGuardTest {

    /** 内存版 DAO：Room 的 DAO 是接口，单测里直接实现即可 */
    private class FakeDao : BufferItemDao {
        private val rows = LinkedHashMap<Long, BufferItemEntity>()
        private var seq = 1L

        override fun itemsFlow(): Flow<List<BufferItemEntity>> = flowOf(rows.values.sortedBy { it.id })
        override suspend fun all(): List<BufferItemEntity> = rows.values.sortedBy { it.id }
        override suspend fun active(): List<BufferItemEntity> =
            rows.values.filter { it.isActive() }.sortedBy { it.id }

        override suspend fun byId(id: Long): BufferItemEntity? = rows[id]

        override suspend fun insert(item: BufferItemEntity): Long {
            val id = if (item.id > 0L) item.id else seq++
            rows[id] = item.copy(id = id)
            return id
        }

        override suspend fun insertAll(items: List<BufferItemEntity>) {
            items.forEach { rows[it.id] = it }
        }

        override suspend fun update(item: BufferItemEntity) {
            rows[item.id] = item
        }

        override suspend fun setStatus(id: Long, status: String, now: Long) {
            rows[id]?.let { rows[id] = it.copy(status = status, updatedAtEpochMillis = now) }
        }

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }

        override suspend fun clearAll() {
            rows.clear()
        }
    }

    private val dao = FakeDao()
    private val repo = BufferRepository(dao) { true }
    private val tool = UpdateBufferTool(repo)

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @After
    fun tearDown() {
        UpdateBufferTool.compactionRoundActive = false
    }

    @Test
    fun `normal rounds cannot write the buffer`() = runBlocking {
        UpdateBufferTool.compactionRoundActive = false
        val outcome = tool.execute(
            args("""{"items":[{"title":"偷偷写一条","body":"不该发生"}]}""")
        )
        assertTrue(outcome is ToolOutcome.Failure)
        assertTrue((outcome as ToolOutcome.Failure).error.contains("上下文整理"))
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `compaction round can add and rewrite items`() = runBlocking {
        UpdateBufferTool.compactionRoundActive = true
        // 新增
        val add = tool.execute(
            args(
                """{"items":[{"title":"零差探测器搭建","kind":"progress",
                   "body":"已完成：光路对准；待办：锁腔","diary_ids":[252,260]}]}"""
            )
        )
        assertTrue(add is ToolOutcome.Success)
        val created = repo.all().single()
        assertEquals("零差探测器搭建", created.title)
        assertEquals(listOf(252L, 260L), created.diaryIdList())
        assertTrue(created.source == BufferItemEntity.SOURCE_AUTO)

        // 改写同一条（带 id）：标题/正文更新，创建时间保留
        val edit = tool.execute(
            args("""{"items":[{"id":${created.id},"title":"零差探测器搭建","body":"已完成：锁腔"}]}""")
        )
        assertTrue(edit is ToolOutcome.Success)
        val after = repo.all().single()
        assertEquals("已完成：锁腔", after.body)
        assertEquals(created.createdAtEpochMillis, after.createdAtEpochMillis)
        assertEquals(created.id, after.id)
    }

    @Test
    fun `kind is validated and diary ids tolerate loose typing`() = runBlocking {
        UpdateBufferTool.compactionRoundActive = true
        tool.execute(
            args("""{"title":"感冒","kind":"乱写的","diary_ids":"12, 13"}""")
        )
        val item = repo.all().single()
        // 非法 kind 回落到 progress；字符串形式的 diary_ids 也能读
        assertEquals(BufferItemEntity.KIND_PROGRESS, item.kind)
        assertEquals(listOf(12L, 13L), item.diaryIdList())
    }

    @Test
    fun `archive and delete ids are applied`() = runBlocking {
        UpdateBufferTool.compactionRoundActive = true
        tool.execute(args("""{"title":"A"}"""))
        tool.execute(args("""{"title":"B"}"""))
        val ids = repo.all().map { it.id }

        tool.execute(args("""{"archive_ids":[${ids[0]}]}"""))
        assertTrue(repo.byId(ids[0])!!.status == BufferItemEntity.STATUS_ARCHIVED)
        // 归档不进注入，但数据还在（可取消归档）
        assertEquals(1, repo.active().size)

        tool.execute(args("""{"delete_ids":[${ids[1]}]}"""))
        assertTrue(repo.byId(ids[1]) == null)
    }

    @Test
    fun `explicit noop succeeds without writing anything`() = runBlocking {
        UpdateBufferTool.compactionRoundActive = true
        val outcome = tool.execute(args("""{"noop":true}"""))
        assertTrue(outcome is ToolOutcome.Success)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `empty args are refused so the model gets a chance to correct itself`() = runBlocking {
        UpdateBufferTool.compactionRoundActive = true
        val outcome = tool.execute(args("""{}"""))
        assertTrue(outcome is ToolOutcome.Failure)
    }

    @Test
    fun `snapshot text skips archived items and dangling diary ids`() = runBlocking {
        val repoWithMissingDiary = BufferRepository(dao) { id -> id == 252L }
        val tool2 = UpdateBufferTool(repoWithMissingDiary)
        UpdateBufferTool.compactionRoundActive = true
        tool2.execute(
            args("""{"items":[{"title":"有引用","body":"x","diary_ids":[252,999]}]}""")
        )
        val text = repoWithMissingDiary.renderSnapshotText()
        assertTrue(text.contains("#id=252"))
        assertFalse(text.contains("#id=999"))
    }
}
