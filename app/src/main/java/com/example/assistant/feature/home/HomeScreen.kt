package com.example.assistant.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.MainTab
import com.example.assistant.core.AppSharedState
import com.example.assistant.core.ui.GlassCard
import com.example.assistant.data.db.entity.DailySummaryEntity
import com.example.assistant.data.db.entity.MonitoredEventEntity
import com.example.assistant.data.db.entity.ReminderEntity
import com.example.assistant.feature.memory.MemoryScreen
import com.example.assistant.service.FloatingBallService
import com.example.assistant.ui.theme.ChampagneGold
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 首页（2026-08-02 改版，glassmorphism 深墨夜景）：
 * 清晨简报 / 昨日小结气泡（点击弹窗）+ 悬浮球开关 + 最近提醒气泡（直接显示）+ 事件监控列表气泡。
 * 点击清晨简报通知 → 打开 App 后在这里弹出完整简报。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: HomeViewModel = viewModel {
        HomeViewModel(
            app.container.summaryStore,
            app.container.summaryRepository,
            app.container.reminderRepository,
            app.container.eventRepository,
            app.container.settingsStore
        )
    }

    val briefing by vm.latestBriefing.collectAsState()
    val briefingDate by vm.latestBriefingDate.collectAsState()
    val summary by vm.latestSummary.collectAsState()
    val reminders by vm.upcomingReminders.collectAsState()
    val events by vm.events.collectAsState()
    val ballEnabled by vm.floatingBallEnabled.collectAsState()
    val memories by app.container.memoryRepository.memories.collectAsState(initial = emptyList())

    // 首页子页：长期记忆管理（从首页进入，不占底部导航）
    var showMemoryPage by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showMemoryPage) { showMemoryPage = false }
    if (showMemoryPage) {
        MemoryScreen(
            modifier = modifier,
            onBack = { showMemoryPage = false }
        )
        return
    }

    // 悬浮球开关：直接启停服务（与设置页同一状态；从设置页切回来也会同步）
    LaunchedEffect(ballEnabled) {
        if (ballEnabled) FloatingBallService.start(context) else FloatingBallService.stop(context)
    }

    // 点击清晨简报通知进入：直接弹最新简报
    val notifyBriefing by AppSharedState.briefingText.collectAsState()
    var briefingDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var summaryDialog by rememberSaveable { mutableStateOf<DailySummaryEntity?>(null) }
    LaunchedEffect(notifyBriefing) {
        if (notifyBriefing != null) {
            briefingDialog = notifyBriefing
            AppSharedState.briefingText.value = null
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- 标题（按时段问候 + 日期）----
        item {
            val cal = remember { Calendar.getInstance() }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val greet = when {
                hour < 5 -> "夜深了"
                hour < 12 -> "早上好"
                hour < 14 -> "中午好"
                hour < 18 -> "下午好"
                else -> "晚上好"
            }
            val dateText = SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(cal.time)
            Column(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                Text(
                    buildAnnotatedString {
                        append(greet + "，我是")
                        withStyle(SpanStyle(color = ChampagneGold, fontWeight = FontWeight.SemiBold)) {
                            append(" 随身助手")
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    dateText + " · 今天想聊点什么？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // ---- 悬浮球开关（整行） ----
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🫧", style = MaterialTheme.typography.titleLarge)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("悬浮球", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (ballEnabled) "已开启 · 点击小球即可识屏 / 提醒 / 记录 / 对话"
                            else "未开启 · 在任意应用上层快捷使用助手",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ballEnabled,
                        onCheckedChange = { vm.setFloatingBallEnabled(it) }
                    )
                }
            }
        }

        // ---- 长期记忆入口（独立子页，不再挂在日记页） ----
        item {
            GlassCard(
                onClick = { showMemoryPage = true },
                containerAlpha = 0.06f
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠", style = MaterialTheme.typography.titleLarge)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("长期记忆", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${memories.size} 条 · 点击管理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "管理 →",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 清晨简报（左）+ 昨日小结（右）----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val isToday = briefingDate == todayString()
                GlassCard(
                    onClick = { if (briefing != null) briefingDialog = briefing },
                    containerAlpha = 0.06f,
                    modifier = Modifier.weight(1f)
                ) {
                    Column {
                        Text("🌅", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "清晨简报",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            if (briefing != null) {
                                if (isToday) "今日简报已生成 · 点击查看" else "最近简报（$briefingDate）· 点击查看"
                            } else "每日定时推送 · 今日提醒 + 昨日小结",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                GlassCard(
                    onClick = { if (summary != null) summaryDialog = summary },
                    containerAlpha = 0.06f,
                    modifier = Modifier.weight(1f)
                ) {
                    Column {
                        Text("📒", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "昨日小结",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            if (summary != null) "${summary!!.date} · 点击查看"
                            else "每天自动汇总当天日记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // ---- 最近提醒（整行） ----
        item {
            GlassCard(
                onClick = { AppSharedState.currentTab.value = MainTab.Reminder },
                containerAlpha = 0.06f
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "最近提醒",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                        Text(
                            "查看全部 →",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (reminders.isEmpty()) {
                        Text(
                            "暂无进行中的提醒（说「提醒我…」即可创建）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                        )
                    } else {
                        reminders.forEach { r ->
                            ReminderRow(r)
                        }
                    }
                }
            }
        }

        // ---- 事件监控（整行） ----
        item {
            GlassCard(
                onClick = {
                    AppSharedState.currentTab.value = MainTab.Reminder
                    AppSharedState.openEventTab.value = true
                },
                containerAlpha = 0.06f
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📰", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "事件监控",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                        Text(
                            "管理 →",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (events.isEmpty()) {
                        Text(
                            "暂无监控事件（说「关注 XX 新闻」即可创建）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                        )
                    } else {
                        events.forEach { e ->
                            EventRow(e)
                        }
                    }
                }
            }
        }
    }

    briefingDialog?.let { text ->
        AlertDialog(
            onDismissRequest = { briefingDialog = null },
            title = { Text("🌅 清晨简报") },
            text = {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { briefingDialog = null }) { Text("关闭") }
            }
        )
    }

    summaryDialog?.let { s ->
        AlertDialog(
            onDismissRequest = { summaryDialog = null },
            title = { Text("📒 小结（${s.date}）") },
            text = {
                SelectionContainer {
                    Text(
                        s.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { summaryDialog = null }) { Text("关闭") }
            }
        )
    }
}

/** 最近提醒的一行：时间 + 标题（+ 重复标记） */
@Composable
private fun ReminderRow(r: ReminderEntity) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp)
    ) {
        Text(
            formatReminderTime(r.triggerAtEpochMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        SelectionContainer(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                r.title,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val repeat = when (r.repeatRule) {
            "daily" -> "每天"
            "weekly" -> "每周"
            else -> null
        }
        if (repeat != null) {
            Text(
                repeat,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 事件监控的一行：名称 + 检查周期 */
@Composable
private fun EventRow(e: MonitoredEventEntity) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp)
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                e.displayName,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            "每${e.pollHours}小时检查",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun todayString(): String {
    val cal = Calendar.getInstance()
    return "%04d-%02d-%02d".format(
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )
}

/** 提醒时间显示：今天/明天 + HH:mm，更远显示 MM-dd HH:mm */
private fun formatReminderTime(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val tomorrow = (Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) })
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(cal, now) -> "今天 $hm"
        sameDay(cal, tomorrow) -> "明天 $hm"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(cal.time)
    }
}
