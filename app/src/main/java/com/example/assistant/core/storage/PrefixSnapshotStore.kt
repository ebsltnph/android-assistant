package com.example.assistant.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 注入前缀的**冻结快照**（2026-09-17「进行中的事」）。
 *
 * 为什么需要它：长期记忆块与状态块的文本原先是每轮从数据库现读的——
 * 用户在记忆页/「进行中的事」页一改，注入文本立刻变，提示词前缀当场断掉，
 * 厂商缓存整段失效（用户的主力模型未命中价是命中价的 50 倍，这笔钱很实在）。
 *
 * 现在的语义：
 *  - 数据库仍是**页面**的真相（改完立刻可见）；
 *  - 冻结快照是**注入**用的那份文本，只在"合并点"（窗口回落流程 / 冷启动有待合并通知）
 *    重渲染一次；
 *  - 合并点之间，用户改动以「粘性通知」追加到会话尾部（纯延长 = 零未命中），
 *    模型照样能立刻看到。
 *
 * 与会话快照一样是 filesDir 下的轻量 JSON（不进备份：它可以从数据库完全重建）。
 */
@Serializable
data class PrefixSnapshot(
    /** 长期记忆块文本（空 = 不插入该块） */
    val memoryText: String = "",
    /** 「进行中的事」条目文本（空 = 不插入该块；块标题与固定说明在 PromptBuilder） */
    val bufferText: String = "",
    val savedAt: Long = 0L
) {
    val isEmpty: Boolean get() = memoryText.isBlank() && bufferText.isBlank()
}

/**
 * 冻结快照存储（filesDir/prefix_snapshot.json）。
 * 写入走"临时文件 + 改名"，避免写到一半被杀进程留下半个 JSON。
 */
class PrefixSnapshotStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file: File get() = File(context.filesDir, "prefix_snapshot.json")

    /** 读取快照；没有 / 损坏返回 null（调用方据此从数据库重建一次） */
    suspend fun load(): PrefixSnapshot? = withContext(Dispatchers.IO) {
        try {
            val f = file
            if (!f.exists() || f.length() == 0L) return@withContext null
            json.decodeFromString<PrefixSnapshot>(f.readText())
        } catch (_: Exception) {
            try { file.delete() } catch (_: Exception) {}
            null
        }
    }

    suspend fun save(snapshot: PrefixSnapshot) = withContext(Dispatchers.IO) {
        try {
            val tmp = File(context.filesDir, "prefix_snapshot.json.tmp")
            tmp.writeText(json.encodeToString(PrefixSnapshot.serializer(), snapshot))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Exception) {
            // 持久化失败不致命：下次请求会再从数据库重建
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            file.delete()
            File(context.filesDir, "prefix_snapshot.json.tmp").delete()
        } catch (_: Exception) {
        }
    }
}
