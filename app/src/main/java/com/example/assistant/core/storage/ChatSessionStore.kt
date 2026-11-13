package com.example.assistant.core.storage

import android.content.Context
import com.example.assistant.core.network.dto.ChatToolCall
import com.example.assistant.core.network.dto.FunctionCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    /**
     * 会话工具协议版本：
     * - 1（旧）= 正文 `[调用]`/`[结果]`；
     * - 2 = 原生 tool_calls（当前）。
     * 读取旧版本时只从**模型上下文**剥离旧工具中间轮，界面消息不动。
     */
    val protocolVersion: Int = 1,
    val turns: List<StoredTurn> = emptyList(),
    /** 界面消息（含思考块/工具行分段），用于恢复聊天页显示 */
    val messages: List<StoredUiMessage> = emptyList(),
    /**
     * 压缩水位线（2026-09-17「进行中的事」）：**已折进缓冲区的最大轮 id**。
     * 未覆盖的轮（id 大于它）不会被动清理删除，留到下次上下文整理一起补（自愈）。
     */
    val coveredThroughTurnId: Long = 0L,
    /**
     * 离开窗口但**还没折进缓冲区**的轮（2026-09-23 补）。
     *
     * 为什么必须单独存：被窗口裁掉的轮会从 [turns] 里消失（turns 每轮按当前会话重建），
     * 如果这次整理恰好跳过/失败，那批内容下次就再也找不回来了——用户实测踩到过
     * （6 轮被裁剪后静默丢失）。存这里之后，下次整理会把它们当"补整理片段"一起带上。
     */
    val uncoveredTurns: List<StoredTurn> = emptyList()
)

@Serializable
data class StoredTurn(
    val id: Long,
    /** 用户消息文本（已含创建时刻的时间戳前缀） */
    val userText: String? = null,
    /**
     * 旧字段：单张附带图片的本机路径（v1.5 及更早的快照）。
     * 保留只为读旧文件；新写入一律走 [imagePaths]。
     */
    val imagePath: String? = null,
    /** 附带图片的本机路径列表（一轮可多张，2026-10-05 起） */
    val imagePaths: List<String> = emptyList(),
    /** 该轮的助手侧消息（工具调用 / tool 结果 / 最终回答），按顺序 */
    val assistant: List<String> = emptyList(),
    /**
     * 与 [assistant] 一一对应的工具注入图片路径（read_diary_image 看过的日记图；空=该条无图）。
     * 旧快照没有此字段 → 全空；只存路径不存 base64，文件仍在 diary_images 下。
     */
    val assistantImagePaths: List<List<String>> = emptyList(),
    /**
     * 与 [assistant] 一一对应的消息角色（"assistant" | "user" | "tool"）。
     * 旧快照没有此字段 → 由"是否带注入图片"推断（会退化）。
     */
    val assistantRoles: List<String> = emptyList(),
    /**
     * 与 [assistant] 一一对应的原生工具回放元数据（tool_calls / tool_call_id / reasoning_content）。
     * 旧快照没有 → 空；新快照必须与 [assistant] 等长写入。
     */
    val assistantMeta: List<StoredAssistantMeta> = emptyList(),
    /** 该轮创建时刻（按保留天数清理用；旧快照没有此字段时回退到 userText 时间戳/保存时刻） */
    val createdAt: Long = 0L,
    /**
     * 粘性通知（用户手动改长期记忆/「进行中的事」时追加的 [系统] 行）。
     * **必须持久化**：它的位置是缓存前缀的一部分，重启后位置变了就等于把前缀改了。
     */
    val notices: List<String> = emptyList()
) {
    /**
     * 兼容旧快照：优先用新字段 [imagePaths]；旧文件只有单图 [imagePath] 时回退成单元素列表。
     * 图片路径可能指向已清理的文件——读取方（Session.imagePartOf）会自行判断存在性。
     */
    fun effectiveImagePaths(): List<String> =
        if (imagePaths.isNotEmpty()) imagePaths
        else listOfNotNull(imagePath?.takeIf { it.isNotBlank() })
}

