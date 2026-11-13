package com.example.assistant.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.assistant.core.scheduled.ScheduledChat
import com.example.assistant.core.ui.GlassCard
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置 → 定时对话（2026-11-14）。
 *
 * 到点后不是"发一条通知"了事，而是以可编辑提示词作为一条用户消息，
 * 走与聊天页完全相同的对话回路（工具、上下文、缓存、持久化），回复进入聊天记录。
 *
 * 列表 + 编辑页同文件；编辑页是子页内的二级视图，系统返回键先回列表。
 */
@Composable
internal fun ScheduledChatPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val schedules by vm.scheduledChatList.collectAsState()
    // 编辑中的配置用 JSON Saver 保存：深色模式/字体缩放/进程重建后草稿不丢
    var editing by rememberSaveable(stateSaver = ScheduledChatSaver) {
        mutableStateOf<ScheduledChat?>(null)
    }

    // 编辑页内返回先回列表（父级 SettingsScreen 的 BackHandler 在更外层）
    BackHandler(enabled = editing != null) { editing = null }

    val current = editing
    if (current != null) {
        ScheduledChatEditor(
            initial = current,
            onSave = { vm.saveScheduledChat(it); editing = null },
            onDelete = { id -> vm.deleteScheduledChat(id); editing = null },
            onBack = { editing = null }
        )
        return
    }

    // 精确闹钟授权状态：从系统设置返回时刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    var exactAlarm by remember { mutableStateOf(vm.scheduledChatCanExact()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 从系统设置返回后刷新授权状态；排程重排由 MainActivity.onResume 统一负责
                exactAlarm = vm.scheduledChatCanExact()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var runNotice by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("定时对话", onBack) }

        item {
            Text(
                "到点后，助手会把你设置的提示词当作一条用户消息，走与聊天页完全相同的对话回路" +
                    "（同样的上下文、工具与记忆；可自行读取提醒、日记后再提醒你）。" +
                    "回复会出现在聊天页，并推送通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!exactAlarm && schedules.any { it.enabled }) {
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "⚠️ 精确闹钟未授权",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "未授权时系统可能延迟触发，撤销权限时还会清除已排闹钟；" +
                                "打开 App 会自动恢复排程。到系统设置开启「闹钟和提醒」后更准点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            requestExactAlarmPermission(context)
                        }) { Text("去授权") }
                    }
                }
            }
        }

        if (schedules.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        "还没有定时对话。点下方「新建定时对话」，设置每天或每周的固定时刻与提示词。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(schedules, key = { it.id }) { schedule ->
            ScheduledChatCard(
                schedule = schedule,
                nextAt = vm.nextScheduledChatAt(schedule),
                onToggle = { vm.setScheduledChatEnabled(schedule.id, it) },
                onEdit = { editing = schedule },
                onRun = {
                    vm.runScheduledChatNow(schedule.id)
                    runNotice = "已开始执行「${schedule.displayName}」，完成后会出现在聊天页"
                },
                onDelete = { vm.deleteScheduledChat(schedule.id) }
            )
        }

        runNotice?.let { text ->
            item {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        item {
            OutlinedButton(onClick = {
                editing = ScheduledChat.createDraft()
                runNotice = null
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("新建定时对话")
            }
        }

        item { HorizontalDivider() }

        item {
            Text(
                "说明：\n" +
                    "· 每天/每周按本机时间计算；重启、修改系统时间或时区后会自动重排。\n" +
                    "· 后台执行受系统省电策略影响；若经常延迟，可在系统设置里给助手开启「电池无限制」。\n" +
                    "· 正在聊天时到点，会等当前回复结束（最长约 3 分钟），再插入这一轮。\n" +
                    "· 定时对话用的是当前「对话」能力指派的模型；未配置模型时会在聊天里提示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScheduledChatCard(
    schedule: ScheduledChat,
    nextAt: Long?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(schedule.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        schedule.scheduleLabel() + if (schedule.enabled) "" else " · 已停用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            }

            Text(
                schedule.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                buildString {
                    append(
                        when {
                            !schedule.enabled -> "已停用"
                            nextAt == null -> "排程无效"
                            else -> "下次：" + formatDateTime(nextAt)
                        }
                    )
                    if (schedule.lastTriggeredAt > 0L) {
                        append(" · 上次：").append(formatDateTime(schedule.lastTriggeredAt))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onRun, enabled = schedule.enabled) { Text("立即运行") }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除定时对话") },
            text = { Text("确定删除「${schedule.displayName}」吗？已生成的历史对话不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduledChatEditor(
    initial: ScheduledChat,
    onSave: (ScheduledChat) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var prompt by rememberSaveable(initial.id) { mutableStateOf(initial.prompt) }
    var repeat by rememberSaveable(initial.id) { mutableStateOf(initial.repeat) }
    var weekday by rememberSaveable(initial.id) { mutableIntStateOf(initial.weekday) }
    var minute by rememberSaveable(initial.id) { mutableIntStateOf(initial.minuteOfDay) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isNew = initial.id <= 0L

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader(if (isNew) "新建定时对话" else "编辑定时对话", onBack) }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "启用",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }

                    Text("重复", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = repeat == ScheduledChat.REPEAT_DAILY,
                            onClick = { repeat = ScheduledChat.REPEAT_DAILY },
                            label = { Text("每天") }
                        )
                        FilterChip(
                            selected = repeat == ScheduledChat.REPEAT_WEEKLY,
                            onClick = { repeat = ScheduledChat.REPEAT_WEEKLY },
                            label = { Text("每周") }
                        )
                    }

                    if (repeat == ScheduledChat.REPEAT_WEEKLY) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ScheduledChat.WEEKDAY_CHOICES.forEach { day ->
                                FilterChip(
                                    selected = weekday == day,
                                    onClick = { weekday = day },
                                    label = { Text("周${ScheduledChat.weekdayLabel(day)}") }
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "触发时间",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        MinutePicker(current = minute, onChange = { minute = it })
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "到点发送的提示词",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { prompt = ScheduledChat.DEFAULT_PROMPT }) {
                            Text("恢复默认")
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 8,
                        maxLines = 16,
                        isError = prompt.isBlank(),
                        supportingText = {
                            Text(
                                if (prompt.isBlank()) "提示词不能为空"
                                else "会作为一条用户消息发给模型；可让它自行读取提醒、日记后再提醒你。"
                            )
                        }
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                prompt = prompt.trim(),
                                repeat = repeat,
                                weekday = weekday,
                                minuteOfDay = minute,
                                enabled = enabled
                            )
                        )
                    },
                    enabled = prompt.isNotBlank()
                ) { Text("保存") }
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Text(
                "保存后立即生效：下一次按新时间排程；修改提示词只影响之后的触发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除定时对话") },
            text = { Text("确定删除这条定时对话吗？已生成的历史对话不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

private val scheduleJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 编辑草稿的 Saveable 序列化（ScheduledChat 不是 Parcelable，用 JSON 字符串过 Bundle） */
private val ScheduledChatSaver: Saver<ScheduledChat?, String> = Saver(
    save = { item ->
        item?.let { runCatching { scheduleJson.encodeToString(it) }.getOrNull() } ?: ""
    },
    restore = { raw ->
        if (raw.isBlank()) null
        else runCatching { scheduleJson.decodeFromString<ScheduledChat>(raw) }.getOrNull()
    }
)

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("M月d日 EEE HH:mm", Locale.CHINA).format(Date(millis))

/** 跳系统「闹钟和提醒」授权页；Android 12 以下没有该页面，退到应用详情 */
private fun requestExactAlarmPermission(context: android.content.Context) {
    val uri = Uri.parse("package:${context.packageName}")
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (_: Exception) {
        }
    }
}
