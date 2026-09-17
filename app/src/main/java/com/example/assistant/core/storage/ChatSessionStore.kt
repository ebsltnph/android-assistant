package com.example.assistant.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话快照的持久化格式。
 *
 * 设计取舍（2026-09-11 用户确认）：会话是"随时可丢"的内容——
 * **只做轻量持久化**（一个 JSON 文件），不进备份、不落 Room；
 * 并按保留天数定时清理（默认 7 天，0 = 不留存）。
 *
 * 图片只存本机路径（不存 base64）：文件本身在 filesDir/chat_images，
 * 组装请求时再读成 base64（见 Session.imagePartOf），避免 JSON 膨胀。
 */
@Serializable
data class StoredChat(
    val savedAt: Long = 0L,
    val turns: List<StoredTurn> = emptyList(),
    /** 界面消息（含思考块/工具行分段），用于恢复聊天页显示 */
    val messages: List<StoredUiMessage> = emptyList()
)

@Serializable
data class StoredTurn(
    val id: Long,
    /** 用户消息文本（已含创建时刻的时间戳前缀） */
    val userText: String? = null,
    /** 附带图片的本机路径（可为空） */
    val imagePath: String? = null,
    /** 该轮的助手侧消息（工具调用原文 / [结果] 回传 / 最终回答），按顺序 */
    val assistant: List<String> = emptyList(),
    /** 该轮创建时刻（按保留天数清理用；旧快照没有此字段时回退到 userText 时间戳/保存时刻） */
    val createdAt: Long = 0L
)

@Serializable
data class StoredUiMessage(
    val id: Long,
    val turnId: Long,
    val role: String,
    val text: String,
    val thinking: String = "",
    val regenerable: Boolean = false,
    val segments: List<StoredSegment> = emptyList(),
    /** 该条界面消息的创建时刻（清理判定用；0 = 未知，一律不删） */
    val createdAt: Long = 0L
)

@Serializable
data class StoredSegment(
    /** "think" | "text" | "tools" */
    val kind: String,
    val text: String = "",
    val labels: List<String> = emptyList()
)

/**
 * 会话快照存储（filesDir/chat_session.json）。
 * 写入走"临时文件 + 改名"，避免写到一半被杀进程留下半个 JSON。
 */
class ChatSessionStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file: File get() = File(context.filesDir, "chat_session.json")

    /** 读取快照；没有/损坏（含旧版本格式不兼容）返回 null 并顺手删掉坏文件 */
    suspend fun load(): StoredChat? = withContext(Dispatchers.IO) {
        try {
            val f = file
            if (!f.exists() || f.length() == 0L) return@withContext null
            json.decodeFromString<StoredChat>(f.readText())
        } catch (_: Exception) {
            try { file.delete() } catch (_: Exception) {}
            null
        }
    }

    suspend fun save(snapshot: StoredChat) = withContext(Dispatchers.IO) {
        try {
            val tmp = File(context.filesDir, "chat_session.json.tmp")
            tmp.writeText(json.encodeToString(StoredChat.serializer(), snapshot))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Exception) {
            // 持久化失败不影响使用（会话本就随时可丢）
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            file.delete()
            File(context.filesDir, "chat_session.json.tmp").delete()
        } catch (_: Exception) {
        }
    }

    /**
     * 定时清理：快照超过保留天数就整体丢弃。
     * 在启动恢复与每次保存前各检查一次（无需额外的周期任务）。
     * @param retentionDays 0 = 不留存（直接清掉）
     * @return true = 快照已被清掉
     */
    suspend fun pruneIfExpired(retentionDays: Int): Boolean {
        if (retentionDays <= 0) {
            clear()
            return true
        }
        val snapshot = load() ?: return false
        val age = System.currentTimeMillis() - snapshot.savedAt
        if (age > retentionDays * 24L * 3600_000L) {
            clear()
            return true
        }
        return false
    }

    companion object {
        /** 单次最多保留的界面消息条数（防文件无限增长） */
        const val MAX_UI_MESSAGES = 120

        /** 单次最多保留的会话轮数 */
        const val MAX_TURNS = 60

        private val STAMP = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2})]""")

        /**
         * 从用户消息的 `[yyyy-MM-dd HH:mm]` 前缀解析创建时刻。
         * 旧快照没有 createdAt 字段时用它回退；解析不出来返回 0 = 未知（清理逻辑一律保留，宁可不删）。
         */
        fun parseStampedAt(userText: String?): Long {
            val m = STAMP.find(userText ?: return 0L) ?: return 0L
            return try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .apply { isLenient = false }
                    .parse(m.groupValues[1])?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
