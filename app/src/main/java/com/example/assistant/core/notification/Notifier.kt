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
    private const val CHANNEL_REMINDER_SILENT = "reminder_silent"
    private const val CHANNEL_BRIEFING = "briefing"

    /** 识屏：截屏前台服务的前台通知（IMPORTANCE_LOW 不打扰） */
    const val CHANNEL_SCREEN_CAPTURE = "screen_capture"

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
        // 免打扰时段内触发的提醒走静默渠道（不响铃不振动，醒来可见）
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER_SILENT, "提醒（静默）", NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BRIEFING, "清晨简报", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCREEN_CAPTURE, "识屏", NotificationManager.IMPORTANCE_LOW
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

    /**
     * 提醒通知：点击 → 打开 App 弹确认窗（用户确认后才停止 5 分钟重复通知）。
     * reminderId 用于通知点击跳转（EXTRA_REMINDER_ID）。
     */
    fun notifyReminder(context: Context, title: String, body: String, id: Int = 2000, reminderId: Long = id.toLong()) {
        send(context, CHANNEL_REMINDER, id, title, body, reminderId = reminderId)
    }

    /** 免打扰时段内的提醒：静默通知（不响铃不振动），同样可点击确认 */
    fun notifyReminderSilent(context: Context, title: String, body: String, id: Int = 2000, reminderId: Long = id.toLong()) {
        send(context, CHANNEL_REMINDER_SILENT, id, title, body, reminderId = reminderId)
    }

    /** 清晨简报通知：点击打开 App 并弹窗显示简报全文 */
    fun notifyBriefing(context: Context, briefing: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_BRIEFING_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ACTION, ACTION_SHOW_BRIEFING)
                .putExtra(EXTRA_BRIEFING_TEXT, briefing),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_BRIEFING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🌅 清晨简报")
            .setContentText(briefing.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(briefing))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_BRIEFING, notification)
        } catch (e: SecurityException) {
            // 未授予 POST_NOTIFICATIONS 权限：通知静默丢弃
        }
    }

    /**
     * 普通通知（无点击跳转）。
     * reminderId 非空时带点击跳转（提醒确认流程用）。
     */
    private fun send(
        context: Context,
        channelId: String,
        id: Int,
        title: String,
        body: String,
        reminderId: Long? = null
    ) {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        if (reminderId != null) {
            // 点击通知 → 打开 App 弹「提醒确认」窗（确认后才停止 5 分钟重复通知）
            val contentIntent = PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_ACTION, ACTION_CONFIRM_REMINDER)
                    .putExtra(EXTRA_REMINDER_ID, reminderId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(contentIntent)
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // 未授予 POST_NOTIFICATIONS 权限：通知静默丢弃
        }
    }

    /** Intent extra：通知点击动作 */
    const val EXTRA_ACTION = "assistant_action"
    const val EXTRA_BRIEFING_TEXT = "briefing_text"
    const val ACTION_SHOW_SUMMARY = "show_summary"
    const val ACTION_SHOW_BRIEFING = "show_briefing"
    /** 识屏小窗「在 App 中继续」→ 回聊天页带截图与结果 */
    const val ACTION_SHOW_SCREEN_SENSE = "show_screen_sense"
    /** 提醒通知点击 → 打开 App 弹确认窗 */
    const val ACTION_CONFIRM_REMINDER = "confirm_reminder"
    const val EXTRA_REMINDER_ID = "reminder_id"

    /** 识屏相关的引导提示（悬浮窗权限缺失等） */
    fun notifyScreenSenseHint(context: Context, text: String) {
        send(context, CHANNEL_SCREEN_CAPTURE, 3002, "识屏", text)
    }

    /**
     * 「识屏准备中」提示：授权后 App 退后台、延迟截屏前提醒用户关闭通知栏
     * （荣耀无法编程收起通知栏，截图会带上它）。截屏完成后由服务取消。
     */
    fun notifyScreenSensePreparing(context: Context) {
        send(context, CHANNEL_SCREEN_CAPTURE, NOTIFY_ID_SCREEN_PREPARING, "识屏", "已就绪，请先关闭通知栏，即将截屏…")
    }

    fun cancelScreenSensePreparing(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFY_ID_SCREEN_PREPARING)
        } catch (_: SecurityException) {
        }
    }

    private const val NOTIFY_ID_SCREEN_PREPARING = 3003

    private const val REQUEST_SUMMARY_OPEN = 100
    private const val REQUEST_BRIEFING_OPEN = 101
    private const val NOTIFY_ID_DIARY_SUMMARY = 1001
    private const val NOTIFY_ID_BRIEFING = 1002
}
