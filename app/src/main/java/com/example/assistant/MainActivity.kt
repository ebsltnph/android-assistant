package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.assistant.core.AppSharedState
import com.example.assistant.core.notification.Notifier
import com.example.assistant.feature.chat.ChatScreen
import com.example.assistant.feature.diary.DiaryScreen
import com.example.assistant.feature.home.HomeScreen
import com.example.assistant.feature.reminder.ReminderScreen
import com.example.assistant.feature.settings.SettingsScreen
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

    /** 通知权限申请（Android 13+ 必需，否则每日总结/提醒通知被静默丢弃） */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
        setContent {
            AssistantTheme {
                AssistantApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App 已在运行时点击通知：更新共享状态，由 DiaryScreen 消费
        handleIntent(intent)
    }

    /** 解析通知点击等外部 Intent，写入共享状态 */
    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(Notifier.EXTRA_ACTION)) {
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
