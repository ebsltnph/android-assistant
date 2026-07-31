package com.example.assistant.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.assistant.MainActivity
import com.example.assistant.R

/**
 * 通知中心：统一管理渠道与发送。
 * 渠道：每日总结 / 提醒（P4 使用）——确保渠道在应用启动时创建一次。
 * 注意：Android 13+ 需运行时申请 POST_NOTIFICATIONS 权限，未授予时通知静默丢弃（不崩溃）。
 */
object Notifier {

    private const val CHANNEL_DIARY_SUMMARY = "diary_summary"
    private const val CHANNEL_REMINDER = "reminder"

    /** 创建通知渠道（幂等，可在 Application.onCreate 调用） */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DIARY_SUMMARY, "每日总结", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER, "提醒", NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    /** 每日总结通知：点击打开 App 并弹出完整小结 */
    fun notifyDiarySummary(context: Context, summary: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_SUMMARY_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ACTION, ACTION_SHOW_SUMMARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DIARY_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("今日小结")
            .setContentText(summary.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_DIARY_SUMMARY, notification)
        } catch (e: SecurityException) {
            // 未授予 POST_NOTIFICATIONS 权限：通知静默丢弃
        }
    }

    /** 提醒通知（P4 使用） */
    fun notifyReminder(context: Context, title: String, body: String, id: Int = 2000) {
        send(context, CHANNEL_REMINDER, id, title, body)
    }

    private fun send(context: Context, channelId: String, id: Int, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // 未授予 POST_NOTIFICATIONS 权限：通知静默丢弃
        }
    }

    /** Intent extra：通知点击动作 */
    const val EXTRA_ACTION = "assistant_action"
    const val ACTION_SHOW_SUMMARY = "show_summary"

    private const val REQUEST_SUMMARY_OPEN = 100
    private const val NOTIFY_ID_DIARY_SUMMARY = 1001
}