@Serializable
data class StoredAssistantMeta(
    val role: String = "assistant",
    /** DeepSeek 思考模式：工具调用轮的 reasoning 必须回传 */
    val reasoningContent: String = "",
    val toolCalls: List<StoredToolCall> = emptyList(),
    val toolCallId: String = ""
)

@Serializable
data class StoredToolCall(
    val id: String = "",
    val name: String = "",
    /** 原始 JSON 字符串；即使非法 JSON 也保留，不静默修复历史 */
    val arguments: String = ""
)

fun ChatToolCall.toStoredToolCall(): StoredToolCall = StoredToolCall(
    id = id,
    name = function?.name.orEmpty(),
    arguments = function?.arguments.orEmpty()
)

fun StoredToolCall.toChatToolCall(): ChatToolCall = ChatToolCall(
    id = id,
    type = "function",
    function = FunctionCall(name = name, arguments = arguments)
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

    /**
     * 写盘串行化（2026-11-14）：定时对话会先异步 persist、再同步 persist，
     * 两次 save 若并发写同一个 tmp 文件，旧实现的"删正式文件→rename"可能出现
     * 后完成者把前者刚写好的文件删掉、rename 又失败 → 整份会话历史丢失。
     * 这里用 Mutex 串行化，并每次使用独立临时文件 + 原子替换。
     */
    private val ioMutex = Mutex()

    private val file: File get() = File(context.filesDir, "chat_session.json")

    /** 当前进程内已成功写入的最大修订号（防旧的异步快照晚到后覆盖新快照） */
    private var newestRevision = 0L

    /** 修订号发号器：在**构造快照时**同步领取，异步写盘晚到也不会乱序 */
    private val revisionSeq = java.util.concurrent.atomic.AtomicLong(0L)

    fun newRevision(): Long = revisionSeq.incrementAndGet()

    /** 读取快照；没有/损坏（含旧版本格式不兼容）返回 null 并顺手删掉坏文件 */
    suspend fun load(): StoredChat? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                val f = file
                if (!f.exists() || f.length() == 0L) return@withLock null
                json.decodeFromString<StoredChat>(f.readText())
            } catch (_: Exception) {
                try { file.delete() } catch (_: Exception) {}
                null
            }
        }
    }

    /**
     * 保存快照。修订号由调用方单调递增提供：异步保存可能晚于同步保存到达，
     * 若它携带的是更旧的快照则直接丢弃，避免把新对话覆盖掉。
     */
    suspend fun save(snapshot: StoredChat, revision: Long = 0L) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (revision > 0L && revision < newestRevision) return@withLock
            var tmp: File? = null
            try {
                tmp = File.createTempFile("chat_session_", ".tmp", context.filesDir)
                tmp.writeText(json.encodeToString(StoredChat.serializer(), snapshot))
                replaceAtomically(tmp, file)
                tmp = null
                if (revision > newestRevision) newestRevision = revision
            } catch (_: Exception) {
                // 持久化失败不影响使用（会话本就随时可丢）
            } finally {
                try { tmp?.delete() } catch (_: Exception) {}
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            // 清空也是一次"更新"：抬高最新修订号，让仍在路上的旧快照保存被丢弃，
            // 否则清空后又被异步旧快照写回，下次启动会恢复出已经清掉的对话。
            newestRevision = maxOf(newestRevision, revisionSeq.incrementAndGet())
            try {
                file.delete()
                File(context.filesDir, "chat_session.json.tmp").delete()
                // 清理进程被杀时残留的独立临时文件
                context.filesDir.listFiles { f ->
                    f.name.startsWith("chat_session_") && f.name.endsWith(".tmp")
                }?.forEach { it.delete() }
            } catch (_: Exception) {
            }
        }
    }

    /** 原子替换；个别文件系统不支持 ATOMIC_MOVE 时退回普通 REPLACE_EXISTING */
    private fun replaceAtomically(src: File, dst: File) {
        try {
            Files.move(
                src.toPath(), dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
