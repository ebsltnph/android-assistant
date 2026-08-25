package com.example.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.assistant.AssistantApplication
import com.example.assistant.MainActivity
import com.example.assistant.R
import com.example.assistant.core.floating.OverlayWindow
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.feature.floating.FloatingPanelActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮球前台服务（P6）：
 * 常驻显示玻璃拟态悬浮球（TYPE_APPLICATION_OVERLAY），可拖拽、贴边半隐藏；
 * 点击展开浮动界面（FloatingPanelActivity，独立 task 透明 Activity）。
 *
 * 悬浮球显隐由 [AppContainer.panelState] 驱动（本服务订阅）：
 * 面板打开 / 识屏授权截屏中 → 隐藏（防悬浮球被截进截图）；面板关闭 → 重现。
 * 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限，未授权时 addView 失败 → 发通知引导 + 自停。
 */
class FloatingBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = OverlayWindow(this)

    private val container: AppContainer
        get() = (application as AssistantApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 订阅面板状态：HIDDEN → 显示悬浮球；PANEL_OPEN/CAPTURING → 隐藏
        scope.launch {
            container.panelState.collect { state ->
                try {
                    when (state) {
                        AppContainer.PanelState.HIDDEN -> showBall()
                        // 截屏期间必须隐藏（防悬浮球被截进截图）
                        AppContainer.PanelState.CAPTURING -> {
                            hideBall()
                            // 自愈：授权/截屏流程中断（如系统回收权限 Activity 未回调）
                            // 时，60 秒后强制恢复悬浮球，避免状态卡死球永远不出现。
                            // v1.4.1 框选：选区层（RegionPickerActivity）显示期间心跳
                            // 持续刷新——用户框选超过 60 秒属正常等待，继续延后自愈
                            scope.launch {
                                while (true) {
                                    kotlinx.coroutines.delay(60_000)
                                    if (container.panelState.value != AppContainer.PanelState.CAPTURING) break
                                    val fresh = System.currentTimeMillis() - container.regionPickerHeartbeatAt < 15_000
                                    if (fresh) continue   // 框选层还活着 → 再等一轮
                                    container.panelState.value = AppContainer.PanelState.HIDDEN
                                    break
                                }
                            }
                        }
                        // 自愈：面板实际已关闭（系统回收 Activity 未回调 onDestroy）→ 恢复显示
                        AppContainer.PanelState.PANEL_OPEN ->
                            if (FloatingPanelActivity.isPanelOpen) hideBall() else showBall()
                    }
                } catch (e: Exception) {
                    // 单次处理失败不杀死订阅（否则后续状态变化不再响应，球永久隐藏）
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务（specialUse 类型，类型在 Manifest 声明）：常驻通知（低优先级不打扰）
        startForeground(NOTIFICATION_ID, buildNotification())
        // 通知栏常驻：点击打开 App（方便管理/关闭悬浮球）
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        window.hide()
        super.onDestroy()
    }

    // ---- 悬浮球 ----

    /** 悬浮球初始位置：屏幕右侧中部（坐标相对 gravity，即 y 方向从顶部起算） */
    private var ballX: Int = 0
    private var ballY: Int = 0

    private fun showBall() {
        if (window.isShowing) return
        val (screenW, screenH) = screenSize()
        ballX = screenW - ballSizePx() - hiddenWidthPx() // 默认贴右边缘
        ballY = screenH / 2 - ballSizePx() / 2
        window.show(
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ballX
                y = ballY
            },
            content = {
                BallContent(
                    onTap = ::onBallTap,
                    onDragMove = { dx, dy -> window.moveBy(dx, dy) },
                    onDragEnd = ::snapToEdge
                )
            },
            onAddError = {
                // 悬浮窗权限未开启等：发通知引导，不崩溃
                Notifier.notifyScreenSenseHint(
                    this, "悬浮球需要「显示在其他应用上层」权限，请在系统设置中开启后重开悬浮球"
                )
                stopSelf()
            }
        )
    }

    private fun hideBall() {
        window.hide()
    }

    /** 点击悬浮球：展开浮动界面（主模式，自动开始语音听写——受设置开关控制）。
     *  SYSTEM_ALERT_WINDOW 权限豁免后台启动限制 */
    private fun onBallTap() {
        startActivity(
            FloatingPanelActivity.intentFor(
                this,
                FloatingPanelActivity.PanelMode.MAIN,
                autoVoice = true
            )
        )
    }

    /** 拖动结束：就近吸附到屏幕左/右边缘，半隐藏（只露出一点可点击的边） */
    private fun snapToEdge() {
        val params = window.currentParams() ?: return
        val (screenW, screenH) = screenSize()
        val size = ballSizePx()
        // 球心在屏幕左半 → 贴左（往左移出大部分）；右半 → 贴右
        val targetX = if (params.x + size / 2 < screenW / 2) {
            -size + hiddenWidthPx()
        } else {
            screenW - hiddenWidthPx()
        }
        // y 限制在屏幕内（别拖丢）
        val targetY = params.y.coerceIn(0, screenH - size)
        ballX = targetX
        ballY = targetY
        window.setPosition(targetX, targetY)
    }

    /**
     * 屏幕尺寸（像素）：API 30+ 用 currentWindowMetrics；
     * API 26-29 用 defaultDisplay.getRealSize 兜底（currentWindowMetrics 是 API 30
     * 才有的方法，直接调用在 Android 8-11 上 NoSuchMethodError 崩溃——跨设备兼容）。
     * 两者都返回包含系统栏的完整屏幕尺寸，与 overlay 坐标一致。
     */
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            wm.defaultDisplay.getRealSize(size)
            size.x to size.y
        }
    }

    private fun ballSizePx(): Int = 56.dp.run { (this.value * resources.displayMetrics.density).toInt() }

    /**
     * 贴边后露出的宽度（可点击再拖出来）。
     * 注意：不能太窄——荣耀右边缘是返回手势区（约 20-40px），
     * 露出太窄时点击会被系统手势吞掉（悬浮球点击无反应的根因）。
     * 32dp 露出后球的大部分在返回手势区之外，点击可靠。
     */
    private fun hiddenWidthPx(): Int = 32.dp.run { (this.value * resources.displayMetrics.density).toInt() }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifier.CHANNEL_FLOATING_BALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("悬浮球运行中")
            .setContentText("点击悬浮球可识屏、提醒、记录或对话")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 3004

        /**
         * 启动悬浮球前台服务。
         *
         * **只在 App 前台时启动**（[allowBackground] = true 的豁免场景除外，如 BOOT_COMPLETED）：
         * - App 在后台时启动 FGS：Android 12+ 抛 ForegroundServiceStartNotAllowedException；
         *   处于豁免窗口时虽不抛异常，但 5 秒内 startForeground 未执行会被系统直接杀进程
         *   （ForegroundServiceDidNotStartInTimeException——"重启后打开 App 闪退"的真机实锤）。
         * - 后台冷启动（闹钟/WorkManager 唤醒进程）时根本没有悬浮球的必要，跳过即可，
         *   用户下次打开 App（前台）由 Application 的延迟启动自动拉起。
         */
        fun start(context: Context, allowBackground: Boolean = false) {
            if (!allowBackground) {
                val app = context.applicationContext as AssistantApplication
                if (!app.isAppInForeground) return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingBallService::class.java))
            } catch (_: Exception) {
                // 后台启动被拒等：静默忽略，等下次前台机会再拉起
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }
}

