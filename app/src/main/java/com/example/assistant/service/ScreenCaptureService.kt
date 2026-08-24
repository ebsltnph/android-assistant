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
import com.example.assistant.di.AppContainer
import com.example.assistant.feature.floating.FloatingPanelActivity
import java.io.File
import java.io.FileOutputStream

/**
 * 屏幕截屏前台服务（foregroundServiceType=mediaProjection，API 34+ 强制 FGS）。
 *
 * 流程：授权成功 → [start] 拉起本服务 → 延迟 2.5s 截一帧 → PNG 存 cacheDir →
 * 拉起浮动界面（FloatingPanelActivity 识图模式：缩略图 + 提取文字/翻译/总结）→ stopSelf。
 * 识屏前/截屏期间由 panelState=CAPTURING 隐藏悬浮球（防被截进截图）。
 *
 * 省电：截图完成后立即释放 MediaProjection/VirtualDisplay（浮动界面只展示本地 PNG 文件）。
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
        // 截屏延迟：悬浮球入口通知栏是收起的（1.2s 即可）；
        // 聊天/磁贴入口通知栏可能展开（保持 2.5s，给用户时间收起）
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS)
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
        // 延迟截屏：授权成功后已 moveTaskToBack，等界面切换动画完成；
        // 聊天/磁贴入口还要给用户留出"关闭通知栏"的时间（荣耀无法编程收起通知栏）
        Handler(Looper.getMainLooper()).postDelayed({
            startCapture(resultCode, data)
        }, delayMs)
        return START_NOT_STICKY
    }

    /** 用授权令牌创建投影 + 虚拟显示，帧回调里截一帧 */
    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            // 授权令牌失效（Android 14 令牌一次性 / 荣耀撤销权限）：提示重试，不崩溃
            Notifier.notifyScreenSenseHint(this, "识屏授权已失效，请再次点击识屏并允许权限")
            (application as AssistantApplication).container.panelState.value = AppContainer.PanelState.HIDDEN
            stopSelf()
            return
        }
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

    /** 截到一帧（handlerThread 线程）：存 PNG → 释放投影 → 拉起浮动界面（识图模式） */
    private fun onFrameCaptured(bitmap: Bitmap) {
        val container = (application as AssistantApplication).container
        // 取消「识屏准备中」提示（提醒关通知栏的）
        Notifier.cancelScreenSensePreparing(this)
        val dir = File(cacheDir, "screensense").apply { mkdirs() }
        // v1.4.1 框选模式：设置开启时先弹「选区层」让用户拖框，
        // 确认后由 RegionPickerActivity 裁剪并接续浮动界面（本服务到此结束）
        if (container.screenSenseRegionEnabled) {
            // 存**整张原图**（框选需要完整画面）；裁剪与缩放由选区层完成
            val fullFile = File(dir, "region_pending_${System.currentTimeMillis()}.png")
            try {
                FileOutputStream(fullFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                container.panelState.value = AppContainer.PanelState.HIDDEN
                stopSelf()
                return
            }
            releaseCapture()
            // 心跳起点：悬浮球服务的 CAPTURING 自愈据此判断框选层是否存活
            container.regionPickerHeartbeatAt = System.currentTimeMillis()
            startActivity(RegionPickerActivity.intentFor(this, fullFile.absolutePath))
            stopSelf()
            return
        }
        // ---- 默认（整屏识别）老路径 ----
        val scaled = ImageUtils.scaleBitmap(bitmap)
        val file = File(dir, "screen_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            container.panelState.value = AppContainer.PanelState.HIDDEN
            stopSelf()
            return
        }
        // 释放投影资源（省电）：浮动界面只展示本地文件，不再需要虚拟显示
        releaseCapture()
        // 拉起浮动界面（识图模式）：截图缩略图 + 提取文字/翻译/总结 + 输出区
        container.panelState.value = AppContainer.PanelState.PANEL_OPEN
        startActivity(
            FloatingPanelActivity.intentFor(
                this, FloatingPanelActivity.PanelMode.SCREEN_SENSE, file.absolutePath
            )
        )
        stopSelf()
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
        private const val EXTRA_DELAY_MS = "delay_ms"
        private const val NOTIFICATION_ID = 3001

        /** 默认截屏延迟（聊天/磁贴入口：通知栏可能展开，需时间收起） */
        const val DEFAULT_DELAY_MS = 2500L

        /** 悬浮球入口的截屏延迟（通知栏已收起，缩短等待） */
        const val BALL_DELAY_MS = 1200L

        /** 启动截屏服务（授权成功后调用，必须带 resultCode + data；delayMs 为截屏等待时长） */
        fun start(context: Context, resultCode: Int, data: Intent, delayMs: Long = DEFAULT_DELAY_MS) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_DELAY_MS, delayMs)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
