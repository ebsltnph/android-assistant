package com.example.assistant.core.storage

import android.content.Context
import androidx.core.content.edit
import com.example.assistant.core.network.WebProvider

/**
 * 最近一次联网搜索/抓取的运行状态（轻量 SharedPreferences，不进备份）。
 *
 * 事件监控失败不再静默变成“没有新闻”：这里记录提供商、时间、成功/空结果/失败和原因，
 * 设置页/事件详情可据此区分限流、鉴权、断网和真的没有命中。
 */
data class WebAccessStatus(
    val timestamp: Long = 0L,
    val provider: WebProvider = WebProvider.TAVILY,
    val operation: String = "搜索",
    val success: Boolean = false,
    val resultCount: Int? = null,
    val message: String = ""
) {
    fun display(): String {
        if (timestamp <= 0L) return "尚无事件搜索记录"
        val whenText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        val state = when {
            !success -> "失败"
            resultCount == 0 -> "成功（空结果）"
            resultCount != null -> "成功 $resultCount 条"
            else -> "成功"
        }
        return "$whenText · ${provider.displayName} $operation · $state" +
            if (message.isBlank()) "" else "：$message"
    }
}

class WebAccessStatusStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("web_access_status", Context.MODE_PRIVATE)

    fun record(status: WebAccessStatus) {
        prefs.edit {
            putLong("$KEY_PREFIX${status.operation}_time", status.timestamp)
            putString("$KEY_PREFIX${status.operation}_provider", status.provider.storageValue)
            putBoolean("$KEY_PREFIX${status.operation}_success", status.success)
            putInt("$KEY_PREFIX${status.operation}_count", status.resultCount ?: -1)
            putString("$KEY_PREFIX${status.operation}_message", status.message.take(500))
        }
    }

    fun latest(operation: String): WebAccessStatus {
        val time = prefs.getLong("$KEY_PREFIX${operation}_time", 0L)
        if (time <= 0L) return WebAccessStatus(operation = operation)
        return WebAccessStatus(
            timestamp = time,
            provider = WebProvider.fromStorage(
                prefs.getString("$KEY_PREFIX${operation}_provider", null)
            ),
            operation = operation,
            success = prefs.getBoolean("$KEY_PREFIX${operation}_success", false),
            resultCount = prefs.getInt("$KEY_PREFIX${operation}_count", -1)
                .takeIf { it >= 0 },
            message = prefs.getString("$KEY_PREFIX${operation}_message", "").orEmpty()
        )
    }

    companion object {
        private const val KEY_PREFIX = "last_"
        const val OP_EVENT_SEARCH = "事件搜索"
        const val OP_CHAT_SEARCH = "对话搜索"
        const val OP_WEB_FETCH = "网页抓取"
    }
}
