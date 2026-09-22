package com.example.assistant.core.agent

import com.example.assistant.data.db.entity.BufferItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「进行中的事」注入文本渲染（2026-09-17）。
 *
 * ⚠️ 渲染器必须是**纯函数**：同样的条目必须渲染出逐字节相同的结果。
 * 掺进相对天数（"3 天前"）、当前时间或动态排序，提示词前缀就会每天/每轮变，
 * 厂商缓存永远命中不了（本项目的老坑）。这个测试就是守这条线的。
 */
class BufferRendererTest {

    private fun item(
        id: Long,
        title: String = "事项$id",
        body: String = "已完成：A；待办：B",
        kind: String = BufferItemEntity.KIND_PROGRESS,
        status: String = BufferItemEntity.STATUS_ACTIVE,
        diaryIds: String = "",
        updatedAt: Long = 1_700_000_000_000L
    ) = BufferItemEntity(
        id = id, kind = kind, title = title, body = body, diaryIds = diaryIds,
        status = status, source = BufferItemEntity.SOURCE_AUTO,
        createdAtEpochMillis = updatedAt, updatedAtEpochMillis = updatedAt
    )

    @Test
    fun `renders by id ascending regardless of input order`() {
        val text = BufferRenderer.render(listOf(item(3), item(1), item(2)))
        val idx1 = text.indexOf("#id=1")
        val idx2 = text.indexOf("#id=2")
        val idx3 = text.indexOf("#id=3")
        assertTrue(idx1 in 0 until idx2)
        assertTrue(idx2 < idx3)
    }

    @Test
    fun `same input renders byte identical output`() {
        val items = listOf(item(1), item(2))
        assertEquals(BufferRenderer.render(items), BufferRenderer.render(items))
    }

    @Test
    fun `uses absolute date and never a relative one`() {
        val text = BufferRenderer.render(listOf(item(1, updatedAt = 1_700_000_000_000L)))
        assertTrue(text.contains("更新于 " + BufferRenderer.formatDay(1_700_000_000_000L)))
        assertFalse(text.contains("天前"))
        assertFalse(text.contains("今天"))
        assertFalse(text.contains("昨天"))
    }

    @Test
    fun `archived items are not rendered`() {
        val text = BufferRenderer.render(
            listOf(item(1, title = "在办的"), item(2, title = "归档的", status = BufferItemEntity.STATUS_ARCHIVED))
        )
        assertTrue(text.contains("在办的"))
        assertFalse(text.contains("归档的"))
    }

    @Test
    fun `empty or blank items produce an empty block`() {
        assertEquals("", BufferRenderer.render(emptyList()))
        assertEquals("", BufferRenderer.render(listOf(item(1, title = " ", body = ""))))
        // 空文本 ⇒ PromptBuilder 不插入状态块（不给前缀白加内容）
        assertEquals("", BufferRenderer.render(listOf(item(1, status = BufferItemEntity.STATUS_ARCHIVED))))
    }

    @Test
    fun `item count limit keeps the most recently updated and notes the omission`() {
        val items = listOf(
            item(1, title = "老的", updatedAt = 1_600_000_000_000L),
            item(2, title = "新的", updatedAt = 1_800_000_000_000L),
            item(3, title = "最新的", updatedAt = 1_900_000_000_000L)
        )
        val text = BufferRenderer.render(items, charLimit = 0, maxItems = 2)
        assertFalse(text.contains("老的"))
        assertTrue(text.contains("新的"))
        assertTrue(text.contains("最新的"))
        // 省略是"只是不注入"，数据一条不删——块尾要写明还有几条
        assertTrue(text.contains("另有 1 条更早的状态已省略"))
        // 顺序仍按 id 升序（不按更新时间，避免一更新就换位置）
        assertTrue(text.indexOf("#id=2") < text.indexOf("#id=3"))
    }

    @Test
    fun `char limit omits the overflowing items but keeps at least one`() {
        val long = "数".repeat(400)
        val items = listOf(item(1, body = long), item(2, body = long), item(3, body = long))
        val text = BufferRenderer.render(items, charLimit = 500, maxItems = 15)
        // 第一条无论多长都放（否则会渲染成空块）
        assertTrue(text.contains("#id=1"))
        assertTrue(text.contains("另有 2 条更早的状态已省略"))
        assertTrue(text.length < 400 + 200)
    }

    @Test
    fun `watch kind and diary references are rendered`() {
        val text = BufferRenderer.render(
            listOf(
                item(
                    7, title = "感冒", kind = BufferItemEntity.KIND_WATCH,
                    body = "9月14日开始，头两天较严重，现在已好转", diaryIds = "252,260"
                )
            )
        )
        assertTrue(text.contains("关注"))
        assertTrue(text.contains("感冒"))
        assertTrue(text.contains("相关日记：#id=252、#id=260"))
    }

    @Test
    fun `relative day helper is for the ui only`() {
        val now = 1_700_000_000_000L
        assertEquals("今天", BufferRenderer.relativeDay(now, now))
        assertEquals("昨天", BufferRenderer.relativeDay(now - 86_400_000L, now))
        assertEquals("3 天前", BufferRenderer.relativeDay(now - 3 * 86_400_000L, now))
        assertEquals("未知", BufferRenderer.relativeDay(0L, now))
    }
}
