package com.example.assistant.data.repo

import com.example.assistant.core.agent.BufferRenderer
import com.example.assistant.data.db.dao.BufferItemDao
import com.example.assistant.data.db.entity.BufferItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「进行中的事」缓冲区仓储（2026-09-17）。
 *
 * 页面（BufferScreen）读的是**数据库真相**；注入用的是**冻结快照**（PrefixSnapshotStore），
 * 两者只在"合并点"（窗口回落流程）同步一次——这是保证提示词前缀稳定的关键设计。
 */
class BufferRepository(
    private val dao: BufferItemDao,
    /** 日记编号是否存在（校验悬挂引用；合并渲染时过滤掉已删日记） */
    private val diaryExists: suspend (Long) -> Boolean = { false }
) {

    val items: Flow<List<BufferItemEntity>> = dao.itemsFlow()

    suspend fun all(): List<BufferItemEntity> = dao.all()

    suspend fun active(): List<BufferItemEntity> = dao.active()

    suspend fun byId(id: Long): BufferItemEntity? = dao.byId(id)

    /** 手动新增（source = user） */
    suspend fun add(
        title: String,
        body: String,
        kind: String = BufferItemEntity.KIND_PROGRESS,
        diaryIds: List<Long> = emptyList(),
        source: String = BufferItemEntity.SOURCE_USER
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            BufferItemEntity(
                kind = if (BufferItemEntity.isValidKind(kind)) kind else BufferItemEntity.KIND_PROGRESS,
                title = title.trim(),
                body = body.trim(),
                diaryIds = BufferItemEntity.packDiaryIds(diaryIds),
                status = BufferItemEntity.STATUS_ACTIVE,
                source = source,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
        )
    }

    /** 手动编辑（标题/正文/类型/相关日记）——更新时间随之刷新 */
    suspend fun update(
        id: Long,
        title: String,
        body: String,
        kind: String? = null,
        diaryIds: List<Long>? = null
    ): Boolean {
        val item = dao.byId(id) ?: return false
        dao.update(
            item.copy(
                title = title.trim(),
                body = body.trim(),
                kind = if (BufferItemEntity.isValidKind(kind)) kind!! else item.kind,
                diaryIds = if (diaryIds == null) item.diaryIds else BufferItemEntity.packDiaryIds(diaryIds),
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
        return true
    }

    /** 归档 / 取消归档（移出或恢复注入，不删数据） */
    suspend fun setArchived(id: Long, archived: Boolean): Boolean {
        if (dao.byId(id) == null) return false
        dao.setStatus(
            id,
            if (archived) BufferItemEntity.STATUS_ARCHIVED else BufferItemEntity.STATUS_ACTIVE,
            System.currentTimeMillis()
        )
        return true
    }

    suspend fun delete(id: Long): Boolean {
        if (dao.byId(id) == null) return false
        dao.delete(id)
        return true
    }

    /**
     * 压缩轮写入：按 id 改写（保留创建时间与来源）或新增。
     * @return 条目 id 与是否为新建
     */
    suspend fun upsertFromCompaction(
        id: Long?,
        title: String,
        body: String,
        kind: String?,
        diaryIds: List<Long>
    ): Pair<Long, Boolean> {
        val now = System.currentTimeMillis()
        val existing = id?.let { dao.byId(it) }
        if (existing != null) {
            dao.update(
                existing.copy(
                    title = title.trim().ifEmpty { existing.title },
                    body = body.trim().ifEmpty { existing.body },
                    kind = if (BufferItemEntity.isValidKind(kind)) kind!! else existing.kind,
                    diaryIds = BufferItemEntity.packDiaryIds(diaryIds).ifEmpty { existing.diaryIds },
                    updatedAtEpochMillis = now
                )
            )
            return existing.id to false
        }
        val newId = dao.insert(
            BufferItemEntity(
                kind = if (BufferItemEntity.isValidKind(kind)) kind!! else BufferItemEntity.KIND_PROGRESS,
                title = title.trim(),
                body = body.trim(),
                diaryIds = BufferItemEntity.packDiaryIds(diaryIds),
                status = BufferItemEntity.STATUS_ACTIVE,
                source = BufferItemEntity.SOURCE_AUTO,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
        )
        return newId to true
    }

    /** 批量归档（压缩轮的 archive_ids） */
    suspend fun archiveMany(ids: List<Long>): Int {
        var n = 0
        ids.distinct().forEach { if (setArchived(it, true)) n++ }
        return n
    }

    /** 批量删除（压缩轮的 delete_ids） */
    suspend fun deleteMany(ids: List<Long>): Int {
        var n = 0
        ids.distinct().forEach { if (delete(it)) n++ }
        return n
    }

    /**
     * 生成注入用文本（**只在合并点调用**）：
     * 只取 active、过滤悬挂的日记引用、按上限渲染（纯函数见 [BufferRenderer]）。
     */
    suspend fun renderSnapshotText(
        charLimit: Int = BufferRenderer.DEFAULT_CHAR_LIMIT,
        maxItems: Int = BufferRenderer.DEFAULT_MAX_ITEMS
    ): String {
        val active = dao.active()
        if (active.isEmpty()) return ""
        val cleaned = active.map { item ->
            val ids = item.diaryIdList()
            if (ids.isEmpty()) return@map item
            val keep = ids.filter { diaryExists(it) }
            if (keep.size == ids.size) item
            else item.copy(diaryIds = BufferItemEntity.packDiaryIds(keep))
        }
        return BufferRenderer.render(cleaned, charLimit, maxItems)
    }

    // ---- 备份 ----

    /** 备份用：全量（含已归档） */
    suspend fun allFull(): List<BufferItemEntity> = dao.all()

    suspend fun clearAll() = dao.clearAll()

    suspend fun insertAll(items: List<BufferItemEntity>) = dao.insertAll(items)
}
