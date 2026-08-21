package com.example.assistant.feature.reminder

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.assistant.core.AppSharedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.assistant.AssistantApplication
import com.example.assistant.core.alarm.ReminderScheduler
import com.example.assistant.data.db.entity.EventHitEntity
import com.example.assistant.data.db.entity.MonitoredEventEntity
import com.example.assistant.data.db.entity.ReminderEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 提醒页：定时提醒列表 + 手动添加（日期/时间选择器 + 重复选项）。
 * 聊天说"提醒我…"走 LLM 解析创建（见 ChatViewModel），这里提供界面入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val vm: ReminderViewModel = viewModel {
        ReminderViewModel(
            reminderRepository = app.container.reminderRepository,
            reminderScheduler = app.container.reminderScheduler,
            eventRepository = app.container.eventRepository
        )
    }

    val reminders by vm.reminders.collectAsState()
    val events by vm.events.collectAsState()
    val message by vm.message.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<ReminderEntity?>(null) }
    var subTab by rememberSaveable { mutableStateOf(0) } // 0=提醒 1=事件监控

    // 首页「事件监控」气泡点击 → 打开事件 tab（消费后置回）
    val openEventTab by AppSharedState.openEventTab.collectAsState()
    LaunchedEffect(openEventTab) {
        if (openEventTab) {
            subTab = 1
            AppSharedState.openEventTab.value = false
        }
    }

    // 事件命中通知点击 → 打开事件 tab 并弹出该事件详情（消费后置回）
    // 用事件 id 而非实体：通知点击时列表可能还没加载，详情弹窗内再按 id 从列表解析
    var detailEventId by remember { mutableStateOf<Long?>(null) }
    val eventDetailRequest by AppSharedState.eventDetailId.collectAsState()
    LaunchedEffect(eventDetailRequest) {
        val eid = eventDetailRequest
        if (eid != null) {
            AppSharedState.eventDetailId.value = null
            subTab = 1
            detailEventId = eid
        }
    }

    // 精确闹钟权限提示（未授权时提醒可能延迟到 ±10 分钟窗口内）
    val exactAlarmGranted = remember { app.container.reminderScheduler.canExact() }

    Column(modifier = modifier.fillMaxSize()) {
        if (!exactAlarmGranted) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "⚠️ 精确闹钟未授权，提醒可能不准点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    // 跳系统设置开启"闹钟和提醒"
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                }) { Text("去授权") }
            }
        }

        // 双 tab：提醒 / 事件监控
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("提醒") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("事件监控") })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "提醒",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (subTab == 0) {
                TextButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加提醒")
                }
            } else {
                TextButton(onClick = { app.runEventPollNow() }) {
                    Text("立即检查")
                }
            }
        }

        if (subTab == 0) {
            // ---- 提醒列表 ----
            if (reminders.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "还没有提醒。\n可以直接说「提醒我明天下午3点开会」，\n或点右上角手动添加。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(reminders, key = { it.id }) { reminder ->
                        ReminderCard(
                            reminder = reminder,
                            onEdit = { editingReminder = reminder },
                            onDelete = { vm.delete(reminder.id) }
                        )
                    }
                }
            }
        } else {
            // ---- 事件监控列表 ----
            if (events.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "还没有监控的事件。\n聊天里说「关注XX新闻」，\n我会定期搜索并在有重要动态时通知你。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        var editing by remember { mutableStateOf(false) }
                        EventCard(
                            event = event,
                            onClick = { detailEventId = event.id },
                            onToggle = { enabled -> vm.setEventEnabled(event.id, enabled) },
                            onEdit = { editing = true },
                            onDelete = { vm.deleteEvent(event.id) }
                        )
                        if (editing) {
                            EditEventDialog(
                                event = event,
                                onDismiss = { editing = false },
                                onConfirm = { name, query, cond, rule, domains, pollHours ->
                                    vm.updateEventConfig(event.id, name, query, cond, rule, domains, pollHours)
                                    editing = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (message != null) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    message ?: "",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2000)
                vm.clearMessage()
            }
        }
    }

    if (showAdd) {
        AddReminderDialog(
            onDismiss = { showAdd = false },
            onConfirm = { title, triggerAt, repeat ->
                vm.add(title, triggerAt, repeat)
                showAdd = false
            }
        )
    }

    editingReminder?.let { reminder ->
        AddReminderDialog(
            initial = reminder,
            onDismiss = { editingReminder = null },
            onConfirm = { title, triggerAt, repeat ->
                vm.update(reminder.id, title, triggerAt, repeat)
                editingReminder = null
            }
        )
    }

    // 事件详情弹窗（卡片点击 / 命中通知点击）：配置 + 触发历史
    detailEventId?.let { eid ->
        EventDetailDialog(
            eventId = eid,
            event = events.firstOrNull { it.id == eid },
            hits = vm.hitsFor(eid),
            onDismiss = { detailEventId = null }
        )
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (reminder.status) {
                "pending" -> MaterialTheme.colorScheme.surfaceVariant
                "fired" -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        reminder.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (reminder.status == "pending") MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Text(
                    formatTime(reminder.triggerAtEpochMillis) + repeatLabel(reminder.repeatRule) +
                        statusLabel(reminder.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

/** 事件监控卡片：名称/搜索词/规则 + 周期显示 + 启停开关 + 编辑 + 删除（点击卡片看详情/触发历史） */
@Composable
private fun EventCard(
    event: MonitoredEventEntity,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (event.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        event.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (event.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                SelectionContainer {
                    Text(
                        "搜索：${event.searchQuery}" +
                            (if (event.conditionKeywords.isNotBlank()) " · 命中：${event.conditionKeywords}" else "") +
                            (if (event.customRule.isNotBlank()) "\n规则：${event.customRule}" else "") +
                            (if (event.includeDomains.isNotBlank()) "\n来源：${event.includeDomains}" else "") +
                            "\n每 ${event.pollHours} 小时检查",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = event.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

/**
 * 事件详情对话框：完整配置 + 「监控触发历史」（每次命中落库的记录）。
 * 命中条目可点「打开」跳浏览器看原文。
 * event 为 null 时（如事件已被删除）只显示提示。
 */
@Composable
private fun EventDetailDialog(
    eventId: Long,
    event: MonitoredEventEntity?,
    hits: kotlinx.coroutines.flow.Flow<List<EventHitEntity>>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hitList by hits.collectAsState(initial = emptyList())
    val now = System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📰 ${event?.displayName ?: "事件详情"}") },
        text = {
            SelectionContainer {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (event == null) {
                    Text(
                        "该监控事件已不存在（可能已被删除）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@Column
                }
                Text("搜索：${event.searchQuery}", style = MaterialTheme.typography.bodyMedium)
                if (event.conditionKeywords.isNotBlank()) {
                    Text("命中关键词：${event.conditionKeywords}", style = MaterialTheme.typography.bodySmall)
                }
                if (event.customRule.isNotBlank()) {
                    Text("规则：${event.customRule}", style = MaterialTheme.typography.bodySmall)
                }
                if (event.includeDomains.isNotBlank()) {
                    Text("来源：${event.includeDomains}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "每 ${event.pollHours} 小时检查 · " +
                        (if (event.enabled) "已启用" else "已停用") +
                        " · 上次检查 " +
                        if (event.lastCheckedAtEpochMillis > 0) formatTime(event.lastCheckedAtEpochMillis) else "从未",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text("监控触发历史（最近 ${hitList.size} 条）", style = MaterialTheme.typography.titleSmall)
                if (hitList.isEmpty()) {
                    Text(
                        "还没有触发记录。命中事件时我会在这里记下时间与内容，同时发通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    hitList.forEach { hit ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                formatHitTime(hit.hitAtEpochMillis, now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(hit.title, style = MaterialTheme.typography.bodyMedium)
                                if (hit.content.isNotBlank() && hit.content != hit.title) {
                                    Text(
                                        hit.content.take(160) + (if (hit.content.length > 160) "…" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (hit.url.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(hit.url))
                                                )
                                            } catch (_: Exception) {
                                            }
                                        },
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("打开原文 ↗", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 触发历史时间：今天 HH:mm，更早显示 M月d日 HH:mm */
private fun formatHitTime(millis: Long, now: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val hm = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    val sameDay = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) hm else "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $hm"
}

/** 事件编辑对话框：名称/搜索词/条件关键词/自定义规则/限定域名/检查周期 */
@Composable
private fun EditEventDialog(
    event: MonitoredEventEntity,
    onDismiss: () -> Unit,
    onConfirm: (name: String, query: String, condition: String, rule: String, domains: String, pollHours: Int) -> Unit
) {
    var name by remember { mutableStateOf(event.displayName) }
    var query by remember { mutableStateOf(event.searchQuery) }
    var condition by remember { mutableStateOf(event.conditionKeywords) }
    var rule by remember { mutableStateOf(event.customRule) }
    var domains by remember { mutableStateOf(event.includeDomains) }
    var pollHours by remember { mutableStateOf(event.pollHours.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑监控事件") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索词") }, singleLine = true)
                OutlinedTextField(
                    value = condition, onValueChange = { condition = it },
                    label = { Text("命中关键词（逗号分隔，可空）") }, singleLine = true
                )
                OutlinedTextField(
                    value = rule, onValueChange = { rule = it },
                    label = { Text("自定义判断规则（可空）") },
                    placeholder = { Text("如：只关注更新文档的变更，忽略传言") },
                    minLines = 2
                )
                OutlinedTextField(
                    value = domains, onValueChange = { domains = it },
                    label = { Text("限定来源域名（逗号分隔，可空）") },
                    placeholder = { Text("如：api-docs.deepseek.com") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = pollHours, onValueChange = { pollHours = it.filter(Char::isDigit) },
                    label = { Text("检查周期（小时，1-168）") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && query.isNotBlank(),
                onClick = {
                    val hours = pollHours.toIntOrNull()?.coerceIn(1, 168) ?: 24
                    onConfirm(name.trim(), query.trim(), condition.trim(), rule.trim(), domains.trim(), hours)
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 添加/编辑提醒对话框：标题 + 日期 + 时间 + 重复（initial 不为空时进入编辑模式并预填） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderDialog(
    initial: ReminderEntity? = null,
    onDismiss: () -> Unit,
    onConfirm: (title: String, triggerAtEpochMillis: Long, repeat: String?) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }

    // 默认：今天 + 下一个整点；编辑时用原提醒的时间（DatePicker 需要 UTC 午夜）
    val now = Calendar.getInstance()
    val initialCal = Calendar.getInstance().apply {
        timeInMillis = initial?.triggerAtEpochMillis ?: now.timeInMillis
    }
    val defaultDateUtc = if (initial != null) {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(
                initialCal.get(Calendar.YEAR), initialCal.get(Calendar.MONTH),
                initialCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0
            )
            set(Calendar.MILLISECOND, 0)
        }
    } else {
        now.clone() as Calendar
    }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = utcMidnightMillis(defaultDateUtc)
    )
    val timeState = rememberTimePickerState(
        initialHour = if (initial != null) initialCal.get(Calendar.HOUR_OF_DAY)
        else (now.get(Calendar.HOUR_OF_DAY) + 1) % 24,
        initialMinute = if (initial != null) initialCal.get(Calendar.MINUTE) else 0
    )

    var repeatRule by remember { mutableStateOf(initial?.repeatRule) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val dateText = remember(dateState.selectedDateMillis) {
        dateState.selectedDateMillis?.let { formatDate(it) } ?: ""
    }
    val timeText = "%02d:%02d".format(timeState.hour, timeState.minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加提醒" else "编辑提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("提醒内容") },
                    placeholder = { Text("如：喝水") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("日期", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { showDatePicker = true }) { Text(dateText) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("时间", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { showTimePicker = true }) { Text(timeText) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("重复", modifier = Modifier.weight(1f))
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(repeatRule?.let { if (it == "daily") "每天" else "每周" } ?: "一次性")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("一次性") }, onClick = { repeatRule = null; expanded = false })
                        DropdownMenuItem(text = { Text("每天") }, onClick = { repeatRule = "daily"; expanded = false })
                        DropdownMenuItem(text = { Text("每周（同星期几）") }, onClick = { repeatRule = "weekly"; expanded = false })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && dateState.selectedDateMillis != null,
                onClick = {
                    // selectedDateMillis 是 UTC 毫秒：从 UTC 日历取"那天"的年月日，
                    // 再装进本地日历 + 时间（全天事件那套 UTC 逻辑，别对本地毫秒做偏移）
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = dateState.selectedDateMillis!!
                    }
                    val local = Calendar.getInstance().apply {
                        set(
                            utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
                            timeState.hour, timeState.minute, 0
                        )
                        set(Calendar.MILLISECOND, 0)
                    }
                    // 重复提醒选当天已过时间：推进到下一次（daily+1天/weekly+7天），
                    // 避免闹钟排在过去立刻触发；一次性提醒维持现状
                    val trigger = if (repeatRule != null) {
                        ReminderScheduler.nextOccurrence(
                            local.timeInMillis, repeatRule, System.currentTimeMillis()
                        ) ?: local.timeInMillis
                    } else local.timeInMillis
                    onConfirm(title.trim(), trigger, repeatRule)
                }
            ) { Text(if (initial == null) "添加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = { showDatePicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = dateState)
        }
    }
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = { TextButton(onClick = { showTimePicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = timeState) }
        )
    }
}

/** 当天 UTC 午夜毫秒（DatePicker 要求 UTC） */
private fun utcMidnightMillis(cal: Calendar): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return utc.timeInMillis
}

private val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
private val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatDate(utcMillis: Long): String {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return "%d年%d月%d日".format(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH))
}

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

private fun repeatLabel(repeat: String?): String = when (repeat) {
    "daily" -> " · 每天"
    "weekly" -> " · 每周"
    else -> ""
}

private fun statusLabel(status: String): String = when (status) {
    "pending" -> ""
    "fired" -> " · 已触发"
    "cancelled" -> " · 已取消"
    else -> ""
}
