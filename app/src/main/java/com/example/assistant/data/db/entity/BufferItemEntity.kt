package com.example.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 「进行中的事」缓冲区条目（2026-09-17 新功能）。
 *
 * 定位：介于「长期记忆」（永久、精炼、被动）和「日记」（流水、用户可见）之间的第三层——
 * **一段时间内成立、会过期的状态**：在办事项的进度、需要盯一阵子的事（如身体不适）。
 * 主模型在「上下文整理」（窗口回落）时把它从即将丢弃的对话里蒸馏出来；
 * 用户也可以在前端手动增删改。
 *
 * 注入：进 PromptBuilder 的 messages[3]（在长期记忆之后、对话之前），
 * 由**冻结快照**（PrefixSnapshotStore）提供文本——只在合并点重渲染，
 * 保证两次合并之间提示词前缀逐字节不变（缓存全命中）。
 *
 * 生命周期：完成 → archived（移出注入，**不删除数据**，可取消归档）；确实没用才 delete。
 * 不做自动 TTL：用户手动管理（2026-09-17 用户拍板）。
 */
@Entity(tableName = "buffer_items")
@Serializable
data class BufferItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 类型：progress = 在办事项；watch = 需要盯一段时间的状态（见 [KIND_PROGRESS]/[KIND_WATCH]） */
    val kind: String = KIND_PROGRESS,
    /** 事项名（通用示例：「准备考试」「感冒」） */
    val title: String = "",
    /** 浓缩正文（已完成 / 待办 / 关键数据；专有名词与数值原样保留） */
    val body: String = "",
    /** 相关日记编号（逗号分隔，可空）——需要细节时模型用 read_diary(id=…) 取全文 */
    val diaryIds: String = "",
    /** active = 参与注入；archived = 已归档（移出注入，数据保留） */
    val status: String = STATUS_ACTIVE,
    /** 写入来源：auto = 压缩轮整理；user = 用户手动 */
    val source: String = SOURCE_AUTO,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** 最后更新时间——注入块里只渲染它的**绝对日期**（相对天数会让前缀天天变） */
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    fun isActive(): Boolean = status == STATUS_ACTIVE

    /** 相关日记编号列表（容错逗号/顿号/空格分隔） */
    fun diaryIdList(): List<Long> = diaryIds.split(",", "，", "、")
        .mapNotNull { it.trim().toLongOrNull() }

    companion object {
        const val KIND_PROGRESS = "progress"
        const val KIND_WATCH = "watch"
        const val STATUS_ACTIVE = "active"
        const val STATUS_ARCHIVED = "archived"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_USER = "user"

        /** 相关日记编号列表 → 存储字符串 */
        fun packDiaryIds(ids: List<Long>): String =
            ids.filter { it > 0 }.distinct().joinToString(",")

        fun isValidKind(kind: String?): Boolean = kind == KIND_PROGRESS || kind == KIND_WATCH
    }
}