/**
 * 悬浮球外观：与浮动界面同风格的深墨玻璃球（glassmorphism）——
 * 深墨蓝灰玻璃底（顶部略亮高光）+ 半透明白描边 + 柔和阴影，
 * 图标用香槟金 #E4B863（风格唯一强调色）。
 * 手势：用「按下到抬起的总位移」区分点击与拖动（比 detectDragGestures 的
 * 拖动回调判定更可靠——点击时手指微小抖动不会被误判成拖动，避免球被拖走贴边）。
 */
@Composable
private fun BallContent(
    onTap: () -> Unit,
    onDragMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val ballSize = 56.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ballSize)
            .shadow(14.dp, CircleShape, ambientColor = Color(0xFF060A13).copy(alpha = 0.7f))
            .clip(CircleShape)
            .background(
                // 深墨玻璃：顶部略亮（玻璃高光），底部深墨夜景
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF4A5A78),
                        Color(0xFF1A2436)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var total = Offset.Zero
                    var isDrag = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            // 抬起：未进入拖动 = 点击（开面板）；拖过 = 贴边
                            if (isDrag) onDragEnd() else onTap()
                            break
                        }
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            if (!isDrag) {
                                total += delta
                                // 超过触摸阈值才进入拖动（点击的微小抖动不算）
                                if (total.getDistance() > slop) isDrag = true
                            }
                            if (isDrag) {
                                change.consume()
                                onDragMove(delta.x, delta.y)
                            }
                        }
                    }
                }
            }
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = "随身助手",
            tint = Color(0xFFE4B863),
            modifier = Modifier.size(24.dp)
        )
    }
}
