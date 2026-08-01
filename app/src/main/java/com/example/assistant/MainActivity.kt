package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.assistant.core.AppSharedState
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.feature.chat.ChatScreen
import com.example.assistant.feature.diary.DiaryScreen
import com.example.assistant.feature.home.HomeScreen
import com.example.assistant.feature.reminder.ReminderScreen
import com.example.assistant.feature.settings.SettingsScreen
import com.example.assistant.service.ScreenCaptureService
import com.example.assistant.service.ScreenResultOverlay
import com.example.assistant.tiles.ScreenSenseTileService
import com.example.assistant.ui.theme.AssistantTheme
import kotlinx.coroutines.launch

/** 底部导航的五个主页面 */
enum class MainTab(
    val label: String,
    val icon: ImageVector
) {
    Home("首页", Icons.Filled.Home),
    Chat("聊天", Icons.Filled.Send),
    Diary("日记", Icons.Filled.DateRange),
    Reminder("提醒", Icons.Filled.Notifications),
    Settings("设置", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as AssistantApplication).container

    /** 通知权限申请（Android 13+ 必需，否则每日总结/提醒通知被静默丢弃） */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 待启动截屏服务的授权数据（resultCode + data），权限请求通过后使用 */
    private var pendingCaptureResult: Pair<Int, Intent>? = null

    /**
     * MediaProjection 截屏授权。
     * 坑（荣耀 MagicOS）：用户点「允许」授权后，系统会（异步、时机不定地）撤销
     * FOREGROUND_SERVICE_MEDIA_PROJECTION 权限——检查时可能在、启动服务瞬间已没了。
     * 对策：授权返回后**固定请求一次权限**（标准 Android 上已授予则直接回调不弹框、
     * 零成本；荣耀上用户允许一次，权限"刚授予"状态启动服务，避开撤销竞态）。
     * 服务内还有 startForeground try-catch 兜底（见 ScreenCaptureService）。
     */
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                pendingCaptureResult = result.resultCode to result.data!!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mediaProjectionPermissionLauncher.launch(
                        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
                    )
                } else {
                    startScreenCaptureService(result.resultCode, result.data!!)
                }
            } else {
                // 用户取消授权：恢复方向（横屏锁定只服务于识屏流程）
                restoreOrientation()
            }
        }

    /** 荣耀把 FOREGROUND_SERVICE_MEDIA_PROJECTION 当运行时权限：请求通过后启动截屏服务 */
    private val mediaProjectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingCaptureResult
            pendingCaptureResult = null
            if (granted && pending != null) {
                startScreenCaptureService(pending.first, pending.second)
            } else {
                restoreOrientation()
            }
        }

    /**
     * 启动截屏服务前先让 App 退到后台（moveTaskToBack）：
     * 识屏的目标是"用户当前看到的屏幕"（其他 App 的内容），而授权后前台是助手 App——
     * 退后台后，服务延迟 1 秒截屏，截到的是用户上一个 App 的画面（服务见 ScreenCaptureService）。
     */
    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        // 提示用户关闭通知栏（荣耀无法编程收起，截屏会带上通知栏）；
        // 截屏完成后服务自动取消该通知
        Notifier.notifyScreenSensePreparing(this)
        // 授权成功：App 即将退后台，恢复方向（前台是目标 App，不受影响）
        restoreOrientation()
        moveTaskToBack(true)
        ScreenCaptureService.start(this, resultCode, data)
    }

    /** 识屏流程结束：恢复 manifest 的 fullSensor（跟随传感器） */
    private fun restoreOrientation() {
        if (landscapeLockedForScreenSense) {
            landscapeLockedForScreenSense = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    /** 悬浮窗权限设置页返回（onResume 里检查是否已开启） */
    private var pendingScreenCapture = false
    private var showOverlayDialog by mutableStateOf(false)

    /** 横屏环境触发识屏时锁定的横屏方向（授权流程结束后恢复） */
    private var landscapeLockedForScreenSense = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Manifest 已声明 fullSensor（跟随传感器方向）：横拿手机时 MainActivity
        // 以横屏加载（与屏幕一致，无旋转动画）；竖拿时竖屏（正常使用不受影响）。
        // 若仍处于横屏（横屏 App 场景），锁定横屏稳定授权流程（授权框不随旋转丢失），
        // 授权结束（成功退后台 / 用户取消）后由 restoreOrientation 恢复。
        val rotation = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            landscapeLockedForScreenSense = true
        }
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
        // 订阅识屏授权请求（聊天指令 → 弹系统授权框）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.screenSenseController.requests.collect {
                    launchScreenCapture()
                }
            }
        }
        setContent {
            AssistantTheme {
                AssistantApp()
                // 悬浮窗权限引导对话框（识屏小窗需要 overlay 权限）
                if (showOverlayDialog) {
                    AlertDialog(
                        onDismissRequest = { showOverlayDialog = false; pendingScreenCapture = false },
                        title = { Text("开启悬浮窗权限") },
                        text = { Text("识屏小窗需要「显示在其他应用上层」权限，才能在任意 App 上方展示截图与识别结果。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showOverlayDialog = false
                                openOverlaySettings()
                            }) { Text("去开启") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showOverlayDialog = false
                                pendingScreenCapture = false
                            }) { Text("取消") }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App 已在运行时收到新 Intent（通知点击 / 外部分享 / 识屏小窗「继续」）
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 从「显示在其他应用上层」设置页返回：已开启则继续识屏授权流程
        if (pendingScreenCapture) {
            pendingScreenCapture = false
            if (Settings.canDrawOverlays(this)) {
                launchMediaProjection()
            }
        }
    }

    /** 解析通知点击 / 分享 / 识屏等外部 Intent */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // 外部 App 分享（文本/图片）到助手
        if (intent.action == Intent.ACTION_SEND) {
            handleShare(intent)
            return
        }
        when (intent.getStringExtra(Notifier.EXTRA_ACTION)) {
            Notifier.ACTION_SHOW_SUMMARY -> {
                AppSharedState.currentTab.value = MainTab.Diary
                AppSharedState.showSummaryRequested.value = true
            }
            Notifier.ACTION_SHOW_BRIEFING -> {
                // 清晨简报：切首页并弹窗显示全文（文本随 intent 传递）
                AppSharedState.currentTab.value = MainTab.Home
                AppSharedState.briefingText.value =
                    intent.getStringExtra(Notifier.EXTRA_BRIEFING_TEXT) ?: "（简报内容缺失）"
            }
            Notifier.ACTION_SHOW_SCREEN_SENSE -> {
                // 识屏小窗「在 App 中继续」：切聊天页，把截图与已得结果交给聊天会话
                AppSharedState.currentTab.value = MainTab.Chat
                val path = intent.getStringExtra(ScreenResultOverlay.EXTRA_SCREEN_IMAGE_PATH).orEmpty()
                val text = intent.getStringExtra(ScreenResultOverlay.EXTRA_SCREEN_RESULT).orEmpty()
                container.screenSenseController.postResult(path, text)
            }
            ScreenSenseTileService.ACTION_SCREEN_SENSE -> {
                // 快捷磁贴点击：直接发起识屏授权
                launchScreenCapture()
            }
        }
    }

    /** 分享处理：文本预填输入框；图片进入聊天附件（等用户输入要求一起发送） */
    private fun handleShare(intent: Intent) {
        val type = intent.type ?: return
        AppSharedState.currentTab.value = MainTab.Chat
        when {
            type.startsWith("image/") -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) container.screenSenseController.postImageShare(uri)
            }
            type.startsWith("text/") -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) container.screenSenseController.postTextShare(text)
            }
        }
    }

    /** 识屏入口：先检查悬浮窗权限（小窗必需），未开启则引导；已开启直接弹系统授权 */
    private fun launchScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            pendingScreenCapture = true
            showOverlayDialog = true
            return
        }
        launchMediaProjection()
    }

    private fun launchMediaProjection() {
        // 统一流程：授权 →（荣耀会撤权限）→ 授权返回后固定请求权限 → 启动服务
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun AssistantApp() {
    val currentTab by AppSharedState.currentTab.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { AppSharedState.currentTab.value = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when (currentTab) {
            MainTab.Home -> HomeScreen(modifier)
            MainTab.Chat -> ChatScreen(modifier)
            MainTab.Diary -> DiaryScreen(modifier)
            MainTab.Reminder -> ReminderScreen(modifier)
            MainTab.Settings -> SettingsScreen(modifier)
        }
    }
}
