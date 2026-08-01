package com.example.assistant.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.assistant.AssistantApplication
import com.example.assistant.MainActivity
import com.example.assistant.R
import com.example.assistant.core.notification.Notifier
import com.example.assistant.core.vision.ImageUtils
import java.io.File
import java.io.FileOutputStream

/**
 * 屏幕截屏前台服务（foregroundServiceType=mediaProjection，API 34+ 强制 FGS）。
 *
 * 流程：MainActivity 授权成功 → [start] 拉起本服务 → 截一帧 → PNG 存 cacheDir →
 * 弹出识屏小窗（AppContainer.screenResultOverlay）→ 本服务作为小窗存活的 FGS 宿主
 * （防 MagicOS 后台杀进程）→ 小窗关闭回调 [onClosed] 时 stopSelf。
 *
 * 省电：截图完成后立即释放 MediaProjection/VirtualDisplay（小窗只展示本地 PNG 文件）。
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handlerThread = HandlerThread("ScreenCapture")

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // API 34+ 必须显式声明 mediaProjection 类型（否则 MissingForegroundServiceTypeException）
        val notification = buildNotification("正在截屏…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // 荣耀会不定时撤销 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限
            // （授权后/服务停止后），兜底：不崩溃，通知用户重新识屏
            Notifier.notifyScreenSenseHint(this, "屏幕录制权限未授予，请再次点击识屏并允许权限")
            stopSelf()
            return START_NOT_STICKY
        }
        // 延迟 2.5 秒再截屏：MainActivity 授权成功后已 moveTaskToBack，
        // 等界面切换动画完成 + 给用户留出"关闭通知栏"的时间（荣耀无法编程收起通知栏）
        Handler(Looper.getMainLooper()).postDelayed({
            startCapture(resultCode, data)
        }, 2500)
        return START_NOT_STICKY
    }

    /** 用授权令牌创建投影 + 虚拟显示，帧回调里截一帧 */
    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = mpm.getMediaProjection(resultCode, data)
        val handler = Handler(handlerThread.looper)
        // Android 14 要求：createVirtualDisplay 之前必须先注册回调
        // （用于在系统停止投影时释放资源），否则 IllegalStateException
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 系统停止投影（用户关闭通知栏录制指示等）→ 释放资源并结束服务
                releaseCapture()
                stopSelf()
            }
        }, handler)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = imageToBitmap(image)
            image.close()
            onFrameCaptured(bitmap)
        }, handler)
        val display = projection.createVirtualDisplay(
            "ScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        mediaProjection = projection
        virtualDisplay = display
        imageReader = reader
    }

    /** 截到一帧（handlerThread 线程）：存 PNG → 释放投影 → 弹出小窗 */
    private fun onFrameCaptured(bitmap: Bitmap) {
        // 取消「识屏准备中」提示（提醒关通知栏的）
        Notifier.cancelScreenSensePreparing(this)
        val scaled = ImageUtils.scaleBitmap(bitmap)
        val dir = File(cacheDir, "screensense").apply { mkdirs() }
        val file = File(dir, "screen_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }
        // 释放投影资源（省电）：小窗只展示本地文件，不再需要虚拟显示
        releaseCapture()
        updateNotification("识屏小窗已弹出")
        val container = (application as AssistantApplication).container
        container.screenResultOverlay.show(file.absolutePath) {
            stopSelf()
        }
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        releaseCapture()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifier.CHANNEL_SCREEN_CAPTURE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("识屏")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** RGBA_8888 Image → Bitmap（处理 rowPadding） */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 3001

        /** 启动截屏服务（授权成功后调用，必须带 resultCode + data） */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
