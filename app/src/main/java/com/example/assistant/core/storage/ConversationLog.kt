package com.example.assistant.core.storage

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 秘密功能：对话历史记录（数字分身素材）。
 * 只保存**用户发出的内容**（不含模型回复），纯文本追加写入 filesDir/secret_log/chat_history.txt。
 * - 开关存 SettingsStore.secretLogEnabled（默认开；写入前检查，关闭即停）
 * - 每条记录一行："[yyyy-MM-dd HH:mm] 内容"（内容可能含换行，用缩进表示续行）
 * - 容量上限 [MAX_BYTES]（10MB）：超限时丢弃最旧的一半（防无限增长）
 * - 导出由设置页分享文件（FileProvider）
 */
class ConversationLog(
    private val context: Context,
    private val settingsStore: SettingsStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 记录开关的内存镜像（启动时收集，写入前检查——DataStore 是异步的） */
    @Volatile
    private var enabled: Boolean = false

    init {
        scope.launch {
            settingsStore.secretLogEnabled.collect { enabled = it }
        }
    }

    /** 记录一条用户消息（开关关闭或空白内容时忽略） */
    fun log(text: String) {
        if (!enabled || text.isBlank()) return
        val line = formatTimestamp() + " " + text.replace("\n", "\n    ")
        scope.launch { append(line) }
    }

    private suspend fun append(line: String) {
        try {
            val file = file()
            synchronized(file) {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                // 容量上限：超限保留最后一半（重写文件）
                if (file.length() > MAX_BYTES) trimToHalf(file)
            }
        } catch (_: Exception) {
            // 写入失败静默忽略（记录功能不打扰用户）
        }
    }

    /** 超限时只保留文件后半段（丢最旧的） */
    private fun trimToHalf(file: File) {
        val lines = file.readLines()
        if (lines.size <= 2) return
        val keep = lines.takeLast(lines.size / 2 + 1)
        file.writeText(keep.joinToString("\n") + "\n")
    }

    /** 记录文件（不存在时返回空文件对象） */
    fun file(): File = File(context.filesDir, "secret_log/chat_history.txt")

    /** 统计：条目数（按非空行计，兼容带/不带方括号的旧数据）+ 文件大小（字节）；IO 线程调用 */
    fun stats(): Pair<Int, Long> {
        val f = file()
        if (!f.exists()) return 0 to 0L
        val count = f.readLines().count { it.isNotBlank() }
        return count to f.length()
    }

    /** 清空记录文件 */
    fun clear() {
        scope.launch {
            try {
                file().delete()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** 记录文件大小上限（10MB，超出丢最旧一半） */
        const val MAX_BYTES = 10 * 1024 * 1024L
    }
}

/** 记录时间戳（本地时区）：[yyyy-MM-dd HH:mm]（方括号供导出/统计识别）。每次新建实例（SimpleDateFormat 非线程安全） */
private fun formatTimestamp(): String =
    "[" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date()) + "]"
