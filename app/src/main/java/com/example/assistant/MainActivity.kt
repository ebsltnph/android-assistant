package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.assistant.data.db.entity.ReminderEntity
import kotlinx.coroutines.launch
import java.util.Calendar
import com.example.assistant.core.AppSharedState
import com.example.assistant.core.notification.Notifier
import com.example.assistant.di.AppContainer
import com.example.assistant.feature.chat.ChatScreen
import com.example.assistant.feature.diary.DiaryScreen
import com.example.assistant.feature.home.HomeScreen
import com.example.assistant.feature.reminder.ReminderScreen
import com.example.assistant.feature.settings.SettingsScreen
import com.example.assistant.core.vision.ScreenSenseStarter
import com.example.assistant.tiles.ScreenSenseTileService
import com.example.assistant.ui.theme.AssistantTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 识屏授权流程在 MediaProjectionPermissionActivity（独立 task）中进行，
        // 横屏锁定等荣耀对策见该 Activity / ScreenSenseStarter
        // 主界面固定竖屏（manifest screenOrientation="portrait"，v1.2.1 用户决定），
        // 无需 OrientationUtils；浮动界面/授权 Activity 仍走 sensor（见各自 onCreate）
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
                // 提醒通知点击 → 确认弹窗（确认后才停止 5 分钟重复通知）
                ReminderConfirmDialog(app = container)
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
        // （原悬浮窗权限检查与引导已随 P6 识屏入口统一到权限 Activity 而移除）
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
            Notifier.ACTION_CONFIRM_REMINDER -> {
                // 提醒通知点击：弹确认窗（确认后停止 5 分钟重复通知）
                val rid = intent.getLongExtra(Notifier.EXTRA_REMINDER_ID, -1)
                if (rid > 0) AppSharedState.pendingReminderConfirmId.value = rid
            }
            Notifier.ACTION_SHOW_EVENT_HIT -> {
                // 事件监控命中通知点击：切到提醒页事件 tab，弹出该事件详情（含触发历史）
                val eid = intent.getLongExtra(Notifier.EXTRA_EVENT_ID, -1)
                if (eid > 0) {
                    AppSharedState.currentTab.value = MainTab.Reminder
                    AppSharedState.openEventTab.value = true
                    AppSharedState.eventDetailId.value = eid
                }
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

    /**
     * 识屏入口（聊天指令 / 快捷磁贴）。
     * 授权**在本 Activity（主 task）内进行**：授权后 moveTaskToBack 退的是主 task，
     * 回到上一个 App 再截屏（P5 已验证路径）。
     * 注意：不能用独立 task 的权限 Activity——它退的是自己的空 task，
     * 主 task 不动，截屏会截到助手自己（用户反馈的 bug，勿改回）。
     */
    private fun launchScreenCapture() {
        // 置 CAPTURING：悬浮球服务据此隐藏悬浮球（防被截进截图）
        container.panelState.value = AppContainer.PanelState.CAPTURING
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /**
     * MediaProjection 截屏授权（荣耀对策与 P5 相同）：
     * 授权返回后**固定请求一次** FOREGROUND_SERVICE_MEDIA_PROJECTION 权限
     * （荣耀把该权限当运行时权限且授权后异步撤销，固定请求避开撤销竞态）。
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
                    ScreenSenseStarter.finishAuth(this, result.resultCode, result.data!!)
                }
            } else {
                // 用户取消授权：恢复悬浮球显示
                container.panelState.value = AppContainer.PanelState.HIDDEN
            }
        }

    /** 荣耀把 FOREGROUND_SERVICE_MEDIA_PROJECTION 当运行时权限：请求通过后启动截屏服务 */
    private val mediaProjectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingCaptureResult
            pendingCaptureResult = null
            if (granted && pending != null) {
                ScreenSenseStarter.finishAuth(this, pending.first, pending.second)
            } else {
                container.panelState.value = AppContainer.PanelState.HIDDEN
            }
        }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * 提醒确认弹窗：提醒通知点击 → App 弹出，手动确认后停止 5 分钟重复通知。
 * 「确认」→ 记录确认时间 + 取消重复闹钟 + 一次性提醒标记 fired（从列表消失）；
 * 「稍后」/点外部 → 不确认，继续每 5 分钟重复提醒。
 */
@Composable
private fun ReminderConfirmDialog(app: AppContainer) {
    val reminderId by AppSharedState.pendingReminderConfirmId.collectAsState()
    if (reminderId == null) return
    val scope = rememberCoroutineScope()
    var reminder by remember(reminderId) { mutableStateOf<ReminderEntity?>(null) }
    var loaded by remember(reminderId) { mutableStateOf(false) }

    LaunchedEffect(reminderId) {
        reminder = app.reminderRepository.byId(reminderId!!)
        loaded = true
    }
    if (!loaded) return

    val item = reminder
    if (item == null) {
        // 提醒已被删除：直接关闭
        LaunchedEffect(Unit) { AppSharedState.pendingReminderConfirmId.value = null }
        return
    }
    AlertDialog(
        onDismissRequest = { AppSharedState.pendingReminderConfirmId.value = null },
        title = { Text("⏰ 提醒确认") },
        text = {
            Text(
                "「${item.title}」\n时间：${formatReminderTime(item.triggerAtEpochMillis)}\n" +
                    "确认后停止提醒；不确认则每 5 分钟再提醒一次。"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    app.reminderRepository.ack(item.id)
                    app.reminderScheduler.cancelAckRepeat(item.id)
                    // 一次性提醒：确认后标记 fired（触发 24h 后自动清理，列表不堆积）
                    if (item.repeatRule == null) app.reminderRepository.markFired(item.id)
                }
                AppSharedState.pendingReminderConfirmId.value = null
            }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = { AppSharedState.pendingReminderConfirmId.value = null }) {
                Text("稍后")
            }
        }
    )
}

/** 提醒触发时间显示：X月X日 HH:mm */
private fun formatReminderTime(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 " +
        "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
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
